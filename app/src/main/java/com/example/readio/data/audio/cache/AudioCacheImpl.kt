package com.example.readio.data.audio.cache

import android.content.Context
import com.example.readio.domain.engine.AudioFormat
import com.example.readio.domain.engine.SentenceTiming
import com.example.readio.domain.engine.SynthesisManifest
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioCacheImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : AudioCache {

    private val root = File(context.filesDir, "audio_cache")

    // ── Directory access ──────────────────────────────────────────────────────

    override fun chapterDir(chapterId: String, cacheKey: String): File =
        File(root, "$chapterId/${cacheKey.sanitize()}").also { it.mkdirs() }

    // ── Manifest access ───────────────────────────────────────────────────────

    override fun isReady(chapterId: String, cacheKey: String): Boolean =
        readManifest(chapterId, cacheKey) != null

    override fun readManifest(chapterId: String, cacheKey: String): SynthesisManifest? =
        readManifest(chapterDir(chapterId, cacheKey))

    override fun readManifest(cacheDir: File): SynthesisManifest? {
        val f = File(cacheDir, "manifest.json")
        return if (f.exists()) parseManifest(f.readText()) else null
    }

    override fun writeManifest(cacheDir: File, manifest: SynthesisManifest) {
        File(cacheDir, "manifest.json").writeText(serializeManifest(manifest))
    }

    // ── Cache management ──────────────────────────────────────────────────────

    override fun clearChapter(chapterId: String) {
        File(root, chapterId).deleteRecursively()
    }

    override fun clearAll() {
        root.deleteRecursively()
    }

    // ── JSON serialization ────────────────────────────────────────────────────

    private fun serializeManifest(m: SynthesisManifest): String {
        val timingsArr = JSONArray().also { arr ->
            m.timings.forEach { t ->
                arr.put(JSONObject().apply {
                    put("sentenceIndex", t.sentenceIndex)
                    put("startMs",       t.startMs)
                    put("endMs",         t.endMs)
                    put("chunkIndex",    t.chunkIndex)
                })
            }
        }
        return JSONObject().apply {
            put("version",       m.version)
            put("format",        m.format.name)
            put("sentenceCount", m.sentenceCount)
            put("audioFileName", m.audioFileName)
            put("timings",       timingsArr)
        }.toString()
    }

    private fun parseManifest(json: String): SynthesisManifest? = runCatching {
        val obj    = JSONObject(json)
        val format = AudioFormat.valueOf(obj.getString("format"))
        val count  = obj.getInt("sentenceCount")
        val arr    = obj.getJSONArray("timings")
        val timings = List(arr.length()) { i ->
            val t = arr.getJSONObject(i)
            SentenceTiming(
                sentenceIndex = t.getInt("sentenceIndex"),
                startMs       = t.getLong("startMs"),
                endMs         = t.getLong("endMs"),
                chunkIndex    = t.getInt("chunkIndex")
            )
        }
        SynthesisManifest(
            version       = obj.optInt("version", 1),
            format        = format,
            sentenceCount = count,
            timings       = timings,
            audioFileName = obj.optString("audioFileName", "audio.mp3")
        )
    }.getOrNull()

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Replace filesystem-unsafe characters with underscores.
     * Keeps alphanumerics, dot, hyphen, pipe (used in cache keys like "VOLC|BV406_V2_streaming").
     */
    private fun String.sanitize(): String =
        replace(Regex("[^A-Za-z0-9._\\-|]"), "_")
}
