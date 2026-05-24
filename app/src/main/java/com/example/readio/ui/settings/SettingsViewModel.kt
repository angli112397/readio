package com.example.readio.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.readio.data.audio.SherpaModelManager
import com.example.readio.data.audio.SherpaOnnxTtsEngine
import com.example.readio.domain.model.ReadingPreferences
import com.example.readio.domain.model.ReadingTheme
import com.example.readio.domain.model.TranslationLanguage
import com.example.readio.domain.model.TranslationProvider
import com.example.readio.domain.model.TtsConfig
import com.example.readio.domain.repository.AudioRepository
import com.example.readio.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Import state ──────────────────────────────────────────────────────────────

/** Import state for the Sherpa-ONNX VITS model. */
sealed interface ModelImportState {
    /** No model has been imported. */
    data object Empty : ModelImportState

    /**
     * An archive is currently being extracted.
     * [progress] is 0.0–1.0, or -1f when total size is unknown.
     */
    data class Importing(val progress: Float) : ModelImportState

    /** Model is fully extracted and ready for synthesis. */
    data object Ready : ModelImportState

    /** Last import attempt failed; [message] is user-readable. */
    data class Failed(val message: String) : ModelImportState
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val audioRepository   : AudioRepository,
    private val sherpaModels      : SherpaModelManager,
    private val sherpaEngine      : SherpaOnnxTtsEngine,
) : ViewModel() {

    val ttsConfig = settingsRepository.observeTtsConfig()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TtsConfig())

    val readingPrefs = settingsRepository.observeReadingPreferences()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingPreferences())

    private val _clearingAudio = MutableStateFlow(false)
    val clearingAudio = _clearingAudio.asStateFlow()

    private val _sherpaState = MutableStateFlow(initialSherpaState())
    val sherpaState: kotlinx.coroutines.flow.StateFlow<ModelImportState> = _sherpaState.asStateFlow()

    private fun initialSherpaState(): ModelImportState =
        if (sherpaModels.isReady()) ModelImportState.Ready else ModelImportState.Empty

    init {
        // React to JNI-triggered invalidations (corrupt model detected at synthesis time).
        viewModelScope.launch {
            sherpaModels.invalidations.collect {
                _sherpaState.value = ModelImportState.Empty
            }
        }
    }

    // ── Sherpa model import / delete ──────────────────────────────────────────

    fun importVitsModel(uri: Uri) {
        if (_sherpaState.value is ModelImportState.Importing) return
        viewModelScope.launch {
            _sherpaState.value = ModelImportState.Importing(-1f)
            runCatching {
                sherpaModels.importFromUri(uri) { read, total ->
                    val progress = if (total > 0) read.toFloat() / total else -1f
                    _sherpaState.value = ModelImportState.Importing(progress)
                }
                sherpaEngine.releaseModel()
                _sherpaState.value = ModelImportState.Ready
            }.onFailure { e ->
                _sherpaState.value = ModelImportState.Failed(e.message ?: "导入失败")
            }
        }
    }

    fun deleteVitsModel() {
        viewModelScope.launch {
            sherpaModels.deleteVits()
            sherpaEngine.releaseModel()
            _sherpaState.value = ModelImportState.Empty
        }
    }

    // ── Instant-save settings — each write goes directly to DataStore ─────────
    //
    // No explicit Save button: each setting persists the moment the user changes it.
    // Text fields (volcAppId, volcAccessKey) are flushed by the UI on focus-lost
    // and again when the user presses Back, so no changes are ever lost.

    /** Saves Volcengine credentials (used by both TTS and translation engines). */
    fun updateVolcCredentials(appId: String, accessKey: String) {
        viewModelScope.launch {
            settingsRepository.saveTtsConfig(
                ttsConfig.value.copy(volcAppId = appId, volcAccessKey = accessKey)
            )
        }
    }

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
