package com.example.readio.data.audio

import com.example.readio.domain.model.TtsConfig
import com.example.readio.domain.model.TtsProvider

interface TtsEngine {
    val provider: TtsProvider

    /**
     * Synthesize [text] and return raw MP3 bytes.
     * Throws [java.io.IOException] on network/API errors.
     * Implementations must be callable from [kotlinx.coroutines.Dispatchers.IO].
     */
    suspend fun synthesize(text: String, config: TtsConfig): ByteArray
}
