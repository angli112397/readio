package com.example.readio.data.translation

import android.util.Log
import com.example.readio.domain.model.TranslationProvider
import com.example.readio.domain.repository.SettingsRepository
import com.example.readio.domain.translation.TranslationEngine
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SmartTranslation"

/**
 * Routing engine that dispatches to the backend selected in [ReadingPreferences.translationProvider].
 *
 * Decoupled from TTS settings: the user can independently choose e.g.
 *   - LOCAL_ANDROID TTS + VOLCENGINE translation
 *   - VOLCENGINE TTS  + ML_KIT translation
 *   - … any combination
 *
 * Volcengine credentials (AppId + AccessKey) are shared with the TTS configuration —
 * the same Volcengine account covers both services with no extra setup.
 *
 * If Volcengine is selected but the API call fails (network error, transient outage),
 * the engine silently falls back to ML Kit so the user still gets a translation.
 *
 * Adding a new backend:
 *   1. Implement [TranslationEngine].
 *   2. Add a [TranslationProvider] entry.
 *   3. Inject the new engine here and add it to the when() branch below.
 */
@Singleton
class SmartTranslationEngine @Inject constructor(
    private val volcengine: VolcengineTranslationEngine,
    private val mlKit: MlKitTranslationEngine,
    private val settingsRepository: SettingsRepository
) : TranslationEngine {

    override suspend fun translate(text: String, targetLang: String, sourceLang: String?): String? {
        val prefs = settingsRepository.getReadingPreferences()

        return when (prefs.translationProvider) {
            TranslationProvider.VOLCENGINE -> {
                val result = runCatching {
                    volcengine.translate(text, targetLang, sourceLang)
                }.onFailure { e ->
                    Log.w(TAG, "Volcengine translation failed, falling back to ML Kit: ${e.message}")
                }.getOrNull()
                // null means API returned non-success or credentials missing — fall through
                result ?: mlKit.translate(text, targetLang, sourceLang)
            }
            TranslationProvider.ML_KIT -> mlKit.translate(text, targetLang, sourceLang)
        }
    }
}
