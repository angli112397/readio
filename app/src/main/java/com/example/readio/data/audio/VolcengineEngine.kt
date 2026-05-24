package com.example.readio.data.audio

import android.content.Context
import android.util.Log
import com.example.readio.domain.model.AudioSource
import com.example.readio.domain.model.Chapter
import com.example.readio.domain.model.ChapterAudio
import com.example.readio.domain.model.SentenceTimestamp
import com.example.readio.domain.model.TtsConfig
import com.example.readio.domain.model.TtsProvider
import com.example.readio.domain.tts.CloudTtsEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
// Resource-Id for 长文本合成 (fixed; no user configuration needed)
private const val RESOURCE_ID = "volc.tts_async.default"

@Singleton
class VolcengineEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : CloudTtsEngine {

    override val provider: TtsProvider = TtsProvider.VOLCENGINE

    // ── CloudTtsEngine: submit ────────────────────────────────────────────────

    /**
     * Submit the chapter text to the v1 async API.
     * Task ID is persisted to disk and returned to the caller.
     */
    override suspend fun submitChapter(chapter: Chapter, config: TtsConfig): String =
        withContext(Dispatchers.IO) {
            val prepared = prepareCleanedChapter(chapter)
            if (prepared.text.isBlank()) throw IOException("Chapter text is empty after cleaning")
            Log.d(TAG, "Submitting chapter ${chapter.id}: ${prepared.text.length} chars")
            val taskId = submitText(prepared.text, config)
            saveTaskId(chapter.id, taskId)
            Log.d(TAG, "Task $taskId submitted for chapter ${chapter.id}")
            taskId
        }

    // ── CloudTtsEngine: fetch ─────────────────────────────────────────────────

    /**
     * Query the task once. If ready, downloads + saves audio and returns [ChapterAudio].
     * Returns null if synthesis is still in progress (status=0).
     * Throws [IOException] on permanent failure (status=2) or API error.
     */
    override suspend fun fetchIfReady(
        taskId: String,
        chapter: Chapter,
        config: TtsConfig,
        onProgress: suspend (done: Int, total: Int) -> Unit
    ): ChapterAudio? = withContext(Dispatchers.IO) {
        val queryResult = queryOnceDirect(taskId, config) ?: return@withContext null

        val prepared        = prepareCleanedChapter(chapter)
        val sentenceToChunk = mapSentencesToChunks(queryResult.sentences.map { it.text }, prepared)
        val timestamps      = queryResult.sentences.map { SentenceTimestamp(it.startMs, it.endMs) }

        val audioDir  = File(context.filesDir, "audio/${chapter.id}").also { it.mkdirs() }
        val audioFile = File(audioDir, "audio.mp3")
        onProgress(0, 1)
        downloadUrl(queryResult.audioUrl, audioFile)
        saveMetadata(audioDir, timestamps, sentenceToChunk, config.cacheKey)
        clearTaskId(chapter.id)
        onProgress(1, 1)
        Log.d(TAG, "Downloaded and saved audio for chapter ${chapter.id}")

        ChapterAudio(
            chapterId       = chapter.id,
            source          = AudioSource.SingleFile(audioFile, timestamps),
            sentenceToChunk = sentenceToChunk,
            config          = config
        )
    }

    // ── CloudTtsEngine: cache access ──────────────────────────────────────────

    /** Load previously-downloaded audio from disk; null if absent or cache key mismatch. */
    override fun loadCached(chapter: Chapter, config: TtsConfig): ChapterAudio? {
        val audioDir  = File(context.filesDir, "audio/${chapter.id}")
        val audioFile = File(audioDir, "audio.mp3")
        val metaFile  = File(audioDir, "meta.json")
        if (!audioFile.exists() || !metaFile.exists()) return null

        return runCatching {
            val meta = JSONObject(metaFile.readText())
            if (meta.optString("cacheKey") != config.cacheKey) return null

            val tsArr = meta.getJSONArray("timestamps")
            val timestamps = List(tsArr.length()) { i ->
                val obj = tsArr.getJSONObject(i)
                SentenceTimestamp(obj.getLong("startMs"), obj.getLong("endMs"))
            }
            if (timestamps.isEmpty()) return null

            val s2cArr = meta.optJSONArray("sentenceToChunk")
            val sentenceToChunk = if (s2cArr != null) {
                List(s2cArr.length()) { i -> s2cArr.getInt(i) }
            } else {
                // Fallback for older cache files without sentenceToChunk
                chapter.sentences.map { it.chunkIndex }
            }

            ChapterAudio(
                chapterId       = chapter.id,
                source          = AudioSource.SingleFile(audioFile, timestamps),
                sentenceToChunk = sentenceToChunk,
                config          = config
            )
        }.getOrNull()
    }

    override fun hasChapter(chapterId: String, config: TtsConfig): Boolean {
        val metaFile = File(context.filesDir, "audio/$chapterId/meta.json")
        return runCatching {
            JSONObject(metaFile.readText()).optString("cacheKey") == config.cacheKey
        }.getOrDefault(false)
    }

    // ── CloudTtsEngine: task ID persistence ──────────────────────────────────

    override fun saveTaskId(chapterId: String, taskId: String) {
        File(context.filesDir, "audio/$chapterId").mkdirs()
        File(context.filesDir, "audio/$chapterId/task.id").writeText(taskId)
    }

