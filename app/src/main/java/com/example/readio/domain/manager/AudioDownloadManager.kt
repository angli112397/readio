package com.example.readio.domain.manager

import com.example.readio.data.audio.cache.AudioCache
import com.example.readio.domain.engine.BatchSynthesisEvent
import com.example.readio.domain.engine.BatchTtsEngine
import com.example.readio.domain.model.ChapterAudioStatus
import com.example.readio.domain.model.ChapterIndex
import com.example.readio.domain.model.TtsProvider
import com.example.readio.domain.repository.EpubRepository
import com.example.readio.domain.repository.SettingsRepository
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
import kotlinx.coroutines.flow.catch
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
    batchEngines: Set<@JvmSuppressWildcards BatchTtsEngine>,
    private val epubRepository: EpubRepository,
    private val settingsRepository: SettingsRepository,
    private val audioCache: AudioCache
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** O(1) engine lookup by provider. */
    private val batchEngineMap: Map<TtsProvider, BatchTtsEngine> =
        batchEngines.associateBy { it.provider }

    private val _state = MutableStateFlow(DownloadManagerState())
    val state: StateFlow<DownloadManagerState> = _state.asStateFlow()

    /** One-shot messages for the UI (e.g., snackbar hints). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var bulkJob: Job? = null
    private var lastChapters: List<ChapterIndex> = emptyList()

    init {
        // Re-check statuses when TTS config changes (provider or voice switch).
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

    // ── Status checking ───────────────────────────────────────────────────────

    /**
     * Populate [state.statusMap] for all [chapters] that don't have a status yet.
     * Called on screen open and after config changes.
     */
    fun checkStatuses(chapters: List<ChapterIndex>) {
        if (chapters.isNotEmpty()) lastChapters = chapters
        scope.launch {
            val ttsConfig = settingsRepository.getTtsConfig()
            val engine    = batchEngineMap[ttsConfig.provider]

            if (engine == null) {
                // Realtime provider — no download concept
                val statuses = chapters.associate { it.id to ChapterAudioStatus.NotApplicable }
                _state.update { it.copy(statusMap = it.statusMap + statuses) }
                return@launch
            }

            val unknown = chapters.filter { _state.value.statusMap[it.id] == null }
            if (unknown.isEmpty()) return@launch

            val statuses = unknown.map { chapter ->
                val status: ChapterAudioStatus = when {
                    engine.hasChapter(chapter.id, ttsConfig) ->
                        ChapterAudioStatus.Downloaded

                    engine.pendingTaskId(chapter.id, ttsConfig) != null ->
                        ChapterAudioStatus.HasTaskId(
                            engine.pendingTaskId(chapter.id, ttsConfig)!!
                        )

                    else -> ChapterAudioStatus.NotDownloaded
                }
                chapter.id to status
            }
            _state.update { it.copy(statusMap = it.statusMap + statuses) }
        }
    }

    // ── Single-chapter download ───────────────────────────────────────────────

    /**
     * Download (or resume) synthesis for [chapter].
     *
     * Internally calls [BatchTtsEngine.synthesize], which handles submit → poll → download
     * as a single Flow. The engine resumes from a saved task ID if the app was previously
     * killed mid-synthesis.
     */
    fun downloadChapter(bookId: String, chapter: ChapterIndex) {
        if (_state.value.statusMap[chapter.id] is ChapterAudioStatus.Downloading) return
        scope.launch {
            setStatus(chapter.id, ChapterAudioStatus.Downloading(0f))
            val ttsConfig = settingsRepository.getTtsConfig()
            val engine    = batchEngineMap[ttsConfig.provider]
                ?: run {
                    setStatus(chapter.id, ChapterAudioStatus.Error("当前 TTS 模式不支持离线下载"))
                    return@launch
                }
            val prefs       = settingsRepository.getReadingPreferences()
            val chapterFull = runCatching {
                epubRepository.loadChapter(bookId, chapter.id, prefs.chunkSize)
            }.getOrElse { e ->
                setStatus(chapter.id, ChapterAudioStatus.Error(e.message ?: "章节加载失败"))
                return@launch
            }
            val cacheDir = audioCache.chapterDir(chapter.id, ttsConfig.cacheKey)

            engine.synthesize(chapterFull.sentences, ttsConfig, cacheDir)
                .catch { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    setStatus(chapter.id, ChapterAudioStatus.Error(e.message ?: "Unknown error"))
                }
                .collect { event ->
                    when (event) {
                        is BatchSynthesisEvent.Progress -> setStatus(
                            chapter.id,
                            ChapterAudioStatus.Downloading(
                                progress = if (event.total > 0) event.done.toFloat() / event.total else 0f,
                                label    = event.label
                            )
                        )
                        BatchSynthesisEvent.Complete ->
                            setStatus(chapter.id, ChapterAudioStatus.Downloaded)
                        is BatchSynthesisEvent.Failed ->
                            setStatus(chapter.id,
                                ChapterAudioStatus.Error(event.error.message ?: "Unknown error"))
                    }
                }
        }
    }

    /**
     * Persist an externally-provided task ID (user pastes it from a previous session).
     * Transitions status to [ChapterAudioStatus.HasTaskId] without an API call.
     */
    fun importTaskId(chapterId: String, taskId: String) {
        scope.launch {
            val ttsConfig = settingsRepository.getTtsConfig()
            batchEngineMap[ttsConfig.provider]?.importTaskId(chapterId, taskId, ttsConfig)
        }
        setStatus(chapterId, ChapterAudioStatus.HasTaskId(taskId))
    }

    // ── Bulk download ─────────────────────────────────────────────────────────

    /**
     * Download all chapters that are [NotDownloaded] or [Error].
     *
     * All chapter synthesis flows are launched concurrently so Volcengine can process them
     * in parallel on its servers while this coroutine tracks aggregate progress.
     */
    fun downloadAll(bookId: String, chapters: List<ChapterIndex>) {
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
                val engine    = batchEngineMap[ttsConfig.provider]
                    ?: run {
                        _state.update { it.copy(isBulkDownloading = false) }
                        return@launch
                    }
                val prefs = settingsRepository.getReadingPreferences()

                val jobs = pending.map { chapter ->
                    async {
                        if (!isActive) return@async
                        runCatching {
                            val chapterFull = epubRepository.loadChapter(
                                bookId, chapter.id, prefs.chunkSize)
                            val cacheDir = audioCache.chapterDir(chapter.id, ttsConfig.cacheKey)
                            engine.synthesize(chapterFull.sentences, ttsConfig, cacheDir)
                                .collect { event ->
                                    if (event is BatchSynthesisEvent.Progress) {
                                        setStatus(chapter.id, ChapterAudioStatus.Downloading(
                                            if (event.total > 0) event.done.toFloat() / event.total else 0f,
                                            event.label
                                        ))
                                    }
                                }
                            setStatus(chapter.id, ChapterAudioStatus.Downloaded)
                        }.onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            setStatus(chapter.id,
                                ChapterAudioStatus.Error(e.message ?: "下载失败"))
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

    // ── Chapter deletion ──────────────────────────────────────────────────────

    /**
     * Delete all cached audio for [chapterId] across all batch engines.
     * Clears across engines to prevent stale data if the user switches providers.
     */
    fun clearChapter(chapterId: String) {
        scope.launch {
            batchEngineMap.values.forEach { it.clearChapter(chapterId) }
            setStatus(chapterId, ChapterAudioStatus.NotDownloaded)
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun setStatus(chapterId: String, status: ChapterAudioStatus) =
        _state.update { it.copy(statusMap = it.statusMap + (chapterId to status)) }
}
