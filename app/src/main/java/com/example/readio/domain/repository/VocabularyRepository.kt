package com.example.readio.domain.repository

import com.example.readio.domain.model.Language
import com.example.readio.domain.model.VocabularyEntry

interface VocabularyRepository {
    suspend fun lookup(word: String, language: Language): VocabularyEntry?
}
