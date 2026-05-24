package com.example.readio.data.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SherpaModels"

/**
 * Manages local storage and import of a single Sherpa-ONNX VITS model.
 *
 * The model is stored under `filesDir/sherpa_models/vits/` and imported on demand
 * from a user-selected file via the Android Storage Access Framework (no permissions
 * required — the user picks the file via a system file picker).
 *
 * **Supported archive format:** `.tar.bz2` (the format used by sherpa-onnx GitHub Releases).
 * The archive is streamed directly without saving an intermediate copy to disk.
 *
 * **Atomicity:**
 * - [importFromUri] wipes [vitsDir] before extraction begins and wipes it again on
 *   failure, leaving the model in an Empty state on any error.
 * - A `.done` sentinel file is written only after successful extraction.
 * - [isReady] returns true only when the sentinel exists AND a valid ONNX file
 *   (≥ 1 MB) is present — guarding against symlink stubs in some Sherpa archives.
 *
 * **Corruption recovery:**
 * - [SherpaOnnxTtsEngine] calls [invalidateModel] when the JNI loader fails.
 *   This deletes the sentinel and emits [invalidations] so the UI resets to Empty.
 */
@Singleton
class SherpaModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modelsRoot = File(context.filesDir, "sherpa_models")

    val vitsDir: File get() = File(modelsRoot, "vits")

    /** Sentinel file written after successful extraction. */
    private fun doneFile(dir: File) = File(dir, ".done")

    /**
     * Emits [Unit] whenever the model is auto-invalidated at load time.
     * [SettingsViewModel] observes this to reset the UI state to Empty.
     */
    private val _invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val invalidations: Flow<Unit> = _invalidations.asSharedFlow()

    /**
     * True when a fully-extracted VITS model is available for synthesis.
     * Updated synchronously after every import, delete, or invalidation.
     * Observed by [LibraryViewModel] to surface model-state warnings in the per-book TTS picker.
     */
    private val _modelReady = MutableStateFlow(isReady())
    val modelReady: StateFlow<Boolean> = _modelReady.asStateFlow()

    // ── Ready check ───────────────────────────────────────────────────────────

    /**
     * Returns true when the VITS model has a completed import AND a usable ONNX file.
     *
     * Some archives ship `model.int8.onnx` as a tar symlink, which Android extracts
     * as a tiny text stub (< 200 bytes).  Files smaller than 1 MB are ignored.
     */
    fun isReady(): Boolean {
        val dir = vitsDir
        if (!doneFile(dir).exists()) return false
        return MODEL_NAMES.any { name ->
            File(dir, name).let { it.exists() && it.length() > 1_000_000L }
        }
    }

    /**
     * Deletes the `.done` sentinel and emits [invalidations].
     * Called by [SherpaOnnxTtsEngine] when the JNI loader reports a corrupt file.
     */
    fun invalidateModel() {
        doneFile(vitsDir).delete()
        _modelReady.value = false
        _invalidations.tryEmit(Unit)
        Log.w(TAG, "Invalidated VITS model — JNI reported corrupt files")
    }

    // ── Import ────────────────────────────────────────────────────────────────

    /**
     * Imports a Sherpa-ONNX `.tar.bz2` VITS archive from a SAF [Uri].
     *
     * The archive content is streamed through a counting wrapper so [onProgress] is
     * called approximately every 256 KB.  [onProgress] receives
     * `(bytesRead: Long, totalBytes: Long)` where `totalBytes` is -1 when the content
     * provider does not report a file size (e.g. some cloud storage pickers).
     *
     * The top-level directory inside the archive is stripped:
     *   `vits-melo-tts-zh_en/model.onnx` → `<vitsDir>/model.onnx`
     *
     * On any failure the directory is wiped before the exception is rethrown, so
     * the model is left in an empty state rather than a half-extracted state.
     */
    suspend fun importFromUri(
        uri       : Uri,
        onProgress: (read: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val targetDir = vitsDir
        targetDir.deleteRecursively()
        targetDir.mkdirs()

        val totalBytes = queryContentSize(uri)

        try {
            (context.contentResolver.openInputStream(uri)
                ?: throw IOException("无法打开所选文件"))
                .use { raw ->
                    var bytesRead = 0L
                    var lastReport = 0L
                    val counting = object : FilterInputStream(raw) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val n = super.read(b, off, len)
                            if (n > 0) {
                                bytesRead += n
                                if (bytesRead - lastReport >= REPORT_EVERY) {
                                    onProgress(bytesRead, totalBytes)
                                    lastReport = bytesRead
                                }
                            }
                            return n
                        }
                    }
                    extractStream(counting.buffered(), targetDir)
                    onProgress(bytesRead, totalBytes)
                }
            doneFile(targetDir).createNewFile()
            _modelReady.value = true
            Log.i(TAG, "VITS import complete → ${targetDir.absolutePath}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            targetDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            targetDir.deleteRecursively()
            Log.e(TAG, "VITS import failed", e)
            throw IOException("导入失败：${e.message}", e)
        }
    }

    /**
     * Deletes all extracted VITS model files, returning to an Empty state.
     * The in-memory model instance (if any) in [SherpaOnnxTtsEngine] is unaffected
     * until [SherpaOnnxTtsEngine.releaseModel] is called.
     */
    fun deleteVits() {
        vitsDir.deleteRecursively()
        _modelReady.value = false
        Log.i(TAG, "VITS model deleted")
    }

    // ── Extraction ────────────────────────────────────────────────────────────

    /**
     * Streams a tar.bz2 [input] into [targetDir], stripping the top-level archive
     * directory (e.g. `vits-melo-tts-zh_en/`) from every entry path.
     */
    private fun extractStream(input: InputStream, targetDir: File) {
        BZip2CompressorInputStream(input).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val rel = entry.name
                        .substringAfter("/")   // strip top-level dir name
                        .trimStart('/')
                    if (rel.isEmpty()) { entry = tar.nextTarEntry; continue }

                    val outFile = File(targetDir, rel)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> tar.copyTo(out) }
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /**
     * Queries the content provider for the size of [uri] in bytes.
     * Returns -1 when the provider does not report a size.
     */
    private fun queryContentSize(uri: Uri): Long =
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (_: Exception) {
            -1L
        }

    companion object {
        /** Candidate ONNX file names searched in a model directory (preference order). */
        val MODEL_NAMES = listOf("model.int8.onnx", "model.onnx")

        private const val REPORT_EVERY = 256 * 1024L   // emit progress every 256 KB
    }
}
