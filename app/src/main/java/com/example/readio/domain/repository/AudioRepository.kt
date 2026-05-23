package com.example.readio.domain.repository

import com.example.readio.domain.model.Chapter
import com.example.readio.domain.model.ChapterAudio
import com.example.readio.domain.model.TtsConfig
import java.io.File
import kotlinx.coroutines.flow.Flow

sealed class ChapterAudioState {
    data class Generating(val done: Int, val total: Int) : ChapterAudioState()
    /** [index] is the ExoPlayer playlist index (= sentence index). [file] is the audio file. */
    data class ChunkReady(val index: Int, val file: File) : ChapterAudioState()
    data class Ready(val audio: ChapterAudio) : ChapterAudioState()
    data class Error(val message: String) : ChapterAudioState()
}

/** Result of a one-shot Volcengine task query. */
sealed class TtsTaskResult {
    /** Task is still processing on Volcengine's servers — try again later. */
    data object Pending : TtsTaskResult()
    /** Task completed and audio has been saved to disk. */
    data class Complete(val audio: ChapterAudio) : TtsTaskResult()
    /** Task failed permanently on the server side. */
    data class Failed(val message: String) : TtsTaskResult()
}

interface AudioRepository {
    /**
     * Returns a Flow of [ChapterAudioState] for **playback**:
     *  - LOCAL_ANDROID: synthesizes sentences one by one starting from [startSentenceIndex].
     *  - VOLCENGINE: loads from disk only; emits Error if not yet cached.
     */
    fun getChapterAudio(
        chapter: Chapter,
        ttsConfig: TtsConfig,
        startSentenceIndex: Int = 0
    ): Flow<ChapterAudioState>

    /**
     * Downloads and caches chapter audio (for explicit user-triggered download only).
     *  - LOCAL_ANDROID: no-op, emits Ready immediately.
     *  - VOLCENGINE: calls the API, saves MP3 + metadata to filesDir, emits Generating progress.
     */
    fun downloadChapterAudio(chapter: Chapter, ttsConfig: TtsConfig): Flow<ChapterAudioState>

    // ── Volcengine async task flow ────────────────────────────────────────────

    /**
     * Submit a TTS task to Volcengine without waiting for completion.
     * Returns the task ID and persists it to disk.
     * Does NOT poll — the user triggers result fetching explicitly.
     */
    suspend fun submitTask(chapter: Chapter, ttsConfig: TtsConfig): String

    /**
     * Query a previously-submitted task once and download the audio if it's ready.
     * Returns [TtsTaskResult.Pending] if still processing (call again later),
     * [TtsTaskResult.Complete] on success, [TtsTaskResult.Failed] on server error.
     */
    suspend fun fetchTaskResult(
        taskId: String,
        chapter: Chapter,
        ttsConfig: TtsConfig,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }
    ): TtsTaskResult

    // ── Task ID persistence ───────────────────────────────────────────────────

    /** Persist [taskId] for [chapterId] so it survives app restarts. */
    fun saveTaskId(chapterId: String, taskId: String)
    /** Returns the persisted task ID for [chapterId], or null if none. */
    fun loadTaskId(chapterId: String): String?
    /** Remove the persisted task ID (called after successful fetch or explicit cancel). */
    fun clearTaskId(chapterId: String)

    // ── Cache management ──────────────────────────────────────────────────────

    /** Returns true only when Volcengine audio for [chapterId] is cached on disk. */
    suspend fun hasChapterAudio(chapterId: String, ttsConfig: TtsConfig): Boolean

    suspend fun clearChapterAudio(chapterId: String)
    suspend fun clearAllAudio()
}
