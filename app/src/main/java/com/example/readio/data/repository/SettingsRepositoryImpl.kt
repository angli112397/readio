package com.example.readio.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

    override fun observeTtsConfig(): Flow<TtsConfig> =
        dataStore.data.map { it.toTtsConfig() }

    override suspend fun getTtsConfig(): TtsConfig =
        dataStore.data.first().toTtsConfig()

    override suspend fun saveTtsConfig(config: TtsConfig) {
        dataStore.edit { prefs ->
            prefs[Keys.PROVIDER]     = config.provider.name
            prefs[Keys.API_KEY]      = config.apiKey
            prefs[Keys.REGION]       = config.region
            prefs[Keys.VOICE]        = config.voice
            prefs[Keys.SPEECH_RATE]  = config.speechRate
        }
    }

    private fun Preferences.toTtsConfig() = TtsConfig(
        provider    = this[Keys.PROVIDER]?.let { runCatching { TtsProvider.valueOf(it) }.getOrNull() }
                      ?: TtsProvider.AZURE,
        apiKey      = this[Keys.API_KEY] ?: "",
        region      = this[Keys.REGION] ?: "eastasia",
        voice       = this[Keys.VOICE] ?: "zh-CN-XiaoxiaoNeural",
        speechRate  = this[Keys.SPEECH_RATE] ?: 1.0f
    )

    private object Keys {
        val PROVIDER    = stringPreferencesKey("tts_provider")
        val API_KEY     = stringPreferencesKey("tts_api_key")
        val REGION      = stringPreferencesKey("tts_region")
        val VOICE       = stringPreferencesKey("tts_voice")
        val SPEECH_RATE = floatPreferencesKey("tts_speech_rate")
    }
}
