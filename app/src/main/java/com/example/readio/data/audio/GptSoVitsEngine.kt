package com.example.readio.data.audio

import android.util.Log
import com.example.readio.data.audio.cache.AudioCache
import com.example.readio.domain.engine.AudioFormat
import com.example.readio.domain.engine.BatchSynthesisEvent
import com.example.readio.domain.engine.BatchTtsEngine
import com.example.readio.domain.engine.SentenceTiming
import com.example.readio.domain.engine.SynthesisManifest
import com.example.readio.domain.model.GptSoVitsVoice
import com.example.readio.domain.model.Sentence
import com.example.readio.domain.model.TtsConfig
import com.example.readio.domain.model.TtsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GptSoVitsEngine"

@Singleton
class GptSoVitsEngine @Inject constructor(
    private val audioCache: AudioCache
) : BatchTtsEngine {

    override val provider: TtsProvider = TtsProvider.GPT_SO_VITS

    override fun synthesize(
        sentences: List<Sentence>,
        config: TtsConfig,
        cacheDir: File
    ): Flow<BatchSynthesisEvent> = flow {
        if (sentences.isEmpty()) {
            audioCache.writeManifest(cacheDir, SynthesisManifest(
                format        = AudioFormat.SINGLE_FILE,
                sentenceCount = 0,
                timings       = emptyList()
            ))
            emit(BatchSynthesisEvent.Complete)
            return@flow
        }

        val baseUrl = config.gptSoVitsUrl.trimEnd('/')
        if (baseUrl.isEmpty()) {
            emit(BatchSynthesisEvent.Failed(IOException("GPT-SoVITS 服务器地址未配置")))
            return@flow
        }
        if (config.gptSoVitsVoice.isBlank()) {
            emit(BatchSynthesisEvent.Failed(IOException("Voice ID 未配置，请在设置中选择音色")))
            return@flow
        }

        val token    = config.gptSoVitsApiToken
        val textLang = config.gptSoVitsTextLanguage.ifBlank { "zh" }

        val validSentences = sentences.filter { cleanForTts(it.text).isNotBlank() }
        if (validSentences.isEmpty()) {
            emit(BatchSynthesisEvent.Failed(IOException("章节文本清理后为空")))
            return@flow
        }

        var jobId: String = loadTaskId(cacheDir) ?: run {
            emit(BatchSynthesisEvent.Progress(0, sentences.size, "提交合成任务…"))
            submit(validSentences, baseUrl, config.gptSoVitsVoice, textLang, token, cacheDir).also {
                saveTaskId(cacheDir, it)
                Log.d(TAG, "Submitted job $it to $baseUrl")
            }
        }

        emit(BatchSynthesisEvent.Progress(0, sentences.size, "查询任务状态…"))
        val completed = try {
            queryOnce(jobId, baseUrl, token)
        } catch (e: IOException) {
            Log.w(TAG, "Job $jobId terminal (${e.message}), re-submitting")
            try { deleteServerJob(jobId, baseUrl, token) } catch (ex: Exception) { }
            clearTaskId(cacheDir)
            emit(BatchSynthesisEvent.Progress(0, sentences.size, "提交合成任务…"))
            jobId = submit(validSentences, baseUrl, config.gptSoVitsVoice, textLang, token, cacheDir).also {
                saveTaskId(cacheDir, it)
                Log.d(TAG, "Re-submitted fresh job $it")
            }
            queryOnce(jobId, baseUrl, token)
        }

        if (completed) {
            emit(BatchSynthesisEvent.Progress(0, sentences.size, "下载音频…"))
            val audioFile = File(cacheDir, "audio.wav")
            downloadAudio(jobId, baseUrl, token, audioFile)
            val serverManifest = downloadManifest(jobId, baseUrl, token)
            val timings = mapTimings(serverManifest, validSentences)
            audioCache.writeManifest(cacheDir, SynthesisManifest(
                format        = AudioFormat.SINGLE_FILE,
                audioFileName = "audio.wav",
                sentenceCount = timings.size,
                timings       = timings
            ))
            try { deleteServerJob(jobId, baseUrl, token) } catch (e: Exception) { }
            clearTaskId(cacheDir)
            emit(BatchSynthesisEvent.Complete)
        } else {
            emit(BatchSynthesisEvent.Submitted(jobId))
        }
    }.flowOn(Dispatchers.IO)

    override fun loadManifest(cacheDir: File): SynthesisManifest? =
        audioCache.readManifest(cacheDir)

    override fun hasChapter(chapterId: String, config: TtsConfig): Boolean =
        audioCache.isReady(chapterId, config.cacheKey)

    override fun pendingTaskId(chapterId: String, config: TtsConfig): String? =
        loadTaskId(audioCache.chapterDir(chapterId, config.cacheKey))

    override fun importTaskId(chapterId: String, taskId: String, config: TtsConfig) {
        saveTaskId(audioCache.chapterDir(chapterId, config.cacheKey), taskId)
    }

    override fun clearChapter(chapterId: String) {
        audioCache.clearChapter(chapterId)
    }

    override fun clearChapter(chapterId: String, config: TtsConfig) {
        val url   = config.gptSoVitsUrl.trimEnd('/')
        val token = config.gptSoVitsApiToken
        if (url.isNotEmpty()) {
            val cacheDir = audioCache.chapterDir(chapterId, config.cacheKey)
            val taskId   = loadTaskId(cacheDir)
            if (taskId != null) {
                try { deleteServerJob(taskId, url, token) } catch (e: Exception) { }
            }
        }
        audioCache.clearChapter(chapterId)
    }

    override fun clearAll() { audioCache.clearAll() }

    fun listVoices(baseUrl: String, token: String): List<GptSoVitsVoice> {
        val conn = openConn("$baseUrl/v1/voices", token)
        try {
            conn.requestMethod = "GET"
            val body = readResponse(conn, "List voices")
            // Server returns a plain JSON array: [{voice_id, display_name, …}, …]
            val arr  = JSONArray(body)
            return List(arr.length()) { i -> parseVoice(arr.getJSONObject(i)) }
                .sortedBy { it.displayName }
        } finally { conn.disconnect() }
    }

    fun uploadVoice(
        displayName: String, referenceLanguage: String, transcript: String,
        audioBytes: ByteArray, baseUrl: String, token: String
    ): GptSoVitsVoice {
        val boundary = "Readio-${System.currentTimeMillis()}"
        val conn = openConn("$baseUrl/v1/voices", token)
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.doOutput = true
            conn.outputStream.buffered().use { out ->
                writeTextPart(out, boundary, "display_name",       displayName)
                writeTextPart(out, boundary, "reference_language", referenceLanguage)
                writeTextPart(out, boundary, "transcript",         transcript)
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"audio\"; filename=\"reference.wav\"\r\n".toByteArray())
                out.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
                out.write(audioBytes)
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            return parseVoice(JSONObject(readResponse(conn, "Upload voice")))
        } finally { conn.disconnect() }
    }

    fun deleteVoice(voiceId: String, baseUrl: String, token: String) {
        val conn = openConn("$baseUrl/v1/voices/$voiceId", token)
        try {
            conn.requestMethod = "DELETE"
            val code = conn.responseCode
            if (code !in 200..299 && code != 404) throw IOException("Delete voice HTTP $code")
        } finally { conn.disconnect() }
    }

    private fun loadTaskId(cacheDir: File): String? {
        val f = File(cacheDir, "task.id")
        return if (f.exists()) f.readText().trim().takeIf { it.isNotEmpty() } else null
    }
    private fun saveTaskId(cacheDir: File, jobId: String) {
        cacheDir.mkdirs(); File(cacheDir, "task.id").writeText(jobId)
    }
    private fun clearTaskId(cacheDir: File) { File(cacheDir, "task.id").delete() }

    private fun submit(
        sentences: List<Sentence>, baseUrl: String, voiceId: String,
        textLanguage: String, token: String, cacheDir: File
    ): String {
        val sentArr = JSONArray().apply {
            sentences.forEachIndexed { idx, s ->
                put(JSONObject().apply {
                    put("id", idx.toString()); put("text", cleanForTts(s.text))
                    put("paragraph_index", s.chunkIndex)
                })
            }
        }
        val payload = JSONObject().apply {
            put("chapter_id", cacheDir.absolutePath); put("voice_id", voiceId)
            put("text_language", textLanguage); put("sentence_gap_ms", 600)
            put("sentences", sentArr)
        }
        val conn = openConn("$baseUrl/v1/jobs", token)
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Idempotency-Key", cacheDir.absolutePath)
            conn.doOutput = true
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            return JSONObject(readResponse(conn, "Submit job")).getString("job_id")
        } finally { conn.disconnect() }
    }

    private fun queryOnce(jobId: String, baseUrl: String, token: String): Boolean {
        val conn = openConn("$baseUrl/v1/jobs/$jobId", token)
        try {
            conn.requestMethod = "GET"
            val resp  = JSONObject(readResponse(conn, "Poll job"))
            val state = resp.getString("state")
            Log.d(TAG, "Poll job=$jobId state=$state")
            return when (state) {
                "succeeded" -> true
                "failed", "cancelled" -> {
                    // error is an object: {"code":"tts_unavailable","message":"…","sentence_id":"…"}
                    val errMsg = resp.optJSONObject("error")?.optString("message", "")
                        ?.ifBlank { null }
                    throw IOException("GPT-SoVITS job $jobId $state" + if (errMsg != null) ": $errMsg" else "")
                }
                else -> false
            }
        } finally { conn.disconnect() }
    }

    private fun downloadAudio(jobId: String, baseUrl: String, token: String, dest: File) {
        val conn = openConn("$baseUrl/v1/jobs/$jobId/audio", token)
        try {
            val code = conn.responseCode
            if (code == 401) throw IOException("音频下载鉴权失败（401），请检查 API Token")
            if (code != 200) throw IOException("Audio download HTTP $code")
            dest.parentFile?.mkdirs()
            conn.inputStream.use { it.copyTo(dest.outputStream()) }
        } finally { conn.disconnect() }
    }

    private fun downloadManifest(jobId: String, baseUrl: String, token: String): List<ServerSentence> {
        val conn = openConn("$baseUrl/v1/jobs/$jobId/manifest", token)
        try {
            conn.requestMethod = "GET"
            val arr = JSONObject(readResponse(conn, "Manifest")).getJSONArray("sentences")
            return List(arr.length()) { i ->
                val s = arr.getJSONObject(i)
                ServerSentence(
                    id             = s.getString("id"),
                    beginMs        = s.getLong("begin_ms"),
                    endMs          = s.getLong("end_ms"),
                    paragraphIndex = s.optInt("paragraph_index", 0),
                )
            }
        } finally { conn.disconnect() }
    }

    private fun deleteServerJob(jobId: String, baseUrl: String, token: String) {
        val conn = openConn("$baseUrl/v1/jobs/$jobId", token)
        try {
            conn.requestMethod = "DELETE"
            val code = conn.responseCode
            Log.d(TAG, "DELETE job $jobId -> HTTP $code")
        } finally { conn.disconnect() }
    }

    private fun openConn(url: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).also { c ->
            if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
        }

    private fun readResponse(conn: HttpURLConnection, ctx: String): String {
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream)
            .use { it.readBytes() }.toString(Charsets.UTF_8)
        if (code == 401) throw IOException("$ctx: 鉴权失败（401），请检查 API Token")
        if (code !in 200..299) throw IOException("$ctx HTTP $code: $body")
        return body
    }

    private fun writeTextPart(out: OutputStream, boundary: String, name: String, value: String) {
        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
        out.write(value.toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray())
    }

    private fun mapTimings(serverSentences: List<ServerSentence>, validSentences: List<Sentence>): List<SentenceTiming> =
        serverSentences.mapIndexed { i, ss ->
            // Server echoes back paragraph_index (= chunkIndex) that we sent in the submit payload.
            // Fall back to positional lookup in case the server omits the field.
            val chunkIdx = if (ss.paragraphIndex >= 0) ss.paragraphIndex
                           else validSentences.getOrNull(i)?.chunkIndex
                                ?: validSentences.lastOrNull()?.chunkIndex ?: 0
            SentenceTiming(i, ss.beginMs, ss.endMs, chunkIdx)
        }

    private fun parseVoice(obj: JSONObject): GptSoVitsVoice = GptSoVitsVoice(
        id = obj.getString("voice_id"),
        displayName = obj.optString("display_name", obj.getString("voice_id")),
        referenceLanguage = obj.optString("reference_language", "zh"),
    )

    private data class ServerSentence(
        val id: String,
        val beginMs: Long,
        val endMs: Long,
        val paragraphIndex: Int = 0,
    )
}
