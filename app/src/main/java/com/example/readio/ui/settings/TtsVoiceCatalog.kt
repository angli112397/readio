package com.example.readio.ui.settings

import com.example.readio.domain.model.TtsProvider

data class VoiceOption(val id: String, val label: String)

data class RegionOption(val id: String, val label: String)

val azureRegions = listOf(
    RegionOption("eastasia",        "东亚 East Asia"),
    RegionOption("southeastasia",   "东南亚 Southeast Asia"),
    RegionOption("eastus",          "美国东部 East US"),
    RegionOption("eastus2",         "美国东部 2 East US 2"),
    RegionOption("westus",          "美国西部 West US"),
    RegionOption("westus2",         "美国西部 2 West US 2"),
    RegionOption("centralus",       "美国中部 Central US"),
    RegionOption("northeurope",     "北欧 North Europe"),
    RegionOption("westeurope",      "西欧 West Europe"),
    RegionOption("uksouth",         "英国南部 UK South"),
    RegionOption("australiaeast",   "澳大利亚东部 Australia East"),
    RegionOption("japaneast",       "日本东部 Japan East"),
    RegionOption("japanwest",       "日本西部 Japan West"),
    RegionOption("koreacentral",    "韩国中部 Korea Central"),
    RegionOption("canadacentral",   "加拿大中部 Canada Central"),
    RegionOption("brazilsouth",     "巴西南部 Brazil South"),
    RegionOption("centralindia",    "印度中部 Central India"),
)

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
        ),

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
