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
import com.example.readio.domain.model.TranslationProvider
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
            prefs[Keys.PROVIDER]            = config.provider.name
            prefs[Keys.ANDROID_LOCALE]      = config.androidLocale
            prefs[Keys.VOLC_APP_ID]         = config.volcAppId
            prefs[Keys.VOLC_ACCESS_KEY]     = config.volcAccessKey
            prefs[Keys.VOLC_SPEAKER]        = config.volcSpeaker
            prefs[Keys.GPT_SO_VITS_URL]     = config.gptSoVitsUrl
            prefs[Keys.GPT_SO_VITS_VOICE]   = config.gptSoVitsVoice
            prefs[Keys.SPEECH_RATE]         = config.speechRate
        }
    }

    // ── Reading preferences ─────────────────────────────────────────────────

    override fun observeReadingPreferences(): Flow<ReadingPreferences> =
        dataStore.data.map { it.toReadingPreferences() }

    override suspend fun getReadingPreferences(): ReadingPreferences =
        dataStore.data.first().toReadingPreferences()

    override suspend fun saveReadingPreferences(prefs: ReadingPreferences) {
        dataStore.edit { data ->
            data[Keys.CHUNK_SIZE]             = prefs.chunkSize
            data[Keys.FONT_SIZE]              = prefs.fontSize
            data[Keys.LINE_HEIGHT]            = prefs.lineHeightMultiplier
            data[Keys.READING_THEME]          = prefs.readingTheme.name
            data[Keys.TRANSLATION_LANGUAGE]   = prefs.translationLanguage.name
            data[Keys.TRANSLATION_PROVIDER]   = prefs.translationProvider.name
        }
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private fun Preferences.toTtsConfig() = TtsConfig(
        provider       = this[Keys.PROVIDER]
                         ?.let { runCatching { TtsProvider.valueOf(it) }.getOrNull() }
                         ?: TtsProvider.LOCAL_ANDROID,
        androidLocale  = this[Keys.ANDROID_LOCALE]    ?: "zh-CN",
        volcAppId      = this[Keys.VOLC_APP_ID]        ?: "",
        volcAccessKey  = this[Keys.VOLC_ACCESS_KEY]    ?: "",
        volcSpeaker    = this[Keys.VOLC_SPEAKER]       ?: "",
        // Migrate Fish Speech URL (tts_fish_speech_url) and older VOLC_SERVER_URL on first read.
        gptSoVitsUrl   = this[Keys.GPT_SO_VITS_URL]
                         ?: this[Keys.FISH_SPEECH_URL]   // legacy: Fish Speech server URL
                         ?: this[Keys.VOLC_SERVER_URL]   // legacy: even older key
                         ?: "",
        gptSoVitsVoice = this[Keys.GPT_SO_VITS_VOICE] ?: "",
        speechRate     = this[Keys.SPEECH_RATE]        ?: 1.0f
    )

    private fun Preferences.toReadingPreferences() = ReadingPreferences(
        chunkSize            = this[Keys.CHUNK_SIZE] ?: 150,
        fontSize             = this[Keys.FONT_SIZE]  ?: 16,
        lineHeightMultiplier = this[Keys.LINE_HEIGHT] ?: 1.5f,
        readingTheme         = this[Keys.READING_THEME]
                               ?.let { runCatching { ReadingTheme.valueOf(it) }.getOrNull() }
                               ?: ReadingTheme.DEFAULT,
        translationLanguage  = this[Keys.TRANSLATION_LANGUAGE]
                               ?.let { runCatching { TranslationLanguage.valueOf(it) }.getOrNull() }
                               ?: TranslationLanguage.ZH_CN,
        translationProvider  = this[Keys.TRANSLATION_PROVIDER]
                               ?.let { runCatching { TranslationProvider.valueOf(it) }.getOrNull() }
                               ?: TranslationProvider.ML_KIT
    )

    private object Keys {
        val PROVIDER             = stringPreferencesKey("tts_provider")
        val ANDROID_LOCALE       = stringPreferencesKey("tts_android_locale")
        val VOLC_APP_ID          = stringPreferencesKey("tts_volc_app_id")
        val VOLC_ACCESS_KEY      = stringPreferencesKey("tts_volc_access_key")
        val VOLC_SPEAKER         = stringPreferencesKey("tts_volc_speaker")
        val VOLC_SERVER_URL      = stringPreferencesKey("tts_volc_server_url")   // legacy (v0.5)
        val FISH_SPEECH_URL      = stringPreferencesKey("tts_fish_speech_url")   // legacy (v0.7)
        val GPT_SO_VITS_URL      = stringPreferencesKey("tts_gpt_so_vits_url")
        val GPT_SO_VITS_VOICE    = stringPreferencesKey("tts_gpt_so_vits_voice")
        val SPEECH_RATE          = floatPreferencesKey("tts_speech_rate")
        val CHUNK_SIZE           = intPreferencesKey("tts_chunk_size")
        val FONT_SIZE            = intPreferencesKey("reading_font_size")
        val LINE_HEIGHT          = floatPreferencesKey("reading_line_height")
        val READING_THEME          = stringPreferencesKey("reading_theme")
        val TRANSLATION_LANGUAGE   = stringPreferencesKey("translation_language")
        val TRANSLATION_PROVIDER   = stringPreferencesKey("translation_provider")
    }
}
