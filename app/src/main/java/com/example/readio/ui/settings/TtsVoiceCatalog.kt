package com.example.readio.ui.settings

import com.example.readio.domain.model.TtsProvider

data class VoiceOption(val id: String, val label: String)

object TtsVoiceCatalog {

    val byProvider: Map<TtsProvider, List<VoiceOption>> = mapOf(

        TtsProvider.LOCAL_ANDROID to listOf(
            VoiceOption("zh-CN", "普通话（大陆）"),
            VoiceOption("en-US", "English (US)"),
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
}
