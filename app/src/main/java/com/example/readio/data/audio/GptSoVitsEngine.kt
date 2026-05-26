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
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GptSoVitsEngine"

/**
 * GPT-SoVITS local GPU inference server (readio-tts API).
 *
 * Async flow:
 *   1. POST /v1/jobs (with Idempotency-Key) → 202 Accepted { job_id }
 *   2. GET  /v1/jobs/{job_id}               → { state, progress }
 *      If state == "completed": proceed; else persist job_id and surface HasTaskId.
 *   3. GET  /v1/jobs/{job_id}/audio         → WAV bytes saved to cacheDir/audio.wav
 *   4. GET  /v1/jobs/{job_id}/manifest      → { sentences: [{id, begin_ms, end_ms}] }
 *
 * No authentication headers — the local server is assumed trusted (LAN).
 */
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
            emit(BatchSynthesisEvent.Failed(
                IOException("GPT-SoVITS 服务器地址未配置，请在设置中填写服务器地址。")
            ))
            return@flow
        }

        // voice_id is required by the server (1-64 chars, alphanumeric + hyphens/underscores).
        if (config.gptSoVitsVoice.isBlank()) {
            emit(BatchSynthesisEvent.Failed(
                IOException("Voice ID 未配置，请在设置 > GPT-SoVITS 中填写 Voice ID。")
            ))
            return@flow
        }

        // Filter sentences that become blank after TTS cleaning; send the rest in order.
        val validSentences = sentences.filter { cleanForTts(it.text).isNotBlank() }
        if (validSentences.isEmpty()) {
            emit(BatchSynthesisEvent.Failed(IOException("章节文本清理后为空")))
            return@flow
        }

        // ── Submit (or resume from saved job_id) ──────────────────────────────

        val jobId = loadTaskId(cacheDir) ?: run {
            emit(BatchSynthesisEvent.Progress(0, sentences.size, "提交合成任务…"))
            val id = submit(validSentences, baseUrl, config.gptSoVitsVoice, cacheDir)
            saveTaskId(cacheDir, id)
            Log.d(TAG, "Submitted job $id to $baseUrl")
            id
        }

        // ── Single query — download if ready, otherwise hand back to user ─────

        emit(BatchSynthesisEvent.Progress(0, sentences.size, "查询任务状态…"))
        val completed = queryOnce(jobId, baseUrl)

        if (completed) {
            emit(BatchSynthesisEvent.Progress(0, sentences.size, "下载音频…"))
            val audioFile = File(cacheDir, "audio.wav")
            downloadAudio(jobId, baseUrl, audioFile)

            val serverManifest = downloadManifest(jobId, baseUrl)
            val timings = mapTimings(serverManifest, validSentences)
            audioCache.writeManifest(cacheDir, SynthesisManifest(
                format        = AudioFormat.SINGLE_FILE,
                audioFileName = "audio.wav",
                sentenceCount = timings.size,
                timings       = timings
            ))
            clearTaskId(cacheDir)
            Log.d(TAG, "Synthesis complete: ${timings.size} timings, file=audio.wav")
            emit(BatchSynthesisEvent.Complete)
        } else {
            Log.d(TAG, "Job $jobId not ready yet → HasTaskId")
            emit(BatchSynthesisEvent.Submitted(jobId))
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

    private fun saveTaskId(cacheDir: File, jobId: String) {
        cacheDir.mkdirs()
        File(cacheDir, "task.id").writeText(jobId)
    }

    private fun clearTaskId(cacheDir: File) {
        File(cacheDir, "task.id").delete()
    }

    // ── Submit ────────────────────────────────────────────────────────────────

    /**
     * POST /v1/jobs
     *
     * Idempotency-Key = cacheDir.absolutePath so retrying the same chapter+config
     * always returns the same existing job rather than creating a duplicate.
     *
     * [voiceId] must be non-blank (validated by [synthesize] before calling here).
     * It references a folder under `references/gpt/` on the server.
     */
    private fun submit(
        sentences: List<Sentence>,
        baseUrl: String,
        voiceId: String,
        cacheDir: File
    ): String {
        val sentencesArray = JSONArray().apply {
            sentences.forEachIndexed { idx, s ->
                put(JSONObject().apply {
                    put("id",              idx.toString())
                    put("text",            cleanForTts(s.text))
                    put("paragraph_index", s.chunkIndex)
                })
            }
        }
        val payload = JSONObject().apply {
            put("chapter_id",      cacheDir.absolutePath)
            put("voice_id",        voiceId)   // required by server (validated non-blank before submit)
            put("sentence_gap_ms", 600)
            put("sentences",       sentencesArray)
        }

        val conn = URL("$baseUrl/v1/jobs").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Idempotency-Key", cacheDir.absolutePath)
            conn.doOutput = true
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream)
                .use { it.readBytes() }.toString(Charsets.UTF_8)
            if (code !in 200..299) throw IOException("Submit HTTP $code: $body")

            return JSONObject(body).getString("job_id")
        } finally {
            conn.disconnect()
        }
    }

    // ── Poll ──────────────────────────────────────────────────────────────────

    /**
     * GET /v1/jobs/{job_id}
     *
     * Returns true if the job is complete, false if still queued/processing.
     * Throws [IOException] on server-side failure or cancellation, with the server's
     * `error` field included in the message for diagnosability.
     */
    private fun queryOnce(jobId: String, baseUrl: String): Boolean {
        val conn = URL("$baseUrl/v1/jobs/$jobId").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream)
                .use { it.readBytes() }.toString(Charsets.UTF_8)
            if (code !in 200..299) throw IOException("Poll HTTP $code: $body")

            val resp  = JSONObject(body)
            val state = resp.getString("state")
            Log.d(TAG, "Poll job=$jobId state=$state body=$body")

            return when (state) {
                "completed" -> true
                "failed", "cancelled" -> {
                    // Surface the server's error field for diagnosability.
                    val serverError = resp.optString("error", "").ifBlank { null }
                    val msg = buildString {
                        append("GPT-SoVITS job $jobId $state")
                        if (serverError != null) append(": $serverError")
                    }
                    Log.e(TAG, "Job $jobId $state — server error: $serverError")
                    throw IOException(msg)
                }
                else -> false  // queued / processing
            }
        } finally {
            conn.disconnect()
        }
    }

    // ── Download audio ────────────────────────────────────────────────────────

    /** GET /v1/jobs/{job_id}/audio — streams WAV bytes to [dest]. */
    private fun downloadAudio(jobId: String, baseUrl: String, dest: File) {
        val conn = URL("$baseUrl/v1/jobs/$jobId/audio").openConnection() as HttpURLConnection
        try {
            conn.connect()
            if (conn.responseCode != 200)
                throw IOException("Audio download HTTP ${conn.responseCode}")
            dest.parentFile?.mkdirs()
            conn.inputStream.use { it.copyTo(dest.outputStream()) }
        } finally {
            conn.disconnect()
        }
    }

    // ── Download manifest ─────────────────────────────────────────────────────

    /**
     * GET /v1/jobs/{job_id}/manifest
     * Response: { "sentences": [{ "id": "0", "begin_ms": 0, "end_ms": 2840 }, …] }
     */
    private fun downloadManifest(jobId: String, baseUrl: String): List<ServerSentence> {
        val conn = URL("$baseUrl/v1/jobs/$jobId/manifest").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream)
                .use { it.readBytes() }.toString(Charsets.UTF_8)
            if (code !in 200..299) throw IOException("Manifest HTTP $code: $body")

            val arr = JSONObject(body).getJSONArray("sentences")
            return List(arr.length()) { i ->
                val s = arr.getJSONObject(i)
                ServerSentence(
                    id      = s.getString("id"),
                    beginMs = s.getLong("begin_ms"),
                    endMs   = s.getLong("end_ms")
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    // ── Timing mapping ────────────────────────────────────────────────────────

    /**
     * Maps server manifest sentences → [SentenceTiming].
     *
     * The server preserves input order and the `id` field equals the submit index,
     * so [serverSentences][i] corresponds to [validSentences][i].
     * [chunkIndex] is read from [validSentences] to drive ChunkWheel sync.
     */
    private fun mapTimings(
        serverSentences: List<ServerSentence>,
        validSentences: List<Sentence>
    ): List<SentenceTiming> =
        serverSentences.mapIndexed { i, ss ->
            val chunkIdx = validSentences.getOrNull(i)?.chunkIndex
                ?: validSentences.lastOrNull()?.chunkIndex ?: 0
            SentenceTiming(i, ss.beginMs, ss.endMs, chunkIdx)
        }

    // ── Internal data types ───────────────────────────────────────────────────

    private data class ServerSentence(val id: String, val beginMs: Long, val endMs: Long)
}
