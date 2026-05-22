package com.example.readio.domain.model

enum class Language {
    ZH, EN, JA, KO, UNKNOWN;

    companion object {
        fun fromTag(tag: String): Language = when {
            tag.startsWith("zh") -> ZH
            tag.startsWith("en") -> EN
            tag.startsWith("ja") -> JA
            tag.startsWith("ko") -> KO
            else -> UNKNOWN
        }
    }
}
