package com.example.readio.data.repository

import com.example.readio.data.translation.MlKitTranslationEngine
import com.example.readio.domain.model.Language
import com.example.readio.domain.model.VocabularyEntry
import com.example.readio.domain.repository.VocabularyRepository
import com.example.readio.domain.translation.TranslationEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VocabularyRepositoryImpl @Inject constructor(
    private val translationEngine: TranslationEngine,
    private val mlKitEngine: MlKitTranslationEngine   // for source-language detection only
) : VocabularyRepository {

    override suspend fun lookup(clause: String, targetLanguageCode: String): VocabularyEntry? {
        // Detect source language via script heuristic (used to populate VocabularyEntry.language).
        // This is intentionally kept cheap and local — no network call for language ID.
        val sourceLang = mlKitEngine.detectSourceLanguage(clause, targetLanguageCode)

        val translation = translationEngine.translate(
            text       = clause,
            targetLang = targetLanguageCode,
            sourceLang = sourceLang
        ) ?: return null

        val language = sourceLang?.let { Language.fromTag(it) } ?: Language.UNKNOWN
        return VocabularyEntry(
            word        = clause,
            language    = language,
            definitions = listOf(translation)
        )
    }
}
