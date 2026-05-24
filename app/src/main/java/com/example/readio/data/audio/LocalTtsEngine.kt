package com.example.readio.data.audio

import com.example.readio.domain.model.TtsConfig

/**
 * Contract for local (on-device) TTS synthesis.
 *
 * Returns raw audio bytes for a single sentence — no network I/O, no persistent cache.
 * [AndroidTtsEngine] is the sole implementation; additional on-device engines can be added
 * by implementing this interface and updating the Hilt binding in [TtsEngineModule].
 */
interface LocalTtsEngine {

    /**
     * Synthesize [text] and return raw audio bytes (WAV or MP3 depending on the engine).
     * Throws [java.io.IOException] on engine errors.
     * Safe to call from [kotlinx.coroutines.Dispatchers.IO].
     */
    suspend fun synthesize(text: String, config: TtsConfig): ByteArray
}
