package com.example.readio.domain.manager

import com.example.readio.domain.model.ChapterAudioStatus
import com.example.readio.domain.model.ChapterIndex
import com.example.readio.domain.model.TtsProvider
import com.example.readio.domain.repository.EpubRepository
import com.example.readio.domain.repository.SettingsRepository
import com.example.readio.domain.tts.CloudTtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadManagerState(
    val statusMap: Map<String, ChapterAudioStatus> = emptyMap(),
    val isBulkDownloading: Boolean = false,
    val bulkDone: Int = 0,
    val bulkTotal: Int = 0
)

@Singleton
class AudioDownloadManager @Inject constructor(
    cloudEngines: Set<@JvmSuppressWildcards CloudTtsEngine>,
    private val epubRepository: EpubRepository,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** O(1) engine lookup by provider — no when/if chains needed when adding new providers. */
    private val engineMap: Map<TtsProvider, CloudTtsEngine> =
        cloudEngines.associateBy { it.provider }

    private val _state = MutableStateFlow(DownloadManagerState())
    val state: StateFlow<DownloadManagerState> = _state.asStateFlow()

    /** One-shot messages for the UI (e.g., "Task still pending"). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var bulkJob: Job? = null
    private var lastChapters: List<ChapterIndex> = emptyList()

    init {
        // Re-check statuses when TTS config changes (provider switch, voice change).
        scope.launch {
            settingsRepository.observeTtsConfig()
                .map { it.cacheKey }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    _state.update { it.copy(statusMap = emptyMap()) }
                    checkStatuses(lastChapters)
                }
        }
    }

    fun checkStatuses(chapters: List<ChapterIndex>) {
        if (chapters.isNotEmpty()) lastChapters = chapters
        scope.launch {
            val ttsConfig = settingsRepository.getTtsConfig()

            // LOCAL_ANDROID: no download concept — mark all as NotApplicable
            if (ttsConfig.provider == TtsProvider.LOCAL_ANDROID) {
                val statuses = chapters.map { it.id to ChapterAudioStatus.NotApplicable }
                _state.update { it.copy(statusMap = it.statusMap + statuses) }
                return@launch
            }

            val engine  = engineMap[ttsConfig.provider] ?: return@launch
            val unknown = chapters.filter { _state.value.statusMap[it.id] == null }
            if (unknown.isEmpty()) return@launch

            val deferred = unknown.map { chapter ->
                scope.async {
                    val taskId = engine.loadTaskId(chapter.id)
                    val status: ChapterAudioStatus = when {
                        taskId != null                            -> ChapterAudioStatus.HasTaskId(taskId)
                        engine.hasChapter(chapter.id, ttsConfig) -> ChapterAudioStatus.Downloaded
                        else                                      -> ChapterAudioStatus.NotDownloaded
                    }
                    chapter.id to status
                }
            }
            val statuses = deferred.map { it.await() }
            _state.update { it.copy(statusMap = it.statusMap + statuses) }
        }
    }

    /**
     * Submit a TTS task for [chapter] and transition to [ChapterAudioStatus.HasTaskId].
     * Does NOT poll — the user triggers [fetchChapterResult] explicitly.
     */
    fun submitChapterTask(bookId: String, chapter: ChapterIndex) {
        if (_state.value.statusMap[chapter.id] is ChapterAudioStatus.Downloading) return
        scope.launch {
            setStatus(chapter.id, ChapterAudioStatus.Downloading(0f))
            try {
                val ttsConfig = settingsRepository.getTtsConfig()
                val engine    = engineMap[ttsConfig.provider]
                    ?: throw IllegalStateException("No cloud engine for provider ${ttsConfig.provider}")
                val prefs       = settingsRepository.getReadingPreferences()
                val chapterFull = epubRepository.loadChapter(bookId, chapter.id, prefs.chunkSize)
                val taskId      = engine.submitChapter(chapterFull, ttsConfig)
                setStatus(chapter.id, ChapterAudioStatus.HasTaskId(taskId))
            } catch (e: kotlinx.coroutines.CancellationException) {
                setStatus(chapter.id, ChapterAudioStatus.NotDownloaded)
                throw e
            } catch (e: Exception) {
                setStatus(chapter.id, ChapterAudioStatus.Error(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Persist an existing [taskId] for [chapterId] without making any API call.
     * Use this to import a task ID obtained externally (e.g., from a previous session).
     */
    fun importTaskId(chapterId: String, taskId: String) {
        scope.launch {
            val ttsConfig = settingsRepository.getTtsConfig()
            engineMap[ttsConfig.provider]?.saveTaskId(chapterId, taskId)
        }
        setStatus(chapterId, ChapterAudioStatus.HasTaskId(taskId))
    }

    /**
     * Query the task status once and download the audio if it's ready.
     * If still pending, emits a message and restores [ChapterAudioStatus.HasTaskId].
     */
    fun fetchChapterResult(bookId: String, chapter: ChapterIndex) {
        val status = _state.value.statusMap[chapter.id]
        val taskId = (status as? ChapterAudioStatus.HasTaskId)?.taskId ?: return
        scope.launch {
            setStatus(chapter.id, ChapterAudioStatus.Downloading(0f))
            try {
                val ttsConfig = settingsRepository.getTtsConfig()
                val engine    = engineMap[ttsConfig.provider]
                    ?: throw IllegalStateException("No cloud engine for provider ${ttsConfig.provider}")
                val prefs       = settingsRepository.getReadingPreferences()
                val chapterFull = epubRepository.loadChapter(bookId, chapter.id, prefs.chunkSize)

                val audio = engine.fetchIfReady(taskId, chapterFull, ttsConfig) { done, total ->
                    if (total > 0) setStatus(chapter.id,
                        ChapterAudioStatus.Downloading(done.toFloat() / total))
                }

                if (audio == null) {
                    setStatus(chapter.id, ChapterAudioStatus.HasTaskId(taskId))
                    _messages.tryEmit("任务还在处理中，请稍后再试")
                } else {
                    setStatus(chapter.id, ChapterAudioStatus.Downloaded)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                setStatus(chapter.id, ChapterAudioStatus.HasTaskId(taskId))
                throw e
            } catch (e: Exception) {
                setStatus(chapter.id, ChapterAudioStatus.Error(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Submit TTS tasks for all chapters that don't have audio yet.
     * Tasks are submitted in parallel — very fast, no waiting for synthesis.
     */
    fun submitAll(bookId: String, chapters: List<ChapterIndex>) {
        bulkJob?.cancel()
        val pending = chapters.filter {
            _state.value.statusMap[it.id].let { s ->
                s is ChapterAudioStatus.NotDownloaded || s is ChapterAudioStatus.Error
            }
        }
        if (pending.isEmpty()) return
        _state.update { it.copy(isBulkDownloading = true, bulkDone = 0, bulkTotal = pending.size) }
        bulkJob = scope.launch {
            try {
                val ttsConfig = settingsRepository.getTtsConfig()
                val engine    = engineMap[ttsConfig.provider]
                    ?: run {
                        _state.update { it.copy(isBulkDownloading = false) }
                        return@launch
                    }
                val prefs = settingsRepository.getReadingPreferences()
                val jobs  = pending.map { chapter ->
                    async {
                        if (!isActive) return@async
                        try {
                            val chapterFull = epubRepository.loadChapter(
                                bookId, chapter.id, prefs.chunkSize)
                            val taskId = engine.submitChapter(chapterFull, ttsConfig)
                            setStatus(chapter.id, ChapterAudioStatus.HasTaskId(taskId))
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            setStatus(chapter.id,
                                ChapterAudioStatus.Error(e.message ?: "Submit failed"))
                        }
                        _state.update { it.copy(bulkDone = it.bulkDone + 1) }
                    }
                }
                jobs.forEach { it.await() }
            } finally {
                _state.update { it.copy(isBulkDownloading = false) }
            }
        }
    }

    fun cancelBulk() {
        bulkJob?.cancel()
        _state.update { it.copy(isBulkDownloading = false) }
    }

    /**
     * Delete all audio and task ID for [chapterId] across all cloud engines.
     * Clearing across engines ensures no stale data if the user switches providers.
     */
    fun clearChapter(chapterId: String) {
        scope.launch {
            engineMap.values.forEach { it.clearChapter(chapterId) }
            setStatus(chapterId, ChapterAudioStatus.NotDownloaded)
        }
    }

    private fun setStatus(chapterId: String, status: ChapterAudioStatus) =
        _state.update { it.copy(statusMap = it.statusMap + (chapterId to status)) }
}
