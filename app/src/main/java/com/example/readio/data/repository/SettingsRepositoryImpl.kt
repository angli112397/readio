package com.example.readio.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.readio.domain.model.ReadingPreferences
import com.example.readio.domain.model.ReadingTheme
import com.example.readio.domain.model.TranslationLanguage
import com.example.readio.domain.model.TtsConfig
import com.example.readio.domain.model.TtsProvider
import com.example.readio.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : SettingsRepository {

    // ── TTS configuration ───────────────────────────────────────────────────

    override fun observeTtsConfig(): Flow<TtsConfig> =
        dataStore.data.map { it.toTtsConfig() }

    override suspend fun getTtsConfig(): TtsConfig =
        dataStore.data.first().toTtsConfig()

    override suspend fun saveTtsConfig(config: TtsConfig) {
        dataStore.edit { prefs ->
            prefs[Keys.PROVIDER]    = config.provider.name
            prefs[Keys.API_KEY]     = config.apiKey
            prefs[Keys.REGION]      = config.region
            prefs[Keys.VOICE]       = config.voice
            prefs[Keys.SPEECH_RATE] = config.speechRate
        }
    }

    // ── Reading preferences ─────────────────────────────────────────────────

    override fun observeReadingPreferences(): Flow<ReadingPreferences> =
        dataStore.data.map { it.toReadingPreferences() }

    override suspend fun getReadingPreferences(): ReadingPreferences =
        dataStore.data.first().toReadingPreferences()

    override suspend fun saveReadingPreferences(prefs: ReadingPreferences) {
        dataStore.edit { data ->
            data[Keys.CHUNK_SIZE]            = prefs.chunkSize
            data[Keys.FONT_SIZE]             = prefs.fontSize
            data[Keys.LINE_HEIGHT]           = prefs.lineHeightMultiplier
            data[Keys.READING_THEME]         = prefs.readingTheme.name
            data[Keys.TRANSLATION_LANGUAGE]  = prefs.translationLanguage.name
        }
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private fun Preferences.toTtsConfig() = TtsConfig(
        provider   = this[Keys.PROVIDER]?.let { runCatching { TtsProvider.valueOf(it) }.getOrNull() }
                     ?: TtsProvider.AZURE,
        apiKey     = this[Keys.API_KEY] ?: "",
        region     = this[Keys.REGION] ?: "eastasia",
        voice      = this[Keys.VOICE] ?: "zh-CN-XiaoxiaoNeural",
        speechRate = this[Keys.SPEECH_RATE] ?: 1.0f
    )

    private fun Preferences.toReadingPreferences() = ReadingPreferences(
        chunkSize            = this[Keys.CHUNK_SIZE] ?: 150,
        fontSize             = this[Keys.FONT_SIZE] ?: 16,
        lineHeightMultiplier = this[Keys.LINE_HEIGHT] ?: 1.5f,
        readingTheme         = this[Keys.READING_THEME]
                               ?.let { runCatching { ReadingTheme.valueOf(it) }.getOrNull() }
                               ?: ReadingTheme.DEFAULT,
        translationLanguage  = this[Keys.TRANSLATION_LANGUAGE]
                               ?.let { runCatching { TranslationLanguage.valueOf(it) }.getOrNull() }
                               ?: TranslationLanguage.ZH_CN
    )

    private object Keys {
        val PROVIDER       = stringPreferencesKey("tts_provider")
        val API_KEY        = stringPreferencesKey("tts_api_key")
        val REGION         = stringPreferencesKey("tts_region")
        val VOICE          = stringPreferencesKey("tts_voice")
        val SPEECH_RATE    = floatPreferencesKey("tts_speech_rate")
        val CHUNK_SIZE     = intPreferencesKey("tts_chunk_size")
        val FONT_SIZE      = intPreferencesKey("reading_font_size")
        val LINE_HEIGHT    = floatPreferencesKey("reading_line_height")
        val READING_THEME          = stringPreferencesKey("reading_theme")
        val TRANSLATION_LANGUAGE   = stringPreferencesKey("translation_language")
    }
}
