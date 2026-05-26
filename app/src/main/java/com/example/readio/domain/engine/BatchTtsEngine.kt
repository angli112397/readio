package com.example.readio.domain.engine

import com.example.readio.domain.model.Sentence
import com.example.readio.domain.model.TtsConfig
import com.example.readio.domain.model.TtsProvider
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Contract for pre-synthesis (offline/batch) TTS engines.
 *
 * I/O contract — the ONLY thing this interface cares about:
 *   IN:  [sentences] + [TtsConfig] + [cacheDir]
 *   OUT: [cacheDir]/manifest.json  (a valid [SynthesisManifest])
 *        [cacheDir]/audio.mp3      (for SINGLE_FILE engines)
 *        [cacheDir]/0.wav … N.wav  (for PER_SENTENCE engines)
 *
 * Implementation details that are deliberately NOT part of this interface:
 *   - Whether synthesis uses an async submit-poll-fetch cycle or is synchronous
 *   - Whether audio is a single file or per-sentence files
 *   - How timestamps are derived (API-returned or computed locally)
 *   - Internal crash-recovery mechanisms (task-ID persistence, etc.)
 *
 * Current implementations:
 *   - [VolcengineEngine] → [TtsProvider.VOLCENGINE]
 *       Internally: async submit → auto-poll → download MP3 → write manifest
 *
 * Adding a new batch engine:
 *   1. Implement this interface (in data/audio/batch/).
 *   2. Add `@Binds @Singleton @IntoSet` in [TtsEngineModule].
 *   3. Add provider entry + voices to [TtsVoiceCatalog].
 */
interface BatchTtsEngine {

    val provider: TtsProvider

    /**
     * Pre-synthesize [sentences] and write the result to [cacheDir].
     *
     * Completion contract: when [BatchSynthesisEvent.Complete] is emitted,
     * [cacheDir]/manifest.json must exist and be parseable as a [SynthesisManifest],
     * and all audio files referenced by that manifest must exist.
     *
     * Resumability: implementations should check [cacheDir] for partially-completed
     * state (e.g. a saved task ID) and resume rather than restart from scratch.
     *
     * The returned Flow is cold and fully cancellable.
     */
    fun synthesize(
        sentences: List<Sentence>,
        config: TtsConfig,
        cacheDir: File
    ): Flow<BatchSynthesisEvent>

    /**
     * Load the [SynthesisManifest] for the given [cacheDir].
     * Returns null if synthesis has not completed for this directory.
     */
    fun loadManifest(cacheDir: File): SynthesisManifest?

    /** Returns true if a complete manifest exists for [chapterId] with [config]. */
    fun hasChapter(chapterId: String, config: TtsConfig): Boolean

    /**
     * Returns the pending task ID if an async synthesis task was submitted for [chapterId]
     * but the result has not yet been downloaded. Lets [AudioDownloadManager] show a
     * "pending" status after an app restart without re-submitting the task.
     *
     * Always returns null for synchronous engines (default).
     */
    fun pendingTaskId(chapterId: String, config: TtsConfig): String? = null

    /**
     * Persist an externally-obtained task ID for [chapterId].
     * Useful for the "import task ID" UI flow (user pastes ID from a previous session).
     * No-op by default; only async engines need to implement this.
     */
    fun importTaskId(chapterId: String, taskId: String, config: TtsConfig) {}

    /** Delete all cached audio and intermediate files for [chapterId]. */
    fun clearChapter(chapterId: String)

    /**
     * Delete all cached audio for [chapterId], also cleaning up any engine-specific
     * server-side resources (e.g. async job records) associated with [config].
     *
     * Default implementation ignores [config] and delegates to [clearChapter].
     * Override in engines that maintain server-side state (e.g. [GptSoVitsEngine]).
     */
    fun clearChapter(chapterId: String, config: TtsConfig) = clearChapter(chapterId)

    /** Delete all audio managed by this engine across all chapters. */
    fun clearAll()
}
