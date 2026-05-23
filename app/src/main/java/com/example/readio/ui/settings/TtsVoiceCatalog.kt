package com.example.readio.ui.settings

import com.example.readio.domain.model.TtsProvider

data class VoiceOption(val id: String, val label: String)

object TtsVoiceCatalog {

    val byProvider: Map<TtsProvider, List<VoiceOption>> = mapOf(

        TtsProvider.LOCAL_ANDROID to listOf(
            VoiceOption("zh-CN", "普通话（大陆）"),
            VoiceOption("en-US", "English (US)"),
        ),

        // 豆包语音合成模型 2.0 音色
        // 完整列表：https://www.volcengine.com/docs/6561/1257544
        TtsProvider.VOLCENGINE to listOf(
            VoiceOption(
                id    = "zh_female_vv_uranus_bigtts",
                label = "Vivi 2.0 · 女 · 通用（中文）"
            ),
            VoiceOption(
                id    = "en_male_tim_uranus_bigtts",
                label = "Tim · 男 · 通用（英文）"
            ),
        )
    )

    fun defaultVoice(provider: TtsProvider): String =
        byProvider[provider]?.firstOrNull()?.id ?: ""
}
