package com.example.readio.data.audio.cache

import com.example.readio.domain.engine.SynthesisManifest
import java.io.File

/**
 * Central manager for the batch-audio cache directory tree.
 *
 * Directory layout (root = [android.content.Context.filesDir]/audio_cache):
 * ```
 * audio_cache/
 *   $chapterId/
 *     $sanitizedCacheKey/       ← one sub-dir per TtsConfig.cacheKey
 *       manifest.json           ← serialized [SynthesisManifest]
 *       audio.mp3               ← SINGLE_FILE engines (e.g. Volcengine)
 *       task.id                 ← (optional) pending async task ID for crash recovery
 *       0.wav 1.wav …           ← PER_SENTENCE engines
 * ```
 *
 * [BatchTtsEngine] implementations receive the leaf directory from [chapterDir] and
 * own its internal layout. This interface is responsible only for the tree structure,
 * manifest serialisation, and bulk-delete operations.
 */
interface AudioCache {

    /**
     * Returns the leaf cache directory for ([chapterId], [cacheKey]).
     * Created on first access if absent.
     */
    fun chapterDir(chapterId: String, cacheKey: String): File

    /** True if a parseable [SynthesisManifest] exists for ([chapterId], [cacheKey]). */
    fun isReady(chapterId: String, cacheKey: String): Boolean

    /** Read manifest by chapter ID + cache key; null if absent or unparseable. */
    fun readManifest(chapterId: String, cacheKey: String): SynthesisManifest?

    /** Read manifest from an already-known [cacheDir]; null if absent or unparseable. */
    fun readManifest(cacheDir: File): SynthesisManifest?

    /** Serialize and write [manifest] to [cacheDir]/manifest.json. */
    fun writeManifest(cacheDir: File, manifest: SynthesisManifest)

    /** Delete all cached content for [chapterId] (all cache keys). Blocking — call from IO thread. */
    fun clearChapter(chapterId: String)

    /** Delete everything under the cache root. Blocking — call from IO thread. */
    fun clearAll()
}
