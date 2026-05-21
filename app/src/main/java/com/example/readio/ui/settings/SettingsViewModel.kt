package com.example.readio.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    val config = settingsRepository.observeTtsConfig()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TtsConfig())

    private val _clearingAudio = MutableStateFlow(false)
    val clearingAudio = _clearingAudio.asStateFlow()

    fun save(provider: TtsProvider, apiKey: String, region: String, voice: String, speechRate: Float) {
        viewModelScope.launch {
            settingsRepository.saveTtsConfig(TtsConfig(provider, apiKey, region, voice, speechRate))
        }
    }

    fun clearAllDownloadedAudio() {
        viewModelScope.launch {
            _clearingAudio.value = true
            runCatching { audioRepository.clearAllAudio() }
            _clearingAudio.value = false
        }
    }
}