    override fun loadTaskId(chapterId: String): String? {
        val f = File(context.filesDir, "audio/$chapterId/task.id")
        return if (f.exists()) f.readText().trim().takeIf { it.isNotEmpty() } else null
    }

    /** Remove only the task.id file (audio + metadata are preserved). */
    private fun clearTaskId(chapterId: String) {
        File(context.filesDir, "audio/$chapterId/task.id").delete()
    }

    // ── CloudTtsEngine: cache management ─────────────────────────────────────

    /** Delete audio file, metadata, and task ID for this chapter. */
    override fun clearChapter(chapterId: String) {
        File(context.filesDir, "audio/$chapterId").deleteRecursively()
    }

    override fun clearAll() {
        File(context.filesDir, "audio").deleteRecursively()
    }

    // ── v1 API: submit ────────────────────────────────────────────────────────

    /**
     * POST text to the 精品长文本 v1 async TTS API.
     * v1 success response: no "code" field; error response: "code" != 0.
     */
    private fun submitText(text: String, config: TtsConfig): String {
        val payload = JSONObject().apply {
            put("appid",           config.volcAppId)
            put("reqid",           UUID.randomUUID().toString())
            put("text",            text)
            put("format",          "mp3")
            put("voice_type",      config.volcSpeaker)
            put("enable_subtitle", 1)    // 1 = sentence-level timestamps
        }
        val resp = httpPost(SUBMIT_URL, payload, config)
        val errorCode = resp.optInt("code", 0)
        if (errorCode != 0)
            throw IOException("Volcengine submit failed (code=$errorCode): ${resp.optString("message")}")
        return resp.getString("task_id")
    }

    // ── v1 API: query ─────────────────────────────────────────────────────────

    /**
     * Execute one v1 GET query.
     * Returns null if task_status=0 (processing); [QueryResult] on success.
     * Throws [IOException] on task failure (status=2) or API error.
     *
     * v1 status codes: 0=processing, 1=success, 2=failed
     * v1 timestamps:   begin_time / end_time in milliseconds (int)
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
            2    -> throw IOException("Volcengine synthesis failed for task $taskId")
            else -> null   // 0 = still processing
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    /** POST with Bearer token auth (v1 API). */
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

    /** GET with Bearer token auth (v1 query API). */
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

    /** v1 timestamps are in milliseconds — no unit conversion needed. */
    private data class VolcSentence(val text: String, val startMs: Long, val endMs: Long)

    // ── Text sanitization + chunk-position index ──────────────────────────────

    /**
     * Cleaned submission text + a per-character chunk lookup array.
     * Built once; consumed by both [submitText] and [mapSentencesToChunks].
     */
    private data class CleanedChapter(
        /** Sanitized full-chapter text submitted to the API. */
        val text: String,
        /** charToChunk[i] = chunk index owning character i in [text]. O(1) lookup. */
        val charToChunk: IntArray
    )

    private fun prepareCleanedChapter(chapter: Chapter): CleanedChapter {
        val sb      = StringBuilder()
        val chunkOf = ArrayList<Int>()

        chapter.chunks.forEachIndexed { ci, chunk ->
            val cleaned = cleanForTts(chunk.text)
            sb.append(cleaned)
            repeat(cleaned.length) { chunkOf.add(ci) }
        }

        return CleanedChapter(sb.toString(), chunkOf.toIntArray())
    }

    // ── Sentence → Chunk mapping ──────────────────────────────────────────────

    /**
     * Map each API sentence to the chunk it belongs to.
     * API sentence texts are substrings of [prepared.text]; indexOf finds them.
     * Chunk lookup is O(1) via [CleanedChapter.charToChunk].
     */
    private fun mapSentencesToChunks(sentenceTexts: List<String>, prepared: CleanedChapter): List<Int> {
        if (sentenceTexts.isEmpty()) return emptyList()
        val fullText    = prepared.text
        val charToChunk = prepared.charToChunk
        var cursor      = 0

        return sentenceTexts.map { vsText ->
            val trimmed = vsText.trim()
            val found   = fullText.indexOf(trimmed, cursor).takeIf { it >= 0 }
                       ?: fullText.indexOf(trimmed).takeIf { it >= 0 }
            val charPos = if (found != null) { cursor = found + trimmed.length; found } else cursor
            charToChunk.getOrElse(charPos) { charToChunk.lastOrNull() ?: 0 }
        }
    }

    // ── Metadata persistence ──────────────────────────────────────────────────

    private fun saveMetadata(
        dir: File,
        timestamps: List<SentenceTimestamp>,
        sentenceToChunk: List<Int>,
        cacheKey: String
    ) {
        val tsArr  = JSONArray().also { arr ->
            timestamps.forEach { ts ->
                arr.put(JSONObject().apply { put("startMs", ts.startMs); put("endMs", ts.endMs) })
            }
        }
        val s2cArr = JSONArray().also { arr -> sentenceToChunk.forEach { arr.put(it) } }

        File(dir, "meta.json").writeText(
            JSONObject().apply {
                put("cacheKey",        cacheKey)
                put("timestamps",      tsArr)
                put("sentenceToChunk", s2cArr)
            }.toString()
        )
    }
}
