package com.example.readio.data.audio

import android.util.Log
import com.example.readio.domain.engine.RealtimeTtsEngine
import com.example.readio.domain.model.TtsConfig
import com.example.readio.domain.model.TtsProvider
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SherpaOnnxTts"

/**
 * Local neural TTS engine backed by Sherpa-ONNX VITS.
 *
 * ## Single model slot
 *
 * The engine uses a single user-importable VITS model stored in `filesDir/sherpa_models/vits/`.
 * All text is synthesised with this model regardless of language.
 *
 * ## Lazy loading & thread safety
 *
 * The [OfflineTts] instance is loaded on first use and cached for the lifetime of the app
 * process.  A [Mutex] ensures at most one concurrent load attempt.
 * Call [releaseModel] after importing or deleting a model to clear the cached instance.
 */
@Singleton
class SherpaOnnxTtsEngine @Inject constructor(
    private val models: SherpaModelManager
) : RealtimeTtsEngine {

    override val provider: TtsProvider = TtsProvider.LOCAL_SHERPA_ONNX

    private var vitsTts: OfflineTts? = null
    private val vitsMutex = Mutex()

    // ── Synthesis ────────────────────────────────────────────────────────────

    override suspend fun synthesize(text: String, config: TtsConfig): ByteArray =
        withContext(Dispatchers.Default) {
            val audio: GeneratedAudio = vitsBacked().generate(text, sid = 0, speed = config.speechRate)
            audio.samples.toWavBytes(audio.sampleRate)
        }

    // ── Model management ─────────────────────────────────────────────────────

    /**
     * Releases the cached [OfflineTts] instance.
     *
     * Call this after importing a new model or deleting an existing one so the next
     * [synthesize] call loads the updated files.  The old instance is dereferenced
     * and will be garbage-collected; JNI resources are freed by the finalizer.
     */
    fun releaseModel() {
        vitsTts = null
        Log.i(TAG, "Released VITS model")
    }

    // ── Lazy model accessor ──────────────────────────────────────────────────

    private suspend fun vitsBacked(): OfflineTts = vitsMutex.withLock {
        vitsTts ?: buildModel(models.vitsDir).also { vitsTts = it }
    }

    // ── Model construction ────────────────────────────────────────────────────

    /**
     * Loads and returns an [OfflineTts] for the VITS model directory.
     *
     * On JNI failure the model is invalidated via [SherpaModelManager.invalidateModel]
     * so the UI shows the slot as Empty and prompts the user to re-import.
     */
    private fun buildModel(dir: File): OfflineTts {
        if (!models.isReady()) throw IOException(
            "尚未导入 VITS 语音包，请在设置中导入 .tar.bz2 语音包后再使用。"
        )
        Log.i(TAG, "Loading VITS model from ${dir.absolutePath}")

        // Prefer int8 quantised model; skip symlink stubs (< 1 MB).
        val modelFile = SherpaModelManager.MODEL_NAMES
            .map { File(dir, it) }
            .firstOrNull { it.exists() && it.length() > 1_000_000L }
            ?: throw IOException("VITS 目录中未找到有效的 ONNX 模型文件")

        Log.i(TAG, "VITS: ${modelFile.name} (${modelFile.length() / 1_000_000} MB)")

        val config = buildVitsConfig(dir, modelFile)

        return try {
            OfflineTts(config = config)
        } catch (e: RuntimeException) {
            Log.e(TAG, "VITS JNI load failed — invalidating", e)
            models.invalidateModel()
            throw IOException("VITS 语音包文件损坏，请重新导入。", e)
        }
    }

    /** Builds an [OfflineTtsConfig] for a VITS model directory (e.g. MeloTTS). */
    private fun buildVitsConfig(dir: File, modelFile: File): OfflineTtsConfig {
        // Chinese text normalisation rules (optional — present in most CJK archives).
        val ruleFsts = listOf("date.fst", "number.fst", "phone.fst", "new_heteronym.fst")
            .map { File(dir, it) }
            .filter { it.exists() }
            .joinToString(",") { it.absolutePath }

        // Jieba dictionary for Chinese word segmentation (optional).
        val dictDir = File(dir, "dict").let { if (it.isDirectory) it.absolutePath else "" }

        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model   = modelFile.absolutePath,
                    lexicon = File(dir, "lexicon.txt").absolutePath,
                    tokens  = File(dir, "tokens.txt").absolutePath,
                    dictDir = dictDir,
                ),
                numThreads = 2,
                provider   = "cpu",
            ),
            ruleFsts = ruleFsts,
        )
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    /**
     * Converts a 32-bit float PCM array (samples in [-1, 1]) to a standard WAV byte array.
     * Output is 16-bit signed PCM, little-endian, mono, at [sampleRate].
     */
    private fun FloatArray.toWavBytes(sampleRate: Int): ByteArray {
        val numChannels   = 1
        val bitsPerSample = 16
        val byteRate      = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign    = numChannels * bitsPerSample / 8
        val dataSize      = size * blockAlign

        val out  = ByteArrayOutputStream(44 + dataSize)
        val data = DataOutputStream(out)

        // RIFF header
        data.writeBytes("RIFF"); data.writeIntLE(36 + dataSize); data.writeBytes("WAVE")
        // fmt chunk
        data.writeBytes("fmt "); data.writeIntLE(16)
        data.writeShortLE(1); data.writeShortLE(numChannels)
        data.writeIntLE(sampleRate); data.writeIntLE(byteRate)
        data.writeShortLE(blockAlign); data.writeShortLE(bitsPerSample)
        // data chunk
        data.writeBytes("data"); data.writeIntLE(dataSize)
        for (sample in this) {
            data.writeShortLE((sample.coerceIn(-1f, 1f) * 32767f).toInt())
        }
        data.flush()
        return out.toByteArray()
    }

    private fun DataOutputStream.writeIntLE(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
        write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }
    private fun DataOutputStream.writeShortLE(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
}
