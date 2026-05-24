package com.example.readio.domain.tts

import com.example.readio.domain.model.Chapter
import com.example.readio.domain.model.ChapterAudio
import com.example.readio.domain.model.TtsConfig
import com.example.readio.domain.model.TtsProvider

/**
 * Contract for a cloud-based TTS provider that synthesizes entire chapters asynchronously
 * and returns a single downloadable audio file with sentence-level timestamps.
 *
 * Implementations live in the data layer; new providers require:
 *   1. Implement this interface.
 *   2. Add one [@Binds @IntoSet] line in [com.example.readio.di.TtsEngineModule].
 *   3. Add the [TtsProvider] entry + voices to [TtsVoiceCatalog].
 *
 * The split-then-fetch model (submit → user waits → fetch) is intentional: synthesis
 * takes minutes for a chapter, and auto-polling would consume paid quota unpredictably.
 */
interface CloudTtsEngine {

    val provider: TtsProvider

    /**
     * Submit the chapter for async synthesis and return the assigned task ID.
     * Task ID is persisted to disk automatically — survives app restarts.
     * Does NOT poll; the caller decides when to call [fetchIfReady].
     *
     * @throws IOException on network or API errors.
     */
    suspend fun submitChapter(chapter: Chapter, config: TtsConfig): String

    /**
     * Query the submitted task once. If ready, download + persist the audio and return
     * a [ChapterAudio] ready for ExoPlayer. Returns null if synthesis is still in progress.
     *
     * @param onProgress called with (done, total) during audio download
     * @return resolved [ChapterAudio] on success; null if still processing
     * @throws IOException on permanent server-side failure
     */
    suspend fun fetchIfReady(
        taskId: String,
        chapter: Chapter,
        config: TtsConfig,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ChapterAudio?

    /** Load previously-downloaded audio from disk; null if absent or cache key mismatch. */
    fun loadCached(chapter: Chapter, config: TtsConfig): ChapterAudio?

    /** True if audio for [chapterId] is cached on disk for the given [config]. */
    fun hasChapter(chapterId: String, config: TtsConfig): Boolean

    fun loadTaskId(chapterId: String): String?
    fun saveTaskId(chapterId: String, taskId: String)

    /** Delete the audio file, metadata, and task ID for this chapter. */
    fun clearChapter(chapterId: String)

    /** Delete all audio managed by this engine. */
    fun clearAll()
}
