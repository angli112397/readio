package com.example.readio.ui.settings

import com.example.readio.domain.model.TtsProvider

data class VoiceOption(val id: String, val label: String)

object TtsVoiceCatalog {

    val byProvider: Map<TtsProvider, List<VoiceOption>> = mapOf(

        TtsProvider.AZURE to listOf(
            // Mainland — female
            VoiceOption("zh-CN-XiaoxiaoNeural",   "普通话 晓晓 女 · 温柔自然（推荐）"),
            VoiceOption("zh-CN-XiaoyiNeural",     "普通话 晓伊 女 · 活泼"),
            VoiceOption("zh-CN-XiaohanNeural",    "普通话 晓涵 女 · 沉稳"),
            VoiceOption("zh-CN-XiaomoNeural",     "普通话 晓墨 女 · 知性"),
            VoiceOption("zh-CN-XiaoxuanNeural",   "普通话 晓萱 女 · 温和"),
            VoiceOption("zh-CN-XiaoruiNeural",    "普通话 晓睿 女 · 成熟"),
            VoiceOption("zh-CN-XiaoshuangNeural", "普通话 晓双 女 · 儿童"),
            // Mainland — male
            VoiceOption("zh-CN-YunyangNeural",    "普通话 云扬 男 · 播音"),
            VoiceOption("zh-CN-YunxiNeural",      "普通话 云希 男 · 轻松"),
            VoiceOption("zh-CN-YunfengNeural",    "普通话 云枫 男 · 低沉"),
            VoiceOption("zh-CN-YunhaoNeural",     "普通话 云皓 男 · 阳光"),
            VoiceOption("zh-CN-YunjianNeural",    "普通话 云健 男 · 运动"),
            // Taiwan
            VoiceOption("zh-TW-HsiaoChenNeural",  "台灣普通話 曉臻 女"),
            VoiceOption("zh-TW-HsiaoYuNeural",    "台灣普通話 曉雨 女"),
            VoiceOption("zh-TW-YunJheNeural",     "台灣普通話 雲哲 男"),
        )

        ,

        TtsProvider.LOCAL_ANDROID to listOf(
            VoiceOption("zh-CN", "普通话（大陆）"),
            VoiceOption("zh-TW", "普通話（台灣）"),
            VoiceOption("zh-HK", "粵語（香港）"),
            VoiceOption("en-US", "English (US)"),
            VoiceOption("en-GB", "English (UK)"),
            VoiceOption("ja-JP", "日本語"),
            VoiceOption("ko-KR", "한국어"),
        )
    )

    fun defaultVoice(provider: TtsProvider): String =
        byProvider[provider]?.firstOrNull()?.id ?: ""
}
