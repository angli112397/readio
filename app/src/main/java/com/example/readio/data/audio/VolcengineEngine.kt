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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VolcengineEngine"

// 精品长文本语音合成 v1 API
// 文档: https://www.volcengine.com/docs/6561/1108211
private const val SUBMIT_URL  = "https://openspeech.bytedance.com/api/v1/tts_async/submit"
private const val QUERY_BASE  = "https://openspeech.bytedance.com/api/v1/tts_async/query"
private const val RESOURCE_ID = "volc.tts_async.default"

/** Polling interval while waiting for Volcengine synthesis to complete. */
private const val POLL_INTERVAL_MS = 10_000L

@Singleton
class VolcengineEngine @Inject constructor(
    private val audioCache: AudioCache
) : BatchTtsEngine {

    override val provider: TtsProvider = TtsProvider.VOLCENGINE

    // ── BatchTtsEngine: synthesize ────────────────────────────────────────────

    /**
     * Full synthesis flow:
     * 1. Build cleaned text from sentences.
     * 2. Submit to Volcengine async API (or resume from saved task ID).
     * 3. Auto-poll every [POLL_INTERVAL_MS] ms until result is ready.
     * 4. Download MP3 + map API sentences → chapter chunks.
     * 5. Write [SynthesisManifest] → emit [BatchSynthesisEvent.Complete].
     *
     * Task ID is persisted to [cacheDir]/task.id immediately after submit for crash recovery.
     * If a previous run saved a task ID, this call resumes polling without re-submitting.
     */
    override fun synthesize(
        sentences: List<Sentence>,
        config: TtsConfig,
        cacheDir: File
    ): Flow<BatchSynthesisEvent> = flow {
        if (sentences.isEmpty()) {
            audioCache.writeManifest(cacheDir, SynthesisManifest(
                format = AudioFormat.SINGLE_FILE,
                sentenceCount = 0,
                timings = emptyList()
            ))
            emit(BatchSynthesisEvent.Complete)
            return@flow
        }

        val prepared = buildPreparedText(sentences)
        if (prepared.text.isBlank()) {
            emit(BatchSynthesisEvent.Failed(IOException("Chapter text is empty after cleaning")))
            return@flow
        }

        // Crash recovery: use existing task ID if present
        val taskId = loadTaskId(cacheDir) ?: run {
            emit(BatchSynthesisEvent.Progress(0, sentences.size, "提交合成任务…"))
            val id = submitText(prepared.text, config)
            saveTaskId(cacheDir, id)
            Log.d(TAG, "Submitted task $id (${prepared.text.length} chars)")
            id
        }

        // Auto-poll until synthesis completes on Volcengine's servers
        emit(BatchSynthesisEvent.Progress(0, sentences.size, "合成中，请稍候…"))
        while (true) {
            val result = queryOnceDirect(taskId, config)
            if (result != null) {
                // Download audio from CDN
                emit(BatchSynthesisEvent.Progress(0, sentences.size, "下载音频…"))
                val audioFile = File(cacheDir, "audio.mp3")
                downloadUrl(result.audioUrl, audioFile)

                // Map each Volcengine-returned sentence to a display chunk
                val timings = mapApiSentencesToTimings(result.sentences, prepared)
                audioCache.writeManifest(cacheDir, SynthesisManifest(
                    format = AudioFormat.SINGLE_FILE,
                    sentenceCount = timings.size,
                    timings = timings
                ))
                clearTaskId(cacheDir)
                Log.d(TAG, "Synthesis complete: ${timings.size} sentences, ${audioFile.length()} bytes")
                emit(BatchSynthesisEvent.Complete)
                return@flow
            }
            delay(POLL_INTERVAL_MS)
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

    // ── Task ID persistence (in cacheDir — survives app restarts) ─────────────

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

    // ── v1 API: submit ────────────────────────────────────────────────────────

    private fun submitText(text: String, config: TtsConfig): String {
        val payload = JSONObject().apply {
            put("appid",           config.volcAppId)
            put("reqid",           UUID.randomUUID().toString())
            put("text",            text)
            put("format",          "mp3")
            put("voice_type",      config.volcSpeaker)
            put("enable_subtitle", 1)    // sentence-level timestamps
        }
        val resp      = httpPost(SUBMIT_URL, payload, config)
        val errorCode = resp.optInt("code", 0)
        if (errorCode != 0)
            throw IOException("Volcengine submit failed (code=$errorCode): ${resp.optString("message")}")
        return resp.getString("task_id")
    }

    // ── v1 API: query ─────────────────────────────────────────────────────────

    /**
     * One query call. Returns null if still processing (status=0).
     * Throws [IOException] on permanent failure (status=2) or API error.
     */
    private fun queryOnceDirect(taskId: String, config: TtsConfig): QueryResult? {
        val url  = "$QUERY_BASE?appid=${config.volcAppId}&task_id=$taskId"
        val resp = httpGet(url, config)

        val errorCode = resp.optInt("code", 0)
        if (errorCode != 0)
            throw IOException("Volcengine query error (code=$errorCode): ${resp.optString("message")}")

        val taskStatus = resp.getInt("task_status")
        Log.d(TAG, "Query task=$taskId status=$taskStatus")

        return when (taskStatus) {
            1 -> {
                val sentArr   = resp.optJSONArray("sentences")
                val sentences = sentArr?.let { arr ->
                    List(arr.length()) { j ->
                        val s = arr.getJSONObject(j)
                        VolcSentence(
                            text    = s.getString("text"),
                            startMs = s.getLong("begin_time"),
                            endMs   = s.getLong("end_time")
                        )
                    }
                } ?: emptyList()
                QueryResult(resp.getString("audio_url"), sentences)
            }
            2    -> throw IOException("Volcengine synthesis failed for task $taskId (status=2)")
            else -> null   // 0 = processing
        }
    }

    // ── Text preparation + timing mapping ────────────────────────────────────

    /**
     * Cleaned text submitted to the API + a per-character chunk lookup array.
     * Built once per [synthesize] call; consumed by [buildPreparedText] and [mapApiSentencesToTimings].
     */
    private data class PreparedText(val text: String, val charToChunk: IntArray)

    private fun buildPreparedText(sentences: List<Sentence>): PreparedText {
        val sb          = StringBuilder()
        val charToChunk = ArrayList<Int>()
        sentences.forEach { sentence ->
            val cleaned = cleanForTts(sentence.text)
            sb.append(cleaned)
            repeat(cleaned.length) { charToChunk.add(sentence.chunkIndex) }
        }
        return PreparedText(sb.toString(), charToChunk.toIntArray())
    }

    /**
     * Map each Volcengine-returned sentence to a [SentenceTiming] carrying its display [chunkIndex].
     *
     * Volcengine's segmentation may differ from TextChunker's — text search bridges the gap.
     * [PreparedText.charToChunk] gives O(1) chunk lookup by character position.
     */
    private fun mapApiSentencesToTimings(
        apiSentences: List<VolcSentence>,
        prepared: PreparedText
    ): List<SentenceTiming> {
        val fullText    = prepared.text
        val charToChunk = prepared.charToChunk
        var cursor      = 0
        return apiSentences.mapIndexed { idx, vs ->
            val trimmed  = vs.text.trim()
            val found    = fullText.indexOf(trimmed, cursor).takeIf { it >= 0 }
                        ?: fullText.indexOf(trimmed).takeIf { it >= 0 }
            val charPos  = if (found != null) { cursor = found + trimmed.length; found } else cursor
            val chunkIdx = charToChunk.getOrElse(charPos) { charToChunk.lastOrNull() ?: 0 }
            SentenceTiming(
                sentenceIndex = idx,
                startMs       = vs.startMs,
                endMs         = vs.endMs,
                chunkIndex    = chunkIdx
            )
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun httpPost(url: String, payload: JSONObject, config: TtsConfig): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Resource-Id",   RESOURCE_ID)
            conn.setRequestProperty("Authorization", "Bearer; ${config.volcAccessKey}")
            conn.setRequestProperty("Content-Type",  "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            return readJsonResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String, config: TtsConfig): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Resource-Id",   RESOURCE_ID)
            conn.setRequestProperty("Authorization", "Bearer; ${config.volcAccessKey}")
            return readJsonResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun readJsonResponse(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream)
            .use { it.readBytes() }.toString(Charsets.UTF_8)
        if (code !in 200..299)
            throw IOException("Volcengine HTTP $code: $body")
        return JSONObject(body)
    }

    private fun downloadUrl(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connect()
            if (conn.responseCode != 200)
                throw IOException("Audio download failed: HTTP ${conn.responseCode}")
            conn.inputStream.use { it.copyTo(dest.outputStream()) }
        } finally {
            conn.disconnect()
        }
    }

    // ── Internal data types ───────────────────────────────────────────────────

    private data class QueryResult(val audioUrl: String, val sentences: List<VolcSentence>)

    /** v1 timestamps are already in milliseconds. */
    private data class VolcSentence(val text: String, val startMs: Long, val endMs: Long)
}
