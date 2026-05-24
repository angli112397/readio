package com.example.readio.data.translation

import com.example.readio.domain.translation.TranslationEngine
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device translation via Google ML Kit.
 *
 * No network required after first use — ML Kit downloads a ~15 MB model per language pair
 * on the first translation call, then works fully offline.
 *
 * Supports ~50 languages. ISO 639-1 codes are mapped to [TranslateLanguage] constants.
 *
 * Used as the fallback engine when Volcengine credentials are not configured.
 */
@Singleton
class MlKitTranslationEngine @Inject constructor() : TranslationEngine {

    /** Cache of initialized [Translator] instances keyed by "src→tgt". */
    private val translators = mutableMapOf<String, Translator>()

    override suspend fun translate(text: String, targetLang: String, sourceLang: String?): String? {
        val src = (sourceLang ?: detectSourceLanguage(text, targetLang)) ?: return null
        val translator = getOrCreate(src, targetLang)
        return withTimeout(30_000L) {
            translator.downloadModelIfNeeded().awaitVoid()
            translator.translate(text).awaitTask()
        }
    }

    /**
     * Heuristic source-language detection via Unicode block inspection.
     * Returns null when the script cannot be determined or equals [targetLang].
     */
    fun detectSourceLanguage(text: String, targetLang: String): String? {
        val hasCJK    = text.any { it.code in 0x4E00..0x9FFF }
        val hasJP     = text.any { it.code in 0x3041..0x30FF }
        val hasKorean = text.any { it.code in 0xAC00..0xD7AF }
        val hasLatin  = text.any { it in 'A'..'Z' || it in 'a'..'z' }

        val src = when {
            hasKorean -> TranslateLanguage.KOREAN
            hasJP     -> TranslateLanguage.JAPANESE
            hasCJK    -> TranslateLanguage.CHINESE
            hasLatin  -> TranslateLanguage.ENGLISH
            else      -> return null
        }
        return if (src == targetLang) null else src
    }

    private fun getOrCreate(src: String, tgt: String): Translator =
        synchronized(translators) {
            translators.getOrPut("$src→$tgt") {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(src)
                        .setTargetLanguage(tgt)
                        .build()
                )
            }
        }
}

// ── Task<Void> helpers ─────────────────────────────────────────────────────────

/** Task<Void> success delivers null — resume with Unit to avoid Kotlin null-safety NPE. */
private suspend fun com.google.android.gms.tasks.Task<Void>.awaitVoid(): Unit =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
        addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    }

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
        addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    }
