package com.example.readio.domain.translation

/**
 * Contract for a translation backend.
 *
 * Implementations live in the data layer. New backends require:
 *   1. Implement this interface.
 *   2. Inject it into [SmartTranslationEngine] (or add an [IntoSet] binding for multi-engine).
 *
 * Language codes follow ISO 639-1 (e.g. "zh", "en", "ja", "ko").
 */
interface TranslationEngine {

    /**
     * Translate [text] into [targetLang].
     * [sourceLang] = null means auto-detect.
     *
     * @return the translated string, or null if the language pair is unsupported
     *         or if this engine cannot process the request.
     * @throws Exception on unrecoverable errors (e.g. malformed API response).
     *         Network / connectivity errors should be caught internally and return null
     *         so that a fallback engine can take over.
     */
    suspend fun translate(text: String, targetLang: String, sourceLang: String? = null): String?
}
