package com.example.readio.data.audio

import android.content.Context
import android.util.Log
import com.example.readio.data.audio.cache.AudioCache
import com.example.readio.domain.engine.AudioFormat
import com.example.readio.domain.engine.BatchTtsEngine
import com.example.readio.domain.engine.RealtimeTtsEngine
import com.example.readio.domain.engine.SynthesisManifest
import com.example.readio.domain.model.AudioSource
import com.example.readio.domain.model.Chapter
import com.example.readio.domain.model.ChapterAudio
import com.example.readio.domain.model.Sentence
import com.example.readio.domain.model.TtsConfig
import com.example.readio.domain.model.TtsProvider
import com.example.readio.domain.repository.AudioRepository
import com.example.readio.domain.repository.ChapterAudioState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AudioRepo"

@Singleton
class AudioRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    realtimeEngines: Set<@JvmSuppressWildcards RealtimeTtsEngine>,
    batchEngines: Set<@JvmSuppressWildcards BatchTtsEngine>,
    private val audioCache: AudioCache
) : AudioRepository {

    /** O(1) lookup by provider. */
    private val realtimeEngineMap: Map<TtsProvider, RealtimeTtsEngine> =
        realtimeEngines.associateBy { it.provider }
    private val batchEngineMap: Map<TtsProvider, BatchTtsEngine> =
        batchEngines.associateBy { it.provider }

    override fun getChapterAudio(
        chapter: Chapter,
        ttsConfig: TtsConfig,
        startSentenceIndex: Int
    ): Flow<ChapterAudioState> = when {
        realtimeEngineMap.containsKey(ttsConfig.provider) ->
            realtimeAudioFlow(chapter, ttsConfig, startSentenceIndex)
        batchEngineMap.containsKey(ttsConfig.provider) ->
            batchAudioFlow(chapter, ttsConfig)
        else ->
            flow { emit(ChapterAudioState.Error("未知的 TTS 引擎: ${ttsConfig.provider}")) }
    }

    // ── Realtime path — per-sentence WAV, ephemeral cacheDir ────────────────

    /**
     * Synthesises **one WAV file per true sentence** starting from [startFrom] (chunk index).
     *
     * ## Synthesis unit: true sentence vs. atom vs. chunk
     *
     * TextChunker produces two granularities:
     *  - **Atoms** (`chapter.sentences`): split at terminal punctuation (。！？…) AND at commas
     *    when a single sentence exceeds the size limit.  Comma-split atoms are incomplete
     *    clauses — synthesising them individually sounds unnatural.
     *  - **Chunks** (`chapter.chunks`): display units, several atoms merged to ~37 words.
     *    Chunks respect atom boundaries, so a logical sentence can straddle two chunks.
     *
     * **True sentences** (built here from atoms) join consecutive atoms until one ends with
     * terminal punctuation.  This matches natural prosodic units — the TTS model receives
     * "因为他很努力，所以他终于成功了。" as a whole instead of two disconnected fragments.
     *
     * ## Playlist ↔ chunk sync
     *
     * Each playlist item = one true sentence.  [ChapterAudio.sentenceToChunk][k] is the chunk
     * index of the **first atom** of true sentence k.  When playback advances to sentence k,
     * the display scrolls to that chunk.  For a cross-chunk sentence (first atom in chunk N,
     * last atom in chunk N+1), display stays on chunk N for the whole sentence duration, then
     * jumps when the next sentence starts — identical to Apple Books / Kindle behaviour.
     *
     * ## Start index
     *
     * [startFrom] is a chunk index.  Synthesis starts from the first true sentence whose first
     * atom belongs to a chunk ≥ [startFrom].  If the user navigates to a chunk that contains
     * only the tail of a cross-chunk sentence, synthesis skips to the next complete sentence
     * (industry-standard seek behaviour).
     *
     * ## Progress
     *
     * [ChapterAudioState.Generating.done] and [.total] are **relative** to [startFrom]:
     * done counts synthesised sentences from the start index, total is how many remain.
     * The ViewModel uses `done / total` directly without any offset subtraction.
     */
    private fun realtimeAudioFlow(
        chapter: Chapter,
        ttsConfig: TtsConfig,
        startFrom: Int   // chunk index
    ): Flow<ChapterAudioState> =
        channelFlow {
            try {
                val trueSentences = buildTrueSentences(chapter.sentences)
                if (trueSentences.isEmpty()) {
                    send(ChapterAudioState.Ready(
                        ChapterAudio(chapter.id, AudioSource.PerSentence(emptyList()),
                            emptyList(), ttsConfig, playlistOffset = 0)
                    ))
                    return@channelFlow
                }

                // Find the first true sentence at or after the requested chunk.
                val startIdx = trueSentences.indexOfFirst { it.firstChunkIndex >= startFrom }
                    .let { if (it < 0) trueSentences.lastIndex else it }
                val totalFromStart = trueSentences.size - startIdx

                send(ChapterAudioState.Generating(0, totalFromStart))
                val files = mutableListOf<File>()

                for (si in startIdx..trueSentences.lastIndex) {
                    val ts = trueSentences[si]
                    val engine = realtimeEngineMap[ttsConfig.provider]
                        ?: throw IOException("未知的本地 TTS 引擎: ${ttsConfig.provider}")
                    val bytes = try {
                        withTimeout(60_000L) { engine.synthesize(ts.text, ttsConfig) }
                    } catch (e: TimeoutCancellationException) {
                        throw IOException("TTS synthesis timed out (sentence $si)")
                    }
                    val file = File(context.cacheDir, "tts_${chapter.id}_s$si.wav")
                    file.writeBytes(bytes)
                    files += file
                    val relativeDone = si - startIdx + 1
                    send(ChapterAudioState.ChunkReady(si, file))
                    send(ChapterAudioState.Generating(relativeDone, totalFromStart))
                }

                // sentenceToChunk[absoluteSentenceIdx] = chunk of first atom of that sentence.
                // Full list (all sentences) so the ViewModel can seek to any chunk correctly.
                val sentenceToChunk = trueSentences.map { it.firstChunkIndex }
                send(ChapterAudioState.Ready(
                    ChapterAudio(chapter.id, AudioSource.PerSentence(files),
                        sentenceToChunk, ttsConfig, playlistOffset = startIdx)
                ))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Realtime TTS synthesis failed", e)
                send(ChapterAudioState.Error(e.message ?: "Unknown error"))
            }
        }.flowOn(Dispatchers.IO)

    // ── True-sentence helpers ─────────────────────────────────────────────────

    /**
     * A synthesisable sentence: text of one or more consecutive atoms joined until a
     * terminal-punctuation atom is reached, plus the chunk index of the first atom.
     */
    private data class TrueSentence(val text: String, val firstChunkIndex: Int)

    /**
     * Reconstructs true sentences from TextChunker atoms.
     *
     * TextChunker's [splitSentences] can split a long sentence at commas to keep atoms small;
     * those comma fragments are NOT natural sentence units for TTS.  This function re-joins
     * consecutive atoms until one ends with sentence-terminal punctuation (。！？…!?.).
     *
     * Any trailing atoms that never reach a terminal (e.g. a chapter ending mid-sentence) are
     * gathered into a final sentence.
     */
    private fun buildTrueSentences(atoms: List<Sentence>): List<TrueSentence> {
        if (atoms.isEmpty()) return emptyList()
        val result  = mutableListOf<TrueSentence>()
        val buf     = StringBuilder()
        var firstChunk = -1
        for (atom in atoms) {
            // Skip atoms that are purely visual noise (URLs, lone brackets, etc.) — they
            // produce no audible output and must NOT claim the firstChunk of the next real
            // sentence (which would make middle content unreachable during seek).
            if (cleanForTts(atom.text).isBlank()) continue
            if (firstChunk < 0) firstChunk = atom.chunkIndex
            buf.append(atom.text)
            val last = atom.text.trimEnd().lastOrNull()
            if (last != null && last in "。！？…!?.") {
                result += TrueSentence(buf.toString(), firstChunk)
                buf.clear(); firstChunk = -1
            }
        }
        if (buf.isNotEmpty() && firstChunk >= 0) {
            result += TrueSentence(buf.toString(), firstChunk)
        }
        return result
    }

    // ── Batch path — load from disk, never synthesize ─────────────────────────

    /**
     * Serves cached batch audio from [AudioCache].
     * Never triggers synthesis — that is initiated from ChapterListScreen via AudioDownloadManager.
     * Emits [ChapterAudioState.Error] if the chapter has not been downloaded yet.
     */
    private fun batchAudioFlow(
        chapter: Chapter,
        ttsConfig: TtsConfig
    ): Flow<ChapterAudioState> =
        channelFlow {
            try {
                val engine = batchEngineMap[ttsConfig.provider]
                if (engine == null) {
                    send(ChapterAudioState.Error("未知的 TTS 服务商: ${ttsConfig.provider}"))
                    return@channelFlow
                }
                val cacheDir = audioCache.chapterDir(chapter.id, ttsConfig.cacheKey)
                val manifest = engine.loadManifest(cacheDir)
                if (manifest == null) {
                    send(ChapterAudioState.Error("本章节尚未下载，请在章节列表中手动下载后再播放"))
                    return@channelFlow
                }
                val audio = buildChapterAudio(chapter.id, manifest, cacheDir, ttsConfig)
                send(ChapterAudioState.Ready(audio))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Batch audio load failed", e)
                send(ChapterAudioState.Error(e.message ?: "Unknown error"))
            }
        }.flowOn(Dispatchers.IO)

    /** Construct a [ChapterAudio] from a completed [SynthesisManifest]. */
    private fun buildChapterAudio(
        chapterId: String,
        manifest: SynthesisManifest,
        cacheDir: File,
        config: TtsConfig
    ): ChapterAudio {
        val sentenceToChunk = manifest.timings.map { it.chunkIndex }
        return when (manifest.format) {
            AudioFormat.SINGLE_FILE -> ChapterAudio(
                chapterId       = chapterId,
                source          = AudioSource.SingleFile(File(cacheDir, manifest.audioFileName), manifest.timings),
                sentenceToChunk = sentenceToChunk,
                config          = config
            )
            AudioFormat.PER_SENTENCE -> ChapterAudio(
                chapterId       = chapterId,
                source          = AudioSource.PerSentence(
                    manifest.timings.map { File(cacheDir, "${it.sentenceIndex}.wav") }
                ),
                sentenceToChunk = sentenceToChunk,
                config          = config
            )
        }
    }

    // ── Cache management ──────────────────────────────────────────────────────

    override suspend fun clearChapterAudio(chapterId: String) = withContext(Dispatchers.IO) {
        audioCache.clearChapter(chapterId)
        // Also clear ephemeral realtime WAV files from system cacheDir
        context.cacheDir.listFiles { f -> f.name.startsWith("tts_${chapterId}_") }
            ?.forEach { it.delete() }
        Unit
    }

    override suspend fun clearAllAudio() = withContext(Dispatchers.IO) {
        audioCache.clearAll()
        // Also clear ephemeral realtime WAV files
        context.cacheDir.listFiles { f -> f.name.startsWith("tts_") }?.forEach { it.delete() }
        Unit
    }
}
