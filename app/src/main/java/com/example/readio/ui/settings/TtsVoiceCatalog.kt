package com.example.readio.ui.settings

import com.example.readio.domain.model.TtsProvider

data class VoiceOption(val id: String, val label: String)

/**
 * A flattened (provider, voice) pair used in the per-book TTS picker.
 *
 * [voiceId] = "" for VOLCENGINE and FISH_SPEECH means "use the global voice / server from TtsConfig".
 * applyBookOverride treats empty voiceId as "don't override the global voice".
 */
data class TtsChoice(
    val provider: TtsProvider,
    val voiceId: String,
    val label: String,
)

object TtsVoiceCatalog {

    /** Per-provider voice options shown in the settings 火山引擎 section. */
    val byProvider: Map<TtsProvider, List<VoiceOption>> = mapOf(

        TtsProvider.LOCAL_ANDROID to listOf(
            VoiceOption("zh-CN", "普通话（大陆）"),
            VoiceOption("en-US", "English (US)"),
        ),

        // 精品长文本语音合成 v1 API 音色
        TtsProvider.VOLCENGINE to listOf(
            VoiceOption("BV406_V2_streaming", "梓梓 2.0 · 女 · 超自然（中文）"),
            VoiceOption("BV502_streaming",    "Amanda · 女 · 讲述（英文）"),
        )
    )

    fun defaultVoice(provider: TtsProvider): String =
        byProvider[provider]?.firstOrNull()?.id ?: ""

    /**
     * Flat list for the per-book TTS picker.
     *
     * Per-book selection is about choosing an engine, not a specific voice.
     * The VOLCENGINE and FISH_SPEECH entries use voiceId="" so applyBookOverride
     * falls back to the global speaker / server URL — determined by global settings.
     */
    val allChoices: List<TtsChoice> = listOf(
        TtsChoice(TtsProvider.LOCAL_ANDROID, "zh-CN", "系统 TTS · 普通话"),
        TtsChoice(TtsProvider.LOCAL_ANDROID, "en-US", "系统 TTS · English"),
        TtsChoice(TtsProvider.VOLCENGINE,    "",      "火山引擎（云端）"),
        TtsChoice(TtsProvider.FISH_SPEECH,   "",      "Fish Speech（本地推理）"),
    )

    /**
     * Find the choice matching [provider] + [voiceId].
     * Any non-null VOLCENGINE or FISH_SPEECH provider maps to their single entry
     * regardless of voiceId, since the picker no longer differentiates voices.
     */
    fun findChoice(provider: TtsProvider?, voiceId: String?): TtsChoice? {
        if (provider == null) return null
        return when (provider) {
            TtsProvider.VOLCENGINE  -> allChoices.first { it.provider == TtsProvider.VOLCENGINE }
            TtsProvider.FISH_SPEECH -> allChoices.first { it.provider == TtsProvider.FISH_SPEECH }
            else -> allChoices.firstOrNull { it.provider == provider && it.voiceId == voiceId }
        }
    }
}
