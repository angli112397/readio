package com.example.readio.data.translation

import android.util.Log
import com.example.readio.domain.repository.SettingsRepository
import com.example.readio.domain.translation.TranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VolcTranslation"

// 机器翻译大模型 v3 API
// 文档: https://www.volcengine.com/docs/6561/XXX
private const val TRANSLATE_URL = "https://openspeech.bytedance.com/api/v3/machine_translation/matx_translate"
private const val RESOURCE_ID   = "volc.speech.mt"
private const val SUCCESS_CODE  = 20000000

/**
 * Cloud translation via Volcengine 机器翻译大模型 v3 API.
 *
 * Uses the same [AppId] + [AccessKey] credentials as the TTS service — no extra configuration.
 * Reuses ISO 639-1 language codes (zh, en, ja, ko, …) matching [TranslationLanguage].
 *
 * If credentials are missing or empty this engine returns null immediately,
 * allowing [SmartTranslationEngine] to fall back to ML Kit.
 *
 * API limits: max 16 texts/request, max 1024 tokens/text.
 * A typical reading chunk (~150 CJK chars) is well within limits.
 */
@Singleton
class VolcengineTranslationEngine @Inject constructor(
    private val settingsRepository: SettingsRepository
) : TranslationEngine {

    /**
     * Translate [text] to [targetLang] via the Volcengine matx API.
     * Returns null (without throwing) if credentials are absent or the API is unavailable.
     */
    override suspend fun translate(text: String, targetLang: String, sourceLang: String?): String? =
        withContext(Dispatchers.IO) {
            val config = settingsRepository.getTtsConfig()
            val appId     = config.volcAppId.trim()
            val accessKey = config.volcAccessKey.trim()
            if (appId.isEmpty() || accessKey.isEmpty()) return@withContext null

            val payload = JSONObject().apply {
                put("target_language", targetLang)
                put("text_list", JSONArray().put(text))
                if (sourceLang != null) put("source_language", sourceLang)
            }

            val headers = mapOf(
                "X-Api-App-Key"     to appId,
                "X-Api-Access-Key"  to accessKey,
                "X-Api-Resource-Id" to RESOURCE_ID,
                "X-Api-Request-Id"  to UUID.randomUUID().toString(),
                "Content-Type"      to "application/json"
            )

            val resp = post(TRANSLATE_URL, payload, headers)
            val code = resp.optInt("code", -1)
            if (code != SUCCESS_CODE) {
                Log.w(TAG, "Translation API error code=$code: ${resp.optString("message")}")
                return@withContext null
            }

            resp.optJSONObject("data")
                ?.optJSONArray("translation_list")
                ?.optJSONObject(0)
                ?.optString("translation")
                ?.takeIf { it.isNotEmpty() }
        }

    // ── HTTP helper ───────────────────────────────────────────────────────────

    private fun post(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code     = conn.responseCode
            val bodyText = (if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream)
                .use { it.readBytes() }.toString(Charsets.UTF_8)
            if (code !in 200..299)
                throw IOException("Volcengine translation HTTP $code: $bodyText")
            return JSONObject(bodyText)
        } finally {
            conn.disconnect()
        }
    }
}
