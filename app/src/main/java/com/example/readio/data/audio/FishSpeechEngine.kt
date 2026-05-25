package com.example.readio.data.audio

import android.util.Log
import com.example.readio.data.audio.cache.AudioCache
import com.example.readio.domain.engine.AudioFormat
import com.example.readio.domain.engine.BatchSynthesisEvent
import com.example.readio.domain.engine.BatchTtsEngine
import com.example.readio.domain.engine.SentenceTiming
import com.example.readio.domain.engine.SynthesisManifest
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FishSpeechEngine"

/**
 * Fish Speech local GPU inference server.
 *
 * Implements the same Volcengine-compatible async TTS API used by the local server:
 *   1. Submit sentences array → receive task_id.
 *   2. Query task status once; if not ready, persist task_id and let user retry.
 *   3. Download WAV audio + parse sentence timings.
 *
 * No authentication headers — the local server is assumed to be trusted (LAN).
 */
@Singleton
class FishSpeechEngine @Inject constructor(
    private val audioCache: AudioCache
) : BatchTtsEngine {

    override val provider: TtsProvider = TtsProvider.FISH_SPEECH

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

        val baseUrl = config.fishSpeechUrl.trimEnd('/')
        if (baseUrl.isEmpty()) {
            emit(BatchSynthesisEvent.Failed(IOException("Fish Speech 服务器地址未配置，请在设置中填写服务器地址。")))
            return@flow
        }

        // Filter blank-after-cleaning sentences; server gets the rest in order.
        val validSentences = sentences.filter { cleanForTts(it.text).isNotBlank() }
        if (validSentences.isEmpty()) {
            emit(BatchSynthesisEvent.Failed(IOException("Chapter text is empty after cleaning")))
            return@flow
        }

        // ── Submit (or resume from saved task ID) ─────────────────────────────

        val taskId = loadTaskId(cacheDir) ?: run {
            emit(BatchSynthesisEvent.Progress(0, sentences.size, "提交合成任务…"))
            val id = submit(validSentences, baseUrl)
            saveTaskId(cacheDir, id)
            Log.d(TAG, "Submitted task $id to $baseUrl")
            id
        }

        // ── Single query — download if ready, otherwise hand back to user ─────

        emit(BatchSynthesisEvent.Progress(0, sentences.size, "查询任务状态…"))
        val result = queryOnce(taskId, baseUrl)
        if (result != null) {
            emit(BatchSynthesisEvent.Progress(0, sentences.size, "下载音频…"))
            val audioFile = File(cacheDir, "audio.wav")
            downloadUrl(result.audioUrl, audioFile)

            val timings = mapTimings(result.sentenceResults, validSentences)
            audioCache.writeManifest(cacheDir, SynthesisManifest(
                format        = AudioFormat.SINGLE_FILE,
                audioFileName = "audio.wav",
                sentenceCount = timings.size,
                timings       = timings
            ))
            clearTaskId(cacheDir)
            Log.d(TAG, "Synthesis complete: ${timings.size} sentences, file=audio.wav")
            emit(BatchSynthesisEvent.Complete)
        } else {
            Log.d(TAG, "Task $taskId not ready yet, returning to HasTaskId state")
            emit(BatchSynthesisEvent.Submitted(taskId))
        }
    }.flowOn(Dispatchers.IO)

    // ── BatchTtsEngine: cache access ──────────────────────────────────────────

    override fun loadManifest(cacheDir: File): SynthesisManifest? =
        audioCache.readManifest(cacheDir)

    override fun hasChapter(chapterId: String, config: TtsConfig): Boolean =
        audioCache.isReady(chapterId, config.cacheKey)

    override fun pendingTaskId(chapterId: String, config: TtsConfig): String? =
        loadTaskId(audioCache.chapterDir(chapterId, config.cacheKey))

    override fun importTaskId(chapterId: String, taskId: String, config: TtsConfig) {
        val dir = audioCache.chapterDir(chapterId, config.cacheKey)
        saveTaskId(dir, taskId)
    }

    override fun clearChapter(chapterId: String) {
        audioCache.clearChapter(chapterId)
    }

    override fun clearAll() {
        audioCache.clearAll()
    }

    // ── Task ID persistence ───────────────────────────────────────────────────

    private fun loadTaskId(cacheDir: File): String? {
        val f = File(cacheDir, "task.id")
        return if (f.exists()) f.readText().trim().takeIf { it.isNotEmpty() } else null
    }

    private fun saveTaskId(cacheDir: File, taskId: String) {
        cacheDir.mkdirs()
        File(cacheDir, "task.id").writeText(taskId)
    }

    private fun clearTaskId(cacheDir: File) {
        File(cacheDir, "task.id").delete()
    }

    // ── Submit ────────────────────────────────────────────────────────────────

    private fun submit(sentences: List<Sentence>, baseUrl: String): String {
        val cleanedTexts = JSONArray(sentences.map { cleanForTts(it.text) })
        val payload = JSONObject().apply {
            put("reqid",           UUID.randomUUID().toString())
            put("sentences",       cleanedTexts)
            put("format",          "wav")
            put("enable_subtitle", 1)
        }
        val resp = httpPost("$baseUrl/api/v1/tts_async/submit", payload)
        val code = resp.optInt("code", 0)
        if (code != 0)
            throw IOException("Fish Speech submit failed (code=$code): ${resp.optString("message")}")
        return resp.getString("task_id")
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    private fun queryOnce(taskId: String, baseUrl: String): QueryResult? {
        val url  = "$baseUrl/api/v1/tts_async/query?task_id=$taskId"
        val resp = httpGet(url)

        val errorCode = resp.optInt("code", 0)
        if (errorCode != 0)
            throw IOException("Fish Speech query error (code=$errorCode): ${resp.optString("message")}")

        val status = resp.getInt("task_status")
        Log.d(TAG, "Query task=$taskId status=$status")

        return when (status) {
            1 -> {
                val arr = resp.optJSONArray("sentences")
                val sents = arr?.let { a ->
                    List(a.length()) { j ->
                        val s = a.getJSONObject(j)
                        FishSentence(
                            text    = s.optString("text", ""),
                            startMs = s.getLong("begin_time"),
                            endMs   = s.getLong("end_time")
                        )
                    }
                } ?: emptyList()
                QueryResult(resp.getString("audio_url"), sents)
            }
            2    -> throw IOException("Fish Speech synthesis failed for task $taskId (status=2)")
            else -> null   // 0 = processing
        }
    }

    // ── Timing mapping ────────────────────────────────────────────────────────

    /**
     * Maps server sentence results to [SentenceTiming] by index.
     * The server preserves input order exactly, so [apiSentences][i] → [sentences][i].
     */
    private fun mapTimings(
        apiSentences: List<FishSentence>,
        sentences: List<Sentence>
    ): List<SentenceTiming> =
        apiSentences.mapIndexed { i, fs ->
            val chunkIdx = sentences.getOrNull(i)?.chunkIndex
                ?: sentences.lastOrNull()?.chunkIndex ?: 0
            SentenceTiming(i, fs.startMs, fs.endMs, chunkIdx)
        }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun httpPost(url: String, payload: JSONObject): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            return readJsonResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            return readJsonResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun readJsonResponse(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream)
            .use { it.readBytes() }.toString(Charsets.UTF_8)
        if (code !in 200..299) throw IOException("Fish Speech HTTP $code: $body")
        return JSONObject(body)
    }

    private fun downloadUrl(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connect()
            if (conn.responseCode != 200)
                throw IOException("Audio download failed: HTTP ${conn.responseCode}")
            dest.parentFile?.mkdirs()
            conn.inputStream.use { it.copyTo(dest.outputStream()) }
        } finally {
            conn.disconnect()
        }
    }

    // ── Internal data types ───────────────────────────────────────────────────

    private data class QueryResult(val audioUrl: String, val sentenceResults: List<FishSentence>)
    private data class FishSentence(val text: String, val startMs: Long, val endMs: Long)
}
