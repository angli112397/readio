package com.example.readio.domain.model

enum class Language {
    ZH, EN, JA, KO, YUE, UNKNOWN;

    companion object {
        fun fromTag(tag: String): Language = when {
            tag.startsWith("yue") || tag == "zh-yue" -> YUE
            tag.startsWith("zh")                     -> ZH
            tag.startsWith("en")                     -> EN
            tag.startsWith("ja")                     -> JA
            tag.startsWith("ko")                     -> KO
            else                                     -> UNKNOWN
        }
    }
}

/**
 * Maps an EPUB book language to the `text_language` code accepted by the
 * readio-tts server.  Falls back to "zh" for UNKNOWN so jobs always have
 * a valid language tag even when EPUB metadata is missing or unrecognised.
 */
fun Language.toGptTextLang(): String = when (this) {
    Language.ZH      -> "zh"
    Language.EN      -> "en"
    Language.JA      -> "ja"
    Language.KO      -> "ko"
    Language.YUE     -> "yue"
    Language.UNKNOWN -> "zh"
}
