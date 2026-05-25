package com.example.readio.domain.engine

import com.example.readio.domain.model.TtsConfig
import com.example.readio.domain.model.TtsProvider

/**
 * Contract for on-device real-time TTS synthesis.
 *
 * Synthesizes one sentence at a time and returns raw WAV bytes.
 * No network I/O, no persistent cache — purely CPU-bound.
 *
 * I/O contract:
 *   IN:  text: String + TtsConfig
 *   OUT: ByteArray (WAV)
 *
 * Current implementations:
 *   - [AndroidTtsEngine] → [TtsProvider.LOCAL_ANDROID] (system TTS)
 *
 * Adding a new realtime engine:
 *   1. Implement this interface (in data/audio/).
 *   2. Add `@Binds @Singleton @IntoSet` in [TtsEngineModule].
 */
interface RealtimeTtsEngine {

    val provider: TtsProvider

    /**
     * Synthesize [text] and return raw WAV audio bytes.
     * Throws [java.io.IOException] on engine errors.
     * Safe to call from [kotlinx.coroutines.Dispatchers.Default] or IO.
     */
    suspend fun synthesize(text: String, config: TtsConfig): ByteArray
}
