package com.example.readio.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.readio.data.audio.GptSoVitsEngine
import com.example.readio.domain.model.GptSoVitsVoice
import com.example.readio.domain.model.ReadingPreferences
import com.example.readio.domain.model.ReadingTheme
import com.example.readio.domain.model.TranslationLanguage
import com.example.readio.domain.model.TranslationProvider
import com.example.readio.domain.model.TtsConfig
import com.example.readio.domain.model.TtsProvider
import com.example.readio.domain.repository.AudioRepository
import com.example.readio.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val audioRepository   : AudioRepository,
    private val gptSoVitsEngine   : GptSoVitsEngine,
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

    // ── GPT-SoVITS voice management state ─────────────────────────────────────

    private val _gptVoices        = MutableStateFlow<List<GptSoVitsVoice>>(emptyList())
    private val _gptVoicesLoading = MutableStateFlow(false)
    private val _gptVoicesError   = MutableStateFlow<String?>(null)
    private val _gptVoiceUploading = MutableStateFlow(false)

    val gptVoices         = _gptVoices.asStateFlow()
    val gptVoicesLoading  = _gptVoicesLoading.asStateFlow()
    val gptVoicesError    = _gptVoicesError.asStateFlow()
    val gptVoiceUploading = _gptVoiceUploading.asStateFlow()

    // ── TTS provider ──────────────────────────────────────────────────────────

    fun updateTtsProvider(provider: TtsProvider) {
        viewModelScope.launch {
            settingsRepository.saveTtsConfig(ttsConfig.value.copy(provider = provider))
        }
    }

    // ── Volcengine credentials ────────────────────────────────────────────────

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

    // ── GPT-SoVITS server config ──────────────────────────────────────────────

    /**
     * Saves all four GPT-SoVITS connection + synthesis settings atomically.
     * Called by the Save button and on Back navigation.
     */
    fun updateGptSoVitsConfig(
        url:      String,
        token:    String,
        textLang: String,
        voice:    String,
    ) {
        viewModelScope.launch {
            settingsRepository.saveTtsConfig(
                ttsConfig.value.copy(
                    gptSoVitsUrl          = url,
                    gptSoVitsApiToken     = token,
                    gptSoVitsTextLanguage = textLang,
                    gptSoVitsVoice        = voice,
                )
            )
        }
    }

    // ── GPT-SoVITS voice management ───────────────────────────────────────────

    /**
     * GET /v1/voices — fetches the list of installed voices from the server.
     * Uses [url] and [token] directly from the live text fields (not yet saved to
     * DataStore), so the user can test connectivity before pressing Save.
     */
    fun fetchGptVoices(url: String, token: String) {
        if (_gptVoicesLoading.value) return
        _gptVoicesLoading.value = true
        _gptVoicesError.value   = null
        viewModelScope.launch {
            try {
                val voices = withContext(Dispatchers.IO) {
                    gptSoVitsEngine.listVoices(url.trimEnd('/'), token)
                }
                _gptVoices.value = voices
            } catch (e: Exception) {
                _gptVoicesError.value = e.message ?: "获取音色列表失败"
            } finally {
                _gptVoicesLoading.value = false
            }
        }
    }

    /**
     * POST /v1/voices — uploads a new reference voice.
     * Reads [audioUri] from the content resolver on IO and sends the WAV bytes as
     * multipart form-data.  Refreshes the voice list on success.
     */
    fun uploadGptVoice(
        displayName:       String,
        referenceLanguage: String,
        transcript:        String,
        audioUri:          Uri,
        url:               String,
        token:             String,
    ) {
        _gptVoiceUploading.value = true
        _gptVoicesError.value    = null
        viewModelScope.launch {
            try {
                val audioBytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(audioUri)?.use { it.readBytes() }
                        ?: throw Exception("无法读取所选音频文件")
                }
                withContext(Dispatchers.IO) {
                    gptSoVitsEngine.uploadVoice(
                        displayName, referenceLanguage, transcript, audioBytes,
                        url.trimEnd('/'), token
                    )
                }
                // Refresh list so the new voice appears in the dropdown.
                fetchGptVoices(url, token)
            } catch (e: Exception) {
                _gptVoicesError.value = e.message ?: "音色上传失败"
            } finally {
                _gptVoiceUploading.value = false
            }
        }
    }

    /**
     * DELETE /v1/voices/{voiceId} — removes a voice from the server.
     * Refreshes the voice list on success and clears the selected voice if it was deleted.
     */
    fun deleteGptVoice(voiceId: String, url: String, token: String, onVoiceDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    gptSoVitsEngine.deleteVoice(voiceId, url.trimEnd('/'), token)
                }
                onVoiceDeleted()
                fetchGptVoices(url, token)
            } catch (e: Exception) {
                _gptVoicesError.value = e.message ?: "删除音色失败"
            }
        }
    }

    fun clearGptVoicesError() { _gptVoicesError.value = null }

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
