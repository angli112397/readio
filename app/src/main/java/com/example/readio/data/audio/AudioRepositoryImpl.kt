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
import com.example.readio.domain.tts.CloudTtsEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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
    private val localTtsEngine: LocalTtsEngine,
    cloudEngines: Set<@JvmSuppressWildcards CloudTtsEngine>
) : AudioRepository {

    /** O(1) lookup by provider — extensible without touching this class. */
    private val engineMap: Map<TtsProvider, CloudTtsEngine> =
        cloudEngines.associateBy { it.provider }

    override fun getChapterAudio(
        chapter: Chapter,
        ttsConfig: TtsConfig,
        startSentenceIndex: Int
    ): Flow<ChapterAudioState> =
        when (ttsConfig.provider) {
            TtsProvider.LOCAL_ANDROID -> localAudioFlow(chapter, ttsConfig, startSentenceIndex)
            else                      -> cloudAudioFlow(chapter, ttsConfig)
        }

    // ── LOCAL_ANDROID — real-time synthesis, ephemeral cacheDir ──────────────

    /**
     * Synthesizes sentences starting from [startFrom], skipping earlier ones.
     * Eliminates cold-start latency when the user is already mid-chapter.
     *
     * [ChapterAudioState.ChunkReady.index] is always the absolute sentence index.
     * [ChapterAudio.playlistOffset] = [startFrom], letting the ViewModel translate
     * playlist indices back to sentence indices without extra state.
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
                    if (index < clampedStart) continue
                    val bytes = try {
                        withTimeout(30_000L) { localTtsEngine.synthesize(sentence.text, ttsConfig) }
                    } catch (e: TimeoutCancellationException) {
                        throw IOException("TTS synthesis timed out (sentence $index)")
                    }
                    // cacheDir is ephemeral — Android clears it under storage pressure.
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

    // ── Cloud providers — load from disk, never auto-download ─────────────────

    /**
     * Serves cached cloud audio or tells the user to download first.
     * Never triggers an automatic download — that would consume paid quota without
     * explicit user intent. Downloads are initiated from the chapter-list screen only.
     */
    private fun cloudAudioFlow(chapter: Chapter, ttsConfig: TtsConfig): Flow<ChapterAudioState> =
        channelFlow {
            try {
                val engine = engineMap[ttsConfig.provider]
                if (engine == null) {
                    send(ChapterAudioState.Error("未知的 TTS 服务商: ${ttsConfig.provider}"))
                    return@channelFlow
                }
                val cached = engine.loadCached(chapter, ttsConfig)
                if (cached != null) {
                    send(ChapterAudioState.Ready(cached))
                } else {
                    send(ChapterAudioState.Error("本章节尚未下载，请在章节列表中手动下载后再播放"))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Cloud audio load failed", e)
                send(ChapterAudioState.Error(e.message ?: "Unknown error"))
            }
        }.flowOn(Dispatchers.IO)

    // ── Cache management ──────────────────────────────────────────────────────

    override suspend fun clearChapterAudio(chapterId: String) = withContext(Dispatchers.IO) {
        engineMap.values.forEach { it.clearChapter(chapterId) }
        context.cacheDir.listFiles { f -> f.name.startsWith("tts_${chapterId}_") }
            ?.forEach { it.delete() }
        Unit
    }

    override suspend fun clearAllAudio() = withContext(Dispatchers.IO) {
        engineMap.values.forEach { it.clearAll() }
        // Clear ephemeral local TTS files
        context.cacheDir.listFiles { f -> f.name.startsWith("tts_") }?.forEach { it.delete() }
        Unit
    }
}
