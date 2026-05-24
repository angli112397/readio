package com.example.readio.domain.engine

/**
 * Canonical on-disk format for a completed batch synthesis.
 *
 * Written to [AudioCache.chapterDir]/manifest.json by [BatchTtsEngine] implementations
 * and read by [AudioRepositoryImpl] to build the [ChapterAudio] handed to ExoPlayer.
 *
 * Engine-agnostic: Volcengine (single MP3 + API timestamps), a future per-sentence engine,
 * or any other implementation all produce the same manifest shape.
 */
data class SynthesisManifest(
    val version: Int = 1,
    val format: AudioFormat,
    /**
     * Number of timing entries. Equals [timings].size.
     * For SINGLE_FILE: = number of sentences the API returned (may differ from chapter.sentences).
     * For PER_SENTENCE: = chapter.sentences.size.
     */
    val sentenceCount: Int,
    val timings: List<SentenceTiming>
)

/** How the audio content is stored on disk. */
enum class AudioFormat {
    /**
     * One audio file for the entire chapter (e.g. Volcengine async MP3).
     * File name: "audio.mp3" in the chapter cache directory.
     * Playback: single ExoPlayer item; chunk sync driven by position-polling + [SentenceTiming].
     */
    SINGLE_FILE,

    /**
     * One audio file per synthesis atom.
     * File names: "0.wav", "1.wav", …, "N.wav" in the chapter cache directory.
     * Playback: ExoPlayer playlist; chunk sync driven by onMediaItemTransition.
     */
    PER_SENTENCE
}

/**
 * Timing record for one synthesis atom.
 *
 * For [AudioFormat.SINGLE_FILE]: atom = one sentence as segmented by the remote TTS API.
 *   [sentenceIndex] is the atom's position in the API response, NOT necessarily the same
 *   as [com.example.readio.domain.model.Sentence.indexInChapter].
 *
 * For [AudioFormat.PER_SENTENCE]: atom = one [com.example.readio.domain.model.Sentence].
 *   [sentenceIndex] equals [com.example.readio.domain.model.Sentence.indexInChapter].
 */
data class SentenceTiming(
    /** Zero-based index of this atom in its sequence (API or chapter). */
    val sentenceIndex: Int,
    val startMs: Long,
    val endMs: Long,
    /**
     * Display chunk ([com.example.readio.domain.model.Chunk.indexInChapter]) this atom belongs to.
     * Stored here so the player can drive ChunkWheel without re-querying the chapter model.
     */
    val chunkIndex: Int
)

/**
 * Events emitted by [BatchTtsEngine.synthesize].
 * Translated by [AudioDownloadManager] into [ChapterAudioStatus] for the UI.
 */
sealed class BatchSynthesisEvent {
    /** Intermediate progress; [label] is a user-visible hint (e.g. "提交任务…", "下载音频…"). */
    data class Progress(val done: Int, val total: Int, val label: String = "") : BatchSynthesisEvent()
    /** Synthesis complete; cacheDir now contains a valid manifest + audio file(s). */
    data object Complete : BatchSynthesisEvent()
    /** Unrecoverable failure; synthesis did NOT complete. */
    data class Failed(val error: Throwable) : BatchSynthesisEvent()
}
