package com.example.readio.domain.manager

import android.util.Log
import com.example.readio.data.audio.cache.AudioCache
import com.example.readio.domain.engine.BatchSynthesisEvent
import com.example.readio.domain.engine.BatchTtsEngine
import com.example.readio.domain.model.Chapter
import com.example.readio.domain.model.ChapterAudioStatus
import com.example.readio.domain.model.ChapterIndex
import com.example.readio.domain.model.TtsConfig
import com.example.readio.domain.model.TtsProvider
import com.example.readio.domain.repository.EpubRepository
import com.example.readio.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadManagerState(
    val statusMap: Map<String, ChapterAudioStatus> = emptyMap()
)

@Singleton
class AudioDownloadManager @Inject constructor(
    batchEngines: Set<@JvmSuppressWildcards BatchTtsEngine>,
    private val epubRepository: EpubRepository,
    private val settingsRepository: SettingsRepository,
    private val audioCache: AudioCache
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val batchEngineMap: Map<TtsProvider, BatchTtsEngine> =
        batchEngines.associateBy { it.provider }

    private val _state = MutableStateFlow(DownloadManagerState())
    val state: StateFlow<DownloadManagerState> = _state.asStateFlow()

    /**
     * Thread-safe job registry. Written from any thread (callers may be on Main or IO);
     * iterated and cleared from the IO-scope config-change handler.
     * [ConcurrentHashMap] prevents ConcurrentModificationException on concurrent access.
     */
    private val chapterJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    companion object { private const val TAG = "AudioDownloadManager" }

    init {
        // Cancel all in-flight downloads and clear cached statuses when the TTS
        // provider or voice changes so the new config starts with a clean slate.
        scope.launch {
            settingsRepository.observeTtsConfig()
                .map { it.cacheKey }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    chapterJobs.values.forEach { it.cancel() }
                    chapterJobs.clear()
                    _state.update { it.copy(statusMap = emptyMap()) }
                }
        }
    }

    // ── Status checking ───────────────────────────────────────────────────────

    /**
     * Populate [state.statusMap] for chapters not yet known.
     * Safe to call multiple times — only processes chapters with null status.
     */
    fun checkStatuses(chapters: List<ChapterIndex>, ttsConfig: TtsConfig? = null) {
        val unknownIds = chapters.filter { _state.value.statusMap[it.id] == null }.map { it.id }
        if (unknownIds.isEmpty()) return
        scope.launch {
            val config = ttsConfig ?: settingsRepository.getTtsConfig()
            val engine = batchEngineMap[config.provider]
            setStatuses(unknownIds.associateWith { id ->
                if (engine == null) return@associateWith ChapterAudioStatus.NotApplicable
                val pending = engine.pendingTaskId(id, config)
                when {
                    engine.hasChapter(id, config) -> ChapterAudioStatus.Downloaded
                    pending != null               -> ChapterAudioStatus.HasTaskId(pending)
                    else                          -> ChapterAudioStatus.NotDownloaded
                }
            })
        }
    }

    /**
     * Seed the initial status for [chapterId] if not yet in the map.
     *
     * Uses [setStatusIfAbsent] at write time so a concurrent [clearChapter] that already
     * wrote NotDownloaded is never overwritten with a stale Downloaded result.
     */
    fun ensureStatusChecked(chapterId: String, ttsConfig: TtsConfig) {
        if (_state.value.statusMap[chapterId] != null) return
        scope.launch {
            val engine = batchEngineMap[ttsConfig.provider]
                ?: run { setStatusIfAbsent(chapterId, ChapterAudioStatus.NotApplicable); return@launch }
            val pending = engine.pendingTaskId(chapterId, ttsConfig)
            val status: ChapterAudioStatus = when {
                engine.hasChapter(chapterId, ttsConfig) -> ChapterAudioStatus.Downloaded
                pending != null                         -> ChapterAudioStatus.HasTaskId(pending)
                else                                    -> ChapterAudioStatus.NotDownloaded
            }
            setStatusIfAbsent(chapterId, status)
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Start (or resume) synthesis for [chapter] using its already-parsed sentence list.
     * Called from the reader where the chapter is already in memory — avoids re-parsing EPUB.
     */
    fun downloadChapterFull(chapter: Chapter, ttsConfig: TtsConfig? = null) {
        val id = chapter.id
        if (_state.value.statusMap[id] is ChapterAudioStatus.Downloading) return
        chapterJobs[id]?.cancel()
        chapterJobs[id] = scope.launch {
            val config = ttsConfig ?: settingsRepository.getTtsConfig()
            val engine = batchEngineMap[config.provider]
                ?: run { setStatus(id, ChapterAudioStatus.Error("当前 TTS 模式不支持离线下载")); return@launch }
            runSynthesis(chapter.sentences, id, engine, config)
        }
    }

    /**
     * Start (or resume) synthesis for [chapter] by loading its content from the EPUB.
     * Use [downloadChapterFull] instead when the chapter object is already available.
     */
    fun downloadChapter(bookId: String, chapter: ChapterIndex, ttsConfig: TtsConfig? = null) {
        val id = chapter.id
        if (_state.value.statusMap[id] is ChapterAudioStatus.Downloading) return
        chapterJobs[id]?.cancel()
        chapterJobs[id] = scope.launch {
            val config = ttsConfig ?: settingsRepository.getTtsConfig()
            val engine = batchEngineMap[config.provider]
                ?: run { setStatus(id, ChapterAudioStatus.Error("当前 TTS 模式不支持离线下载")); return@launch }
            val prefs = settingsRepository.getReadingPreferences()
            val chapterFull = runCatching {
                epubRepository.loadChapter(bookId, id, prefs.chunkSize)
            }.getOrElse { e ->
                setStatus(id, ChapterAudioStatus.Error(e.message ?: "章节加载失败")); return@launch
            }
            runSynthesis(chapterFull.sentences, id, engine, config)
        }
    }

    // ── Clearing ──────────────────────────────────────────────────────────────

    /**
     * Cancel any in-flight synthesis and delete all cached audio for [chapterId].
     *
     * Handles both "cancel during download" and "delete completed audio"; both cases
     * reset to NotDownloaded so the user can re-trigger synthesis.
     *
     * Status is set eagerly before the async file deletion so the UI responds immediately.
     * Idempotent: concurrent calls safely overlap because file deletions are no-ops on
     * already-deleted paths.
     */
    fun clearChapter(chapterId: String) {
        chapterJobs[chapterId]?.cancel()
        chapterJobs.remove(chapterId)
        setStatus(chapterId, ChapterAudioStatus.NotDownloaded)
        scope.launch {
            // Pass the current config so engines with server-side state (e.g. GPT-SoVITS)
            // can DELETE the associated job and free the idempotency key slot.
            val config = settingsRepository.getTtsConfig()
            batchEngineMap.values.forEach { it.clearChapter(chapterId, config) }
        }
    }

    // ── Private synthesis loop ────────────────────────────────────────────────

    /**
     * Shared synthesis coroutine body used by both [downloadChapterFull] and [downloadChapter].
     * Drives the submit → poll → download flow and translates [BatchSynthesisEvent]s
     * into [ChapterAudioStatus] updates.
     */
    private suspend fun runSynthesis(
        sentences: List<com.example.readio.domain.model.Sentence>,
        chapterId: String,
        engine: BatchTtsEngine,
        config: TtsConfig
    ) {
        val cacheDir = audioCache.chapterDir(chapterId, config.cacheKey)
        setStatus(chapterId, ChapterAudioStatus.Downloading(0f))
        engine.synthesize(sentences, config, cacheDir)
            .catch { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Synthesis failed for $chapterId [${config.provider}]", e)
                setStatus(chapterId, ChapterAudioStatus.Error(e.message ?: "Unknown error"))
            }
            .collect { event ->
                when (event) {
                    is BatchSynthesisEvent.Progress  -> setStatus(chapterId,
                        ChapterAudioStatus.Downloading(
                            progress = if (event.total > 0) event.done.toFloat() / event.total else -1f,
                            label    = event.label))
                    BatchSynthesisEvent.Complete     -> setStatus(chapterId, ChapterAudioStatus.Downloaded)
                    is BatchSynthesisEvent.Submitted -> setStatus(chapterId, ChapterAudioStatus.HasTaskId(event.taskId))
                    is BatchSynthesisEvent.Failed    -> setStatus(chapterId,
                        ChapterAudioStatus.Error(event.error.message ?: "Unknown error"))
                }
            }
    }

    // ── State helpers ─────────────────────────────────────────────────────────

    private fun setStatus(chapterId: String, status: ChapterAudioStatus) =
        _state.update { it.copy(statusMap = it.statusMap + (chapterId to status)) }

    private fun setStatuses(entries: Map<String, ChapterAudioStatus>) =
        _state.update { it.copy(statusMap = it.statusMap + entries) }

    /**
     * Write [status] only if the slot is currently null (absent from the map).
     *
     * Implemented as a single [MutableStateFlow.update] call, which is atomic under
     * Kotlin's flow lock — no concurrent [clearChapter] can slip between the read and
     * the write and have its NotDownloaded overwritten by a stale hasChapter() result.
     */
    private fun setStatusIfAbsent(chapterId: String, status: ChapterAudioStatus) {
        _state.update { current ->
            if (current.statusMap[chapterId] == null)
                current.copy(statusMap = current.statusMap + (chapterId to status))
            else
                current
        }
    }
}
