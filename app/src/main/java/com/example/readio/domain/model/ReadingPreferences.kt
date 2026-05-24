package com.example.readio.domain.model

enum class ReadingTheme(val label: String) {
    DEFAULT("默认"),
    WARM("暖黄"),
    SEPIA("羊皮纸"),
    NIGHT("夜间")
}

enum class TranslationLanguage(val label: String, val code: String) {
    ZH_CN("简体中文", "zh"),
    EN("English", "en"),
    JA("日本語", "ja"),
    KO("한국어", "ko")
}

/** Which translation backend to use — independent of [TtsProvider]. */
enum class TranslationProvider(val displayName: String) {
    ML_KIT("ML Kit（本地）"),
    VOLCENGINE("火山引擎（在线）")
}

data class ReadingPreferences(
    val chunkSize: Int = 150,
    val fontSize: Int = 16,             // sp
    val lineHeightMultiplier: Float = 1.5f,
    val readingTheme: ReadingTheme = ReadingTheme.DEFAULT,
    val translationLanguage: TranslationLanguage = TranslationLanguage.ZH_CN,
    val translationProvider: TranslationProvider = TranslationProvider.ML_KIT
)
