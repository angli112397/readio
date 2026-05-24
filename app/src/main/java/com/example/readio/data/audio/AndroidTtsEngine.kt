package com.example.readio.data.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.readio.domain.model.TtsConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class AndroidTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : LocalTtsEngine {

    private val initDeferred = CompletableDeferred<Unit>()

    // TextToSpeech constructor must be called on a thread with a Looper.
    // Hilt creates @Singleton instances from the main thread on first injection.
    private val tts = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) initDeferred.complete(Unit)
        else initDeferred.completeExceptionally(
            IOException("Android TTS init failed (status=$status)")
        )
    }

    // Mutex serializes all synthesis calls: setLanguage() mutates global TTS engine state,
    // so voice configuration and synthesizeToFile() must be atomic across coroutines
    // (e.g. current chapter + prefetch chapter running concurrently on Dispatchers.IO).
    private val synthesizeMutex = Mutex()
    private var configuredVoice = ""

    override suspend fun synthesize(text: String, config: TtsConfig): ByteArray =
        withContext(Dispatchers.IO) {
            initDeferred.await()
            synthesizeMutex.withLock {
                if (config.androidLocale != configuredVoice) {
                    val locale = Locale.forLanguageTag(config.androidLocale)
                    val result = tts.setLanguage(locale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        throw IOException("系统 TTS 不支持语言 '${config.androidLocale}'。请在系统设置中安装对应语言包。")
                    }
                    configuredVoice = config.androidLocale
                }

                val tempFile = File.createTempFile("tts_", ".wav", context.cacheDir)
                try {
                    suspendCancellableCoroutine { cont ->
                        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String) {}
                            override fun onDone(id: String) {
                                if (cont.isActive) cont.resume(Unit)
                            }
                            // Override both overloads: the deprecated one may be skipped on
                            // newer Android versions if only onError(String, Int) is dispatched.
                            @Suppress("DEPRECATION")
                            override fun onError(id: String) = onError(id, -1)
                            override fun onError(id: String, errorCode: Int) {
                                if (cont.isActive) cont.resumeWithException(
                                    IOException("Android TTS synthesis failed (error=$errorCode)")
                                )
                            }
                        })
                        val cleaned = cleanForTts(text)
                if (tts.synthesizeToFile(cleaned, Bundle(), tempFile, tempFile.name) == TextToSpeech.ERROR) {
                            if (cont.isActive) cont.resumeWithException(
                                IOException("Android TTS: failed to queue synthesis")
                            )
                        }
                    }
                    tempFile.readBytes()
                } finally {
                    tempFile.delete()
                }
            }
        }
}
