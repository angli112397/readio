package com.example.readio.domain.repository

import com.example.readio.domain.model.VocabularyEntry

interface VocabularyRepository {
    suspend fun lookup(clause: String, targetLanguageCode: String): VocabularyEntry?
}
