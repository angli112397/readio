package com.example.readio.domain.model

data class VocabularyEntry(
    val word: String,
    val language: Language,
    val pinyin: String? = null,
    val partOfSpeech: String? = null,
    val definitions: List<String>
)
