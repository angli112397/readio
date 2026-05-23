package com.example.readio.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.readio.domain.model.ReadingPreferences
import com.example.readio.domain.model.ReadingTheme
import com.example.readio.domain.model.TranslationLanguage
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
    private val audioRepository: AudioRepository
) : ViewModel() {

    val ttsConfig = settingsRepository.observeTtsConfig()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TtsConfig())

    val readingPrefs = settingsRepository.observeReadingPreferences()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingPreferences())

    private val _clearingAudio = MutableStateFlow(false)
    val clearingAudio = _clearingAudio.asStateFlow()

    suspend fun save(
        provider: TtsProvider,
        androidLocale: String,
        volcAppId: String,
        volcAccessKey: String,
        volcSpeaker: String,
        speechRate: Float,
        chunkSize: Int,
        fontSize: Int,
        lineHeightMultiplier: Float,
        readingTheme: ReadingTheme,
        translationLanguage: TranslationLanguage
    ) {
        settingsRepository.saveTtsConfig(
            // volcResourceId is hardcoded — only seed-tts-2.0 is supported.
            TtsConfig(provider, androidLocale, volcAppId, volcAccessKey, "seed-tts-2.0", volcSpeaker, speechRate)
        )
        settingsRepository.saveReadingPreferences(
            ReadingPreferences(chunkSize, fontSize, lineHeightMultiplier, readingTheme, translationLanguage)
        )
    }

    fun clearAllDownloadedAudio() {
        viewModelScope.launch {
            _clearingAudio.value = true
            runCatching { audioRepository.clearAllAudio() }
            _clearingAudio.value = false
        }
    }
}
