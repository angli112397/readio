package com.example.readio.data.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.readio.domain.model.TtsConfig
import com.example.readio.domain.model.TtsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@Singleton
class AndroidTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : TtsEngine {

    override val provider = TtsProvider.LOCAL_ANDROID

    private val initDeferred = CompletableDeferred<Unit>()

    // TextToSpeech constructor must be called on a thread with a Looper.
    // Hilt creates @Singleton instances from the main thread on first injection.
    private val tts = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) initDeferred.complete(Unit)
        else initDeferred.completeExceptionally(
            IOException("Android TTS init failed (status=$status)")
        )
    }

    // Cache the last configured voice to avoid redundant setLanguage() calls per sentence.
    private var configuredVoice = ""

    override suspend fun synthesize(text: String, config: TtsConfig): ByteArray =
        withContext(Dispatchers.IO) {
            initDeferred.await()

            if (config.voice != configuredVoice) {
                val locale = Locale.forLanguageTag(config.voice)
                val result = tts.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    throw IOException("系统 TTS 不支持语言 '${config.voice}'。请在系统设置中安装对应语言包。")
                }
                configuredVoice = config.voice
            }

            val tempFile = File.createTempFile("tts_", ".wav", context.cacheDir)
            try {
                suspendCoroutine { cont ->
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String) {}
                        override fun onDone(id: String) = cont.resume(Unit)

                        @Suppress("DEPRECATION")
                        override fun onError(id: String) =
                            cont.resumeWithException(IOException("Android TTS synthesis failed"))
                    })
                    if (tts.synthesizeToFile(text, Bundle(), tempFile, tempFile.name) == TextToSpeech.ERROR) {
                        cont.resumeWithException(IOException("Android TTS: failed to queue synthesis"))
                    }
                }
                tempFile.readBytes()
            } finally {
                tempFile.delete()
            }
        }
}
