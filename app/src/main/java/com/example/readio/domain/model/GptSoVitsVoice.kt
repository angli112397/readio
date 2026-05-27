package com.example.readio.domain.model

/**
 * A reference voice installed on the readio-tts server.
 *
 * Returned by GET /v1/voices and GET /v1/voices/{voice_id}.
 * Created via POST /v1/voices (multipart upload).
 * Deleted via DELETE /v1/voices/{voice_id}.
 *
 * [referenceLanguage] is the language of the uploaded reference audio ("zh", "en", "ja", "ko", "yue").
 * It does NOT restrict synthesis language — cross-lingual narration is supported.
 */
data class GptSoVitsVoice(
    val id: String,
    val displayName: String,
    val referenceLanguage: String,
)

/** Language codes accepted by the readio-tts server for both voice upload and job submission. */
object GptSoVitsLanguage {
    val all: List<Pair<String, String>> = listOf(
        "zh"  to "中文（普通话）",
        "en"  to "English",
        "ja"  to "日本語",
        "ko"  to "한국어",
        "yue" to "粤语",
    )

    fun labelFor(code: String): String = all.firstOrNull { it.first == code }?.second ?: code
}
