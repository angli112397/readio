package com.example.readio.ui.settings

import com.example.readio.domain.model.TtsProvider

data class VoiceOption(val id: String, val label: String)

/** A flattened (provider, voice) pair used in the per-book TTS picker. */
data class TtsChoice(
    val provider: TtsProvider,
    val voiceId: String,   // androidLocale for LOCAL_ANDROID, volcSpeaker for VOLCENGINE, "auto" for SHERPA
    val label: String,
)

object TtsVoiceCatalog {

    val byProvider: Map<TtsProvider, List<VoiceOption>> = mapOf(

        TtsProvider.LOCAL_ANDROID to listOf(
            VoiceOption("zh-CN", "普通话（大陆）"),
            VoiceOption("en-US", "English (US)"),
        ),

        // Sherpa-ONNX: single VITS model slot, no language routing.
        TtsProvider.LOCAL_SHERPA_ONNX to listOf(
            VoiceOption("auto", "本地 VITS 模型"),
        ),

        // 精品长文本语音合成 v1 API 音色（voice_type 格式）
        // 完整列表：https://www.volcengine.com/docs/6561/1108211
        TtsProvider.VOLCENGINE to listOf(
            VoiceOption(
                id    = "BV406_V2_streaming",
                label = "梓梓 2.0 · 女 · 超自然（中文）"
            ),
            VoiceOption(
                id    = "BV502_streaming",
                label = "Amanda · 女 · 讲述（英文）"
            ),
        )
    )

    fun defaultVoice(provider: TtsProvider): String =
        byProvider[provider]?.firstOrNull()?.id ?: ""

    /** Flat list for the per-book TTS picker dialog. */
    val allChoices: List<TtsChoice> = listOf(
        TtsChoice(TtsProvider.LOCAL_ANDROID,     "zh-CN",                "系统 TTS · 普通话"),
        TtsChoice(TtsProvider.LOCAL_ANDROID,     "en-US",                "系统 TTS · English"),
        TtsChoice(TtsProvider.LOCAL_SHERPA_ONNX, "auto",                 "本地神经网络 · VITS"),
        TtsChoice(TtsProvider.VOLCENGINE,        "BV406_V2_streaming",   "火山引擎 · 梓梓 2.0"),
        TtsChoice(TtsProvider.VOLCENGINE,        "BV502_streaming",      "火山引擎 · Amanda"),
    )

    fun findChoice(provider: TtsProvider?, voiceId: String?): TtsChoice? =
        if (provider == null) null
        else allChoices.firstOrNull { it.provider == provider && it.voiceId == voiceId }
}
