package com.example.readio.data.repository

import com.example.readio.domain.model.Language
import com.example.readio.domain.model.VocabularyEntry
import com.example.readio.domain.repository.VocabularyRepository
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

@Singleton
class VocabularyRepositoryImpl @Inject constructor() : VocabularyRepository {

    private val translators = mutableMapOf<String, Translator>()

    override suspend fun lookup(clause: String, targetLanguageCode: String): VocabularyEntry? {
        val srcCode = detectSourceLanguage(clause, targetLanguageCode) ?: return null
        val translator = getOrCreate(srcCode, targetLanguageCode)
        return try {
            // 30 s covers first-time model download (~15 MB); subsequent calls are near-instant
            withTimeout(30_000L) {
                translator.downloadModelIfNeeded().awaitVoid()
                val translation = translator.translate(clause).awaitTask()
                val language = when (srcCode) {
                    TranslateLanguage.ENGLISH  -> Language.EN
                    TranslateLanguage.JAPANESE -> Language.JA
                    TranslateLanguage.KOREAN   -> Language.KO
                    else                       -> Language.ZH
                }
                VocabularyEntry(word = clause, language = language, definitions = listOf(translation))
            }
        } catch (e: TimeoutCancellationException) {
            // Convert to plain Exception so callers treat it as an error, not job cancellation
            throw Exception("翻译超时，请检查网络连接")
        }
    }

    private fun detectSourceLanguage(text: String, targetCode: String): String? {
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
        return if (src == targetCode) null else src
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

// Task<Void> success delivers null — resume with Unit to avoid Kotlin null-safety NPE
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
