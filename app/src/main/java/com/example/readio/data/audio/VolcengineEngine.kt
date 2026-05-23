package com.example.readio.data.audio

import android.content.Context
import android.util.Log
import com.example.readio.domain.model.AudioSource
import com.example.readio.domain.model.Chapter
import com.example.readio.domain.model.SentenceTimestamp
import com.example.readio.domain.model.TtsConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
private const val BASE_URL = "https://openspeech.bytedance.com"

/** Bundled result after loading or downloading chapter audio — avoids double meta.json reads. */
data class CachedChapterAudio(
    val source: AudioSource.SingleFile,
    val sentenceToChunk: List<Int>
)

@Singleton
class VolcengineEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Download chapter audio from Volcengine async TTS.
     * Submits the task, polls until complete, downloads MP3, parses timestamps.
     *
     * @param onProgress called with (done, total) during polling
     * @return [CachedChapterAudio] ready for ExoPlayer — no further disk reads needed
     * @throws IOException on API errors or timeout
     */
    suspend fun downloadChapter(
        chapter: Chapter,
        config: TtsConfig,
        onProgress: suspend (done: Int, total: Int) -> Unit
    ): CachedChapterAudio = withContext(Dispatchers.IO) {

        val prepared = prepareCleanedChapter(chapter)
        if (prepared.text.isBlank()) throw IOException("Chapter text is empty")
        Log.d(TAG, "Submission: ${prepared.text.length} chars " +
                   "(saved ${chapter.chunks.sumOf { it.text.length } - prepared.text.length} via sanitization)")

        onProgress(0, chapter.sentences.size)
        val taskId = submit(prepared.text, config)
        Log.d(TAG, "Submitted task $taskId for chapter ${chapter.id}, text=${prepared.text.length} chars")
        if (prepared.text.length > 10_000) {
            Log.w(TAG, "Chapter text is ${prepared.text.length} chars — synthesis may take several minutes")
        }

        val result = pollUntilComplete(taskId, config, onProgress, chapter.sentences.size, prepared.text.length)
        Log.d(TAG, "Task $taskId complete, ${result.sentences.size} sentences, url=${result.audioUrl}")

        if (result.sentences.isEmpty()) {
            Log.w(TAG, "Volcengine returned no sentence timestamps — sync will be approximate")
        }

        val audioDir = File(context.filesDir, "audio/${chapter.id}").also { it.mkdirs() }
        val audioFile = File(audioDir, "audio.mp3")
        downloadUrl(result.audioUrl, audioFile)

        val sentenceToChunk = mapSentencesToChunks(result.sentences.map { it.text }, prepared)
        val timestamps = result.sentences.map {
            SentenceTimestamp(
                startMs = (it.startTime * 1000).toLong(),
                endMs   = (it.endTime   * 1000).toLong()
            )
        }

        saveMetadata(audioDir, timestamps, sentenceToChunk, config.cacheKey)

        CachedChapterAudio(
            source          = AudioSource.SingleFile(audioFile, timestamps),
            sentenceToChunk = sentenceToChunk
        )
    }

    /**
     * Load previously-downloaded audio from disk in a single meta.json read.
     * Returns null if not cached or cache key doesn't match.
     */
    fun loadCached(chapter: Chapter, config: TtsConfig): CachedChapterAudio? {
        val audioDir = File(context.filesDir, "audio/${chapter.id}")
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
                chapter.sentences.map { it.chunkIndex }  // fallback for older cache files
            }

            CachedChapterAudio(
                source          = AudioSource.SingleFile(audioFile, timestamps),
                sentenceToChunk = sentenceToChunk
            )
        }.getOrNull()
    }

    // ── Task ID persistence ───────────────────────────────────────────────────

    fun saveTaskId(chapterId: String, taskId: String) {
        File(context.filesDir, "audio/$chapterId").mkdirs()
        File(context.filesDir, "audio/$chapterId/task.id").writeText(taskId)
    }

    fun loadTaskId(chapterId: String): String? {
        val f = File(context.filesDir, "audio/$chapterId/task.id")
        return if (f.exists()) f.readText().trim().takeIf { it.isNotEmpty() } else null
    }

    fun clearTaskId(chapterId: String) {
        File(context.filesDir, "audio/$chapterId/task.id").delete()
    }

    // ── Submit-only (no polling) ──────────────────────────────────────────────

    /**
     * Submit the chapter text to Volcengine and return the task ID immediately.
     * Does not poll — the caller decides when to fetch the result.
     */
    suspend fun submitOnly(chapter: Chapter, config: TtsConfig): String =
        withContext(Dispatchers.IO) {
            val prepared = prepareCleanedChapter(chapter)
            if (prepared.text.isBlank()) throw IOException("Chapter text is empty after cleaning")
            Log.d(TAG, "Submitting task for chapter ${chapter.id}, text=${prepared.text.length} chars")
            val taskId = submit(prepared.text, config)
            saveTaskId(chapter.id, taskId)
            Log.d(TAG, "Task $taskId submitted for chapter ${chapter.id}")
            taskId
        }

    // ── One-shot query + download ─────────────────────────────────────────────

    /**
     * Query the task status once. Returns null if still pending (status=1).
     * Throws [IOException] on server error (status=3) or API error.
     */
    internal suspend fun queryOnce(taskId: String, config: TtsConfig): QueryResult? =
        withContext(Dispatchers.IO) {
            val resp = post("$BASE_URL/api/v3/tts/query",
                JSONObject().put("task_id", taskId), config)
            val respCode = resp.optInt("code", -1)
            if (respCode != 20000000)
                throw IOException("Volcengine query error code=$respCode: ${resp.optString("message")}")
            val data = resp.getJSONObject("data")
            when (data.getInt("task_status")) {
                2 -> {
                    val sentArr = data.optJSONArray("sentences")
                    val sentences = if (sentArr != null) {
                        List(sentArr.length()) { j ->
                            val s = sentArr.getJSONObject(j)
                            VolcSentence(s.getString("text"),
                                s.getDouble("startTime"), s.getDouble("endTime"))
                        }
                    } else emptyList()
                    QueryResult(data.getString("audio_url"), sentences)
                }
                3 -> throw IOException(
                    "Volcengine synthesis failed for task $taskId: ${data.optString("err_msg")}"
                )
                else -> null  // status=1 or other: still pending
            }
        }

    /**
     * Download and persist the audio once a [QueryResult] is available.
     * Clears the task.id file on success.
     */
    internal suspend fun downloadResult(
        result: QueryResult,
        chapter: Chapter,
        config: TtsConfig,
        onProgress: suspend (done: Int, total: Int) -> Unit
    ): CachedChapterAudio = withContext(Dispatchers.IO) {
        val prepared = prepareCleanedChapter(chapter)
        val sentenceToChunk = mapSentencesToChunks(result.sentences.map { it.text }, prepared)
        val timestamps = result.sentences.map {
            SentenceTimestamp(
                startMs = (it.startTime * 1000).toLong(),
                endMs   = (it.endTime   * 1000).toLong()
            )
        }
        val audioDir = File(context.filesDir, "audio/${chapter.id}").also { it.mkdirs() }
        val audioFile = File(audioDir, "audio.mp3")
        onProgress(0, 1)
        downloadUrl(result.audioUrl, audioFile)
        saveMetadata(audioDir, timestamps, sentenceToChunk, config.cacheKey)
        clearTaskId(chapter.id)
        onProgress(1, 1)
        Log.d(TAG, "Downloaded and saved audio for chapter ${chapter.id}")
        CachedChapterAudio(
            source          = AudioSource.SingleFile(audioFile, timestamps),
            sentenceToChunk = sentenceToChunk
        )
    }

    fun clearChapter(chapterId: String) {
        File(context.filesDir, "audio/$chapterId").deleteRecursively()
    }

    fun clearAll() {
        File(context.filesDir, "audio").deleteRecursively()
    }

    fun hasChapter(chapterId: String, config: TtsConfig): Boolean {
        val metaFile = File(context.filesDir, "audio/$chapterId/meta.json")
        return runCatching {
            JSONObject(metaFile.readText()).optString("cacheKey") == config.cacheKey
        }.getOrDefault(false)
    }

    // ── Volcengine API calls ──────────────────────────────────────────────────

    private fun submit(text: String, config: TtsConfig): String {
        val payload = JSONObject().apply {
            put("user", JSONObject().put("uid", "readio"))
            put("unique_id", UUID.randomUUID().toString())
            put("req_params", JSONObject().apply {
                put("text", text)
                put("speaker", config.volcSpeaker)
                put("audio_params", JSONObject().apply {
                    put("format", "mp3")
                    // sample_rate intentionally omitted — seed-tts-2.0 bigtts speakers do not
                    // accept this param; passing it causes the server task to hang indefinitely.
                    put("enable_timestamp", true)
                })
            })
        }
        val resp = post("$BASE_URL/api/v3/tts/submit", payload, config)
        if (resp.getInt("code") != 20000000)
            throw IOException("Volcengine submit failed: ${resp.optString("message")}")
        return resp.getJSONObject("data").getString("task_id")
    }

    internal data class QueryResult(val audioUrl: String, val sentences: List<VolcSentence>)
    internal data class VolcSentence(val text: String, val startTime: Double, val endTime: Double)

    /**
     * Poll /query until task_status=2 (success) or 3 (failure).
     *
     * Strategy: 1 s × 10 polls, then 2 s × remaining polls.
     * Max polls scales with text length: ~1 char/s processing rate assumed,
     * so a 45 000-char chapter gets ~25 min instead of the flat 6 min limit.
     * Floor 180 polls (6 min), ceiling 900 polls (30 min).
     */
    private suspend fun pollUntilComplete(
        taskId: String,
        config: TtsConfig,
        onProgress: suspend (Int, Int) -> Unit,
        total: Int,
        textLength: Int = 0
    ): QueryResult {
        // 2 s per poll after the first 10; estimate 1 char ≈ 1 s of server work / 20 chars.
        val maxPolls = ((textLength / 20) + 180).coerceIn(180, 900)
        Log.d(TAG, "Polling task $taskId, maxPolls=$maxPolls (~${maxPolls * 2 / 60} min timeout)")
        repeat(maxPolls) { i ->
            delay(if (i < 10) 1_000L else 2_000L)

            val resp = post("$BASE_URL/api/v3/tts/query",
                JSONObject().put("task_id", taskId), config)

            val respCode = resp.optInt("code", -1)
            if (respCode != 20000000)
                throw IOException("Volcengine query error code=$respCode: ${resp.optString("message")}")

            val data = resp.getJSONObject("data")
            val taskStatus = data.getInt("task_status")
            Log.d(TAG, "Poll #$i task=$taskId status=$taskStatus")

            when (taskStatus) {
                2 -> {
                    val sentArr = data.optJSONArray("sentences")
                    val sentences = if (sentArr != null) {
                        List(sentArr.length()) { j ->
                            val s = sentArr.getJSONObject(j)
                            VolcSentence(s.getString("text"),
                                s.getDouble("startTime"), s.getDouble("endTime"))
                        }
                    } else emptyList()
                    onProgress(total, total)
                    return QueryResult(data.getString("audio_url"), sentences)
                }
                3 -> throw IOException(
                    "Volcengine synthesis failed for task $taskId: ${data.optString("err_msg")}"
                )
                else -> onProgress(minOf(i * 2, total - 1), total)
            }
        }
        throw IOException("Volcengine: timeout waiting for task $taskId after $maxPolls polls (~${maxPolls * 2 / 60} min)")
    }

    /** POST with correct error-body handling for 4xx/5xx responses. */
    private fun post(url: String, payload: JSONObject, config: TtsConfig): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-Api-App-Id",      config.volcAppId)
            conn.setRequestProperty("X-Api-Access-Key",  config.volcAccessKey)
            conn.setRequestProperty("X-Api-Resource-Id", config.volcResourceId)
            conn.setRequestProperty("X-Api-Request-Id",  UUID.randomUUID().toString())
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream
                        else conn.errorStream ?: conn.inputStream)
                       .use { it.readBytes() }
                       .toString(Charsets.UTF_8)

            if (code !in 200..299)
                throw IOException("Volcengine HTTP $code: $body")

            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
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

    // ── Text sanitization + chunk-position index ─────────────────────────────

    /**
     * Cleaned text ready for API submission, paired with a per-character chunk lookup.
     * Built once in [prepareCleanedChapter]; consumed by both [submit] and [mapSentencesToChunks].
     */
    private data class CleanedChapter(
        /** Sanitized full-chapter text — submit this to the API. */
        val text: String,
        /** charToChunk[i] = chunk index that owns character i in [text]. O(1) chunk lookup. */
        val charToChunk: IntArray
    )

    /**
     * Build submission text and per-character chunk lookup in a single pass.
     * Cleaning is delegated to [cleanForTts] — the shared sanitization function.
     */
    private fun prepareCleanedChapter(chapter: Chapter): CleanedChapter {
        val sb = StringBuilder()
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
     * Map each Volcengine sentence to the chunk it belongs to.
     *
     * [volcTexts] are substrings of [prepared.text] (the cleaned submission), so indexOf
     * finds them correctly. Chunk lookup is O(1) via [CleanedChapter.charToChunk].
     */
    private fun mapSentencesToChunks(volcTexts: List<String>, prepared: CleanedChapter): List<Int> {
        if (volcTexts.isEmpty()) return emptyList()
        val fullText    = prepared.text
        val charToChunk = prepared.charToChunk
        var cursor = 0

        return volcTexts.map { vsText ->
            val trimmed = vsText.trim()
            val found = fullText.indexOf(trimmed, cursor).takeIf { it >= 0 }
                     ?: fullText.indexOf(trimmed).takeIf { it >= 0 }
            val charPos = if (found != null) { cursor = found + trimmed.length; found } else cursor
            charToChunk.getOrElse(charPos) { charToChunk.lastOrNull() ?: 0 }
        }
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private fun saveMetadata(
        dir: File,
        timestamps: List<SentenceTimestamp>,
        sentenceToChunk: List<Int>,
        cacheKey: String
    ) {
        val tsArr = JSONArray().also { arr ->
            timestamps.forEach { ts ->
                arr.put(JSONObject().apply { put("startMs", ts.startMs); put("endMs", ts.endMs) })
            }
        }
        val s2cArr = JSONArray().also { arr -> sentenceToChunk.forEach { arr.put(it) } }

        File(dir, "meta.json").writeText(
            JSONObject().apply {
                put("cacheKey",       cacheKey)
                put("timestamps",     tsArr)
                put("sentenceToChunk", s2cArr)
            }.toString()
        )
    }
}
