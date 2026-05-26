package com.example.readio.ui.settings

import com.example.readio.domain.model.TtsProvider

data class VoiceOption(val id: String, val label: String)

/**
 * A flattened (provider, voice) pair used in the per-book TTS picker.
 *
 * [voiceId] = "" for VOLCENGINE and GPT_SO_VITS means "use the global voice / server from TtsConfig".
 * applyBookOverride treats empty voiceId as "don't override the global voice".
 */
data class TtsChoice(
    val provider: TtsProvider,
    val voiceId: String,
    val label: String,
)

object TtsVoiceCatalog {

    /** Per-provider voice options shown in the settings voice dropdown sections. */
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
        // GPT_SO_VITS: voice_id is user-entered (references/gpt/ folder names on the server).
        // No static catalog — see settings screen for the free-text Voice ID field.
    )

    fun defaultVoice(provider: TtsProvider): String =
        byProvider[provider]?.firstOrNull()?.id ?: ""

    /**
     * Flat list for the per-book TTS picker.
     *
     * Per-book selection is about choosing an engine, not a specific voice.
     * The VOLCENGINE and GPT_SO_VITS entries use voiceId="" so applyBookOverride
     * falls back to the global speaker / voice / server URL determined by global settings.
     */
    val allChoices: List<TtsChoice> = listOf(
        TtsChoice(TtsProvider.LOCAL_ANDROID, "zh-CN", "系统 TTS · 普通话"),
        TtsChoice(TtsProvider.LOCAL_ANDROID, "en-US", "系统 TTS · English"),
        TtsChoice(TtsProvider.VOLCENGINE,    "",      "火山引擎（云端）"),
        TtsChoice(TtsProvider.GPT_SO_VITS,   "",      "GPT-SoVITS（本地推理）"),
    )

    /**
     * Find the choice matching [provider] + [voiceId].
     * Any non-null VOLCENGINE or GPT_SO_VITS provider maps to their single entry
     * regardless of voiceId, since the per-book picker doesn't differentiate voices.
     */
    fun findChoice(provider: TtsProvider?, voiceId: String?): TtsChoice? {
        if (provider == null) return null
        return when (provider) {
            TtsProvider.VOLCENGINE  -> allChoices.first { it.provider == TtsProvider.VOLCENGINE }
            TtsProvider.GPT_SO_VITS -> allChoices.first { it.provider == TtsProvider.GPT_SO_VITS }
            else -> allChoices.firstOrNull { it.provider == provider && it.voiceId == voiceId }
        }
    }
}
