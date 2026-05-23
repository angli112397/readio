package com.example.readio.data.audio

import android.content.Context
import android.util.Log
import com.example.readio.domain.model.AudioSource
import com.example.readio.domain.model.Chapter
import com.example.readio.domain.model.ChapterAudio
import com.example.readio.domain.model.TtsConfig
import com.example.readio.domain.model.TtsProvider
import com.example.readio.domain.repository.AudioRepository
import com.example.readio.domain.repository.ChapterAudioState
import com.example.readio.domain.repository.TtsTaskResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AudioRepo"

@Singleton
class AudioRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val androidTtsEngine: AndroidTtsEngine,
    private val volcengineEngine: VolcengineEngine
) : AudioRepository {

    override fun getChapterAudio(
        chapter: Chapter,
        ttsConfig: TtsConfig,
        startSentenceIndex: Int
    ): Flow<ChapterAudioState> =
        when (ttsConfig.provider) {
            TtsProvider.LOCAL_ANDROID -> localAudioFlow(chapter, ttsConfig, startSentenceIndex)
            TtsProvider.VOLCENGINE   -> volcengineAudioFlow(chapter, ttsConfig)
        }

    // ── LOCAL_ANDROID — real-time synthesis, cacheDir, no persistence ─────────

    /**
     * Synthesizes sentences starting from [startFrom], skipping earlier ones.
     * This eliminates cold-start latency when the user is mid-chapter: synthesis begins
     * exactly where playback will start instead of re-synthesizing everything from sentence 0.
     *
     * [ChapterAudioState.ChunkReady.index] is always the absolute sentence index.
     * [ChapterAudio.playlistOffset] = [startFrom], allowing the ViewModel to translate
     * playlist indices back to sentence indices without additional state.
     */
    private fun localAudioFlow(
        chapter: Chapter,
        ttsConfig: TtsConfig,
        startFrom: Int
    ): Flow<ChapterAudioState> =
        channelFlow {
            try {
                val sentences = chapter.sentences
                if (sentences.isEmpty()) {
                    send(ChapterAudioState.Ready(
                        ChapterAudio(chapter.id, AudioSource.PerSentence(emptyList()),
                            emptyList(), ttsConfig, playlistOffset = 0)
                    ))
                    return@channelFlow
                }

                val clampedStart = startFrom.coerceIn(0, sentences.lastIndex)
                send(ChapterAudioState.Generating(clampedStart, sentences.size))
                val files = mutableListOf<File>()

                for ((index, sentence) in sentences.withIndex()) {
                    if (index < clampedStart) continue   // skip already-past sentences
                    val bytes = try {
                        withTimeout(30_000L) { androidTtsEngine.synthesize(sentence.text, ttsConfig) }
                    } catch (e: TimeoutCancellationException) {
                        throw IOException("TTS synthesis timed out (sentence $index)")
                    }
                    // cacheDir: ephemeral, Android manages under storage pressure.
                    val file = File(context.cacheDir, "tts_${chapter.id}_$index.wav")
                    file.writeBytes(bytes)
                    files += file
                    send(ChapterAudioState.ChunkReady(index, file))
                    send(ChapterAudioState.Generating(index + 1, sentences.size))
                }

                val sentenceToChunk = sentences.map { it.chunkIndex }
                send(ChapterAudioState.Ready(
                    ChapterAudio(chapter.id, AudioSource.PerSentence(files),
                        sentenceToChunk, ttsConfig, playlistOffset = clampedStart)
                ))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Local TTS synthesis failed", e)
                send(ChapterAudioState.Error(e.message ?: "Unknown error"))
            }
        }.flowOn(Dispatchers.IO)

    // ── VOLCENGINE — load from disk only, no auto-download ───────────────────

    /**
     * Serves cached Volcengine audio or reports that the chapter needs to be downloaded first.
     * Never triggers a download automatically — that would consume paid quota without the
     * user's explicit intent. Downloads are initiated from the chapter list screen only.
     */
    private fun volcengineAudioFlow(chapter: Chapter, ttsConfig: TtsConfig): Flow<ChapterAudioState> =
        channelFlow {
            try {
                val cached = volcengineEngine.loadCached(chapter, ttsConfig)
                if (cached != null) {
                    send(ChapterAudioState.Ready(
                        ChapterAudio(chapter.id, cached.source, cached.sentenceToChunk, ttsConfig)
                    ))
                } else {
                    send(ChapterAudioState.Error("本章节尚未下载，请在章节列表中手动下载后再播放"))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Volcengine load failed", e)
                send(ChapterAudioState.Error(e.message ?: "Unknown error"))
            }
        }.flowOn(Dispatchers.IO)

    // ── User-triggered download ───────────────────────────────────────────────

    /**
     * Download and persist chapter audio for the given provider.
     * For VOLCENGINE: calls the API, emits Generating progress, saves MP3 + metadata.
     * For LOCAL_ANDROID: no persistent cache — emits Ready immediately as a no-op.
     */
    override fun downloadChapterAudio(
        chapter: Chapter,
        ttsConfig: TtsConfig
    ): Flow<ChapterAudioState> = when (ttsConfig.provider) {
        TtsProvider.LOCAL_ANDROID -> channelFlow {
            send(ChapterAudioState.Ready(
                ChapterAudio(chapter.id, AudioSource.PerSentence(emptyList()),
                    emptyList(), ttsConfig)
            ))
        }
        TtsProvider.VOLCENGINE -> channelFlow {
            try {
                val cached = volcengineEngine.downloadChapter(chapter, ttsConfig) { done, total ->
                    send(ChapterAudioState.Generating(done, total))
                }
                send(ChapterAudioState.Ready(
                    ChapterAudio(chapter.id, cached.source, cached.sentenceToChunk, ttsConfig)
                ))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Volcengine download failed", e)
                send(ChapterAudioState.Error(e.message ?: "Unknown error"))
            }
        }.flowOn(Dispatchers.IO)
    }

    // ── Volcengine async task flow ────────────────────────────────────────────

    override suspend fun submitTask(chapter: Chapter, ttsConfig: TtsConfig): String =
        volcengineEngine.submitOnly(chapter, ttsConfig)

    override suspend fun fetchTaskResult(
        taskId: String,
        chapter: Chapter,
        ttsConfig: TtsConfig,
        onProgress: suspend (Int, Int) -> Unit
    ): TtsTaskResult = withContext(Dispatchers.IO) {
        try {
            val result = volcengineEngine.queryOnce(taskId, ttsConfig)
                ?: return@withContext TtsTaskResult.Pending
            val cached = volcengineEngine.downloadResult(result, chapter, ttsConfig, onProgress)
            TtsTaskResult.Complete(
                ChapterAudio(chapter.id, cached.source, cached.sentenceToChunk, ttsConfig)
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchTaskResult failed", e)
            TtsTaskResult.Failed(e.message ?: "Unknown error")
        }
    }

    // ── Task ID persistence ───────────────────────────────────────────────────

    override fun saveTaskId(chapterId: String, taskId: String) =
        volcengineEngine.saveTaskId(chapterId, taskId)

    override fun loadTaskId(chapterId: String): String? =
        volcengineEngine.loadTaskId(chapterId)

    override fun clearTaskId(chapterId: String) =
        volcengineEngine.clearTaskId(chapterId)

    // ── Cache management ──────────────────────────────────────────────────────

    override suspend fun hasChapterAudio(chapterId: String, ttsConfig: TtsConfig): Boolean =
        when (ttsConfig.provider) {
            TtsProvider.LOCAL_ANDROID -> false
            TtsProvider.VOLCENGINE   -> volcengineEngine.hasChapter(chapterId, ttsConfig)
        }

    override suspend fun clearChapterAudio(chapterId: String) = withContext(Dispatchers.IO) {
        volcengineEngine.clearChapter(chapterId)
        context.cacheDir.listFiles { f -> f.name.startsWith("tts_${chapterId}_") }
            ?.forEach { it.delete() }
        Unit
    }

    override suspend fun clearAllAudio() = withContext(Dispatchers.IO) {
        volcengineEngine.clearAll()
        context.cacheDir.listFiles { f -> f.name.startsWith("tts_") }
            ?.forEach { it.delete() }
        Unit
    }
}
