package com.example.readio.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.readio.domain.model.ReadingPreferences
import com.example.readio.domain.model.ReadingTheme
import com.example.readio.domain.model.TranslationLanguage
import com.example.readio.domain.model.TranslationProvider
import com.example.readio.domain.model.TtsConfig
import com.example.readio.domain.model.TtsProvider
import com.example.readio.domain.repository.AudioRepository
import com.example.readio.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val audioRepository   : AudioRepository,
) : ViewModel() {

    // Eagerly: DataStore starts collecting as soon as the ViewModel is created,
    // so by the time the Composable first renders, the real stored values are
    // already in ttsConfig.value — no blank-then-update flash on text fields.
    val ttsConfig = settingsRepository.observeTtsConfig()
        .stateIn(viewModelScope, SharingStarted.Eagerly, TtsConfig())

    val readingPrefs = settingsRepository.observeReadingPreferences()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingPreferences())

    private val _clearingAudio = MutableStateFlow(false)
    val clearingAudio = _clearingAudio.asStateFlow()

    // ── TTS provider ──────────────────────────────────────────────────────────

    fun updateTtsProvider(provider: TtsProvider) {
        viewModelScope.launch {
            settingsRepository.saveTtsConfig(ttsConfig.value.copy(provider = provider))
        }
    }

    // ── Volcengine credentials (cloud) ────────────────────────────────────────
    //
    // No explicit Save button needed for individual fields: the UI groups them into
    // one "保存凭据" button that calls updateVolcCredentials with all three values.
    // BackHandler also flushes on navigation away.

    /**
     * Saves all Volcengine cloud credentials in one write.
     * Called by the explicit Save button and on Back navigation.
     */
    fun updateVolcCredentials(appId: String, accessKey: String, speaker: String) {
        viewModelScope.launch {
            settingsRepository.saveTtsConfig(
                ttsConfig.value.copy(
                    volcAppId     = appId,
                    volcAccessKey = accessKey,
                    volcSpeaker   = speaker
                )
            )
        }
    }

    // ── GPT-SoVITS (local server) ─────────────────────────────────────────────

    /**
     * Saves the GPT-SoVITS server URL and voice ID in one write.
     * Called by the explicit Save button and on Back navigation.
     */
    fun updateGptSoVitsConfig(url: String, voice: String) {
        viewModelScope.launch {
            settingsRepository.saveTtsConfig(
                ttsConfig.value.copy(gptSoVitsUrl = url, gptSoVitsVoice = voice)
            )
        }
    }

    // ── System TTS ───────────────────────────────────────────────────────────

    fun updateAndroidLocale(locale: String) {
        viewModelScope.launch {
            settingsRepository.saveTtsConfig(ttsConfig.value.copy(androidLocale = locale))
        }
    }

    // ── Speech rate ───────────────────────────────────────────────────────────

    fun updateSpeechRate(rate: Float) {
        viewModelScope.launch {
            settingsRepository.saveTtsConfig(ttsConfig.value.copy(speechRate = rate))
        }
    }

    // ── Translation ───────────────────────────────────────────────────────────

    fun updateTranslationProvider(provider: TranslationProvider) {
        viewModelScope.launch {
            settingsRepository.saveReadingPreferences(
                readingPrefs.value.copy(translationProvider = provider)
            )
        }
    }

    fun updateTranslationLanguage(lang: TranslationLanguage) {
        viewModelScope.launch {
            settingsRepository.saveReadingPreferences(
                readingPrefs.value.copy(translationLanguage = lang)
            )
        }
    }

    // ── Display ───────────────────────────────────────────────────────────────

    fun updateChunkSize(size: Int) {
        viewModelScope.launch {
            settingsRepository.saveReadingPreferences(readingPrefs.value.copy(chunkSize = size))
        }
    }

    fun updateFontSize(size: Int) {
        viewModelScope.launch {
            settingsRepository.saveReadingPreferences(readingPrefs.value.copy(fontSize = size))
        }
    }

    fun updateLineHeight(height: Float) {
        viewModelScope.launch {
            settingsRepository.saveReadingPreferences(readingPrefs.value.copy(lineHeightMultiplier = height))
        }
    }

    fun updateReadingTheme(theme: ReadingTheme) {
        viewModelScope.launch {
            settingsRepository.saveReadingPreferences(readingPrefs.value.copy(readingTheme = theme))
        }
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    fun clearAllDownloadedAudio() {
        viewModelScope.launch {
            _clearingAudio.value = true
            runCatching { audioRepository.clearAllAudio() }
            _clearingAudio.value = false
        }
    }
}
