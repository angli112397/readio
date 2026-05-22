package com.example.readio.domain.repository

import com.example.readio.domain.model.ReadingPreferences
import com.example.readio.domain.model.TtsConfig
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {

    // ── TTS configuration ───────────────────────────────────────────────────

    fun observeTtsConfig(): Flow<TtsConfig>
    suspend fun getTtsConfig(): TtsConfig
    suspend fun saveTtsConfig(config: TtsConfig)

    // ── Reading preferences (display / chunking) ────────────────────────────

    fun observeReadingPreferences(): Flow<ReadingPreferences>
    suspend fun getReadingPreferences(): ReadingPreferences
    suspend fun saveReadingPreferences(prefs: ReadingPreferences)
}
