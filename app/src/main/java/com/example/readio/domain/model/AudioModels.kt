package com.example.readio.domain.model

import com.example.readio.domain.engine.SentenceTiming
import java.io.File

enum class TtsProvider(val displayName: String) {
    LOCAL_ANDROID("系统 TTS（实时）"),
    LOCAL_SHERPA_ONNX("本地神经网络（离线）"),
    VOLCENGINE("火山引擎豆包（离线）")
}

data class TtsConfig(
    val provider: TtsProvider = TtsProvider.LOCAL_ANDROID,
    // LOCAL_ANDROID
    val androidLocale: String = "zh-CN",
    // VOLCENGINE — 精品长文本 v1 API
    val volcAppId: String = "",
    val volcAccessKey: String = "",   // used as Bearer token
    val volcSpeaker: String = "",     // voice_type, e.g. "BV001_streaming"
    // Common
    val speechRate: Float = 1.0f
) {
    // Rate excluded from cache key — applied locally via ExoPlayer setPlaybackSpeed.
    val cacheKey: String get() = when (provider) {
        TtsProvider.LOCAL_ANDROID     -> "LOCAL|$androidLocale"
        TtsProvider.LOCAL_SHERPA_ONNX -> "SHERPA"
        TtsProvider.VOLCENGINE        -> "VOLC|$volcSpeaker"
    }

    /**
     * Returns a copy of this config with [provider] and [voiceId] applied as a per-book override.
     * [provider] null → returns this unchanged.
     * Maps [voiceId] to the correct field: androidLocale for LOCAL_ANDROID,
     * volcSpeaker for VOLCENGINE; for LOCAL_SHERPA_ONNX the voice field is irrelevant.
     */
    fun applyBookOverride(provider: TtsProvider?, voiceId: String?): TtsConfig {
        if (provider == null) return this
        return copy(
            provider      = provider,
            androidLocale = if (provider == TtsProvider.LOCAL_ANDROID) voiceId ?: androidLocale else androidLocale,
            volcSpeaker   = if (provider == TtsProvider.VOLCENGINE)    voiceId ?: volcSpeaker   else volcSpeaker,
        )
    }
}

sealed class AudioSource {
    /**
     * Realtime TTS (LOCAL_ANDROID, LOCAL_SHERPA_ONNX): one WAV per sentence in cacheDir.
     * [files][i] corresponds to ExoPlayer playlist item i.
     */
    data class PerSentence(val files: List<File>) : AudioSource()

    /**
     * Batch TTS (Volcengine): single MP3 for the entire chapter + per-atom timings.
     * [timings][i].startMs/endMs drives ChunkWheel sync via position-polling.
     */
    data class SingleFile(
        val audioFile: File,
        val timings: List<SentenceTiming>
    ) : AudioSource()
}

/**
 * Resolved audio for a chapter, ready for ExoPlayer.
 *
 * [sentenceToChunk] unifies sync for both audio sources:
 *   - PerSentence:  sentenceToChunk[playlistIndex + playlistOffset] = chunkIndex
 *   - SingleFile:   sentenceToChunk[i] = timings[i].chunkIndex (from SynthesisManifest)
 */
data class ChapterAudio(
    val chapterId: String,
    val source: AudioSource,
    val sentenceToChunk: List<Int>,
    val config: TtsConfig,
    /**
     * Sentence index of playlist item 0 — non-zero when realtime synthesis starts mid-chapter.
     * sentenceIndex = playlistIndex + playlistOffset.
     */
    val playlistOffset: Int = 0
)
