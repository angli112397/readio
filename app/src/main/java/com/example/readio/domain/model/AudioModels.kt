package com.example.readio.domain.model

import com.example.readio.domain.engine.SentenceTiming
import java.io.File

enum class TtsProvider(val displayName: String) {
    LOCAL_ANDROID("系统 TTS（实时）"),
    VOLCENGINE("火山引擎（云端）"),
    GPT_SO_VITS("GPT-SoVITS（本地推理）")
}

data class TtsConfig(
    val provider: TtsProvider = TtsProvider.LOCAL_ANDROID,
    // LOCAL_ANDROID
    val androidLocale: String = "zh-CN",
    // VOLCENGINE — 精品长文本 v1 API (cloud only)
    val volcAppId: String = "",
    val volcAccessKey: String = "",   // Bearer token
    val volcSpeaker: String = "",     // voice_type (e.g. "BV406_V2_streaming")
    // GPT_SO_VITS — local GPU inference server (readio-tts API, no auth)
    val gptSoVitsUrl: String = "",
    val gptSoVitsVoice: String = "",  // voice_id referencing references/gpt/; empty = server default
    // Common
    val speechRate: Float = 1.0f
) {
    /**
     * Cache key encodes the synthesis parameters that affect audio content.
     * Speech rate is excluded — applied locally via ExoPlayer setPlaybackSpeed.
     */
    val cacheKey: String get() = when (provider) {
        TtsProvider.LOCAL_ANDROID -> "LOCAL|$androidLocale"
        TtsProvider.VOLCENGINE    -> "VOLC|$volcSpeaker"
        TtsProvider.GPT_SO_VITS   -> "GPT|$gptSoVitsVoice"
    }

    /** True when the active TTS provider supports batch pre-download. */
    val isBatchProvider: Boolean
        get() = provider == TtsProvider.VOLCENGINE || provider == TtsProvider.GPT_SO_VITS

    /**
     * Returns a copy of this config with [provider] and [voiceId] applied as a per-book override.
     * [provider] null → returns this unchanged.
     * Maps [voiceId] to the correct field based on provider.
     */
    fun applyBookOverride(provider: TtsProvider?, voiceId: String?): TtsConfig {
        if (provider == null) return this
        return copy(
            provider      = provider,
            // Empty voiceId means "use global default" — don't overwrite with blank.
            androidLocale    = if (provider == TtsProvider.LOCAL_ANDROID)
                                   voiceId?.takeIf { it.isNotEmpty() } ?: androidLocale
                               else androidLocale,
            volcSpeaker      = if (provider == TtsProvider.VOLCENGINE)
                                   voiceId?.takeIf { it.isNotEmpty() } ?: volcSpeaker
                               else volcSpeaker,
            gptSoVitsVoice   = if (provider == TtsProvider.GPT_SO_VITS)
                                   voiceId?.takeIf { it.isNotEmpty() } ?: gptSoVitsVoice
                               else gptSoVitsVoice,
        )
    }
}

sealed class AudioSource {
    /**
     * Realtime TTS (LOCAL_ANDROID): one WAV per true sentence in a temporary directory.
     * [files][i] corresponds to ExoPlayer playlist item i.
     */
    data class PerSentence(val files: List<File>) : AudioSource()

    /**
     * Batch TTS (VOLCENGINE cloud or FISH_SPEECH local): single audio file for the entire
     * chapter plus per-sentence timings. Position-polling drives ChunkWheel sync.
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
