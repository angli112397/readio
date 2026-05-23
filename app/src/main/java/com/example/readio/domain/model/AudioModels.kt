package com.example.readio.domain.model

import java.io.File

enum class TtsProvider(val displayName: String) {
    LOCAL_ANDROID("系统 TTS（实时）"),
    VOLCENGINE("火山引擎豆包（离线）")
}

data class TtsConfig(
    val provider: TtsProvider = TtsProvider.LOCAL_ANDROID,
    // LOCAL_ANDROID
    val androidLocale: String = "zh-CN",
    // VOLCENGINE
    val volcAppId: String = "",
    val volcAccessKey: String = "",
    val volcResourceId: String = "seed-tts-2.0",
    val volcSpeaker: String = "",
    // Common
    val speechRate: Float = 1.0f
) {
    // Rate excluded from cache key — applied locally via ExoPlayer setPlaybackSpeed.
    val cacheKey: String get() = when (provider) {
        TtsProvider.LOCAL_ANDROID -> "LOCAL|$androidLocale"
        TtsProvider.VOLCENGINE   -> "VOLC|$volcSpeaker"
    }
}

/** Timestamp for one sentence in the Volcengine chapter audio. */
data class SentenceTimestamp(val startMs: Long, val endMs: Long)

sealed class AudioSource {
    /**
     * Local TTS: one WAV per sentence in [cacheDir] (ephemeral, system-managed).
     * files[i] corresponds to ExoPlayer playlist item i.
     */
    data class PerSentence(val files: List<File>) : AudioSource()

    /**
     * Volcengine: single MP3 for the entire chapter + per-sentence timestamps.
     * ExoPlayer items are built with [MediaItem.ClippingConfiguration].
     */
    data class SingleFile(
        val audioFile: File,
        val timestamps: List<SentenceTimestamp>
    ) : AudioSource()
}

/**
 * Resolved audio for a chapter, ready for ExoPlayer.
 *
 * [sentenceToChunk] unifies sync for both audio sources:
 *   - PerSentence: sentenceToChunk[i] = chapter.sentences[i].chunkIndex
 *   - SingleFile:  sentenceToChunk[i] = Volcengine sentence i → nearest display chunk
 *
 * This lets [ReaderViewModel] use the same onMediaItemTransition logic regardless of source.
 */
data class ChapterAudio(
    val chapterId: String,
    val source: AudioSource,
    /**
     * Maps ExoPlayer playlist index → display chunk index.
     * For LOCAL_ANDROID: sentenceToChunk[playlistIndex + playlistOffset] = chunkIndex
     * For VOLCENGINE:    sentenceToChunk[playlistIndex] = chunkIndex  (offset always 0)
     */
    val sentenceToChunk: List<Int>,
    val config: TtsConfig,
    /**
     * Sentence index of playlist item 0 — non-zero when LOCAL_ANDROID synthesis
     * starts mid-chapter (user was already at a later position).
     * sentenceIndex = playlistIndex + playlistOffset.
     */
    val playlistOffset: Int = 0
)
