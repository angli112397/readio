package com.example.readio.data.audio

import android.content.Context
import android.util.Log
import com.example.readio.domain.model.Chapter
import com.example.readio.domain.model.ChapterAudio
import com.example.readio.domain.model.TtsConfig
import com.example.readio.domain.model.TtsProvider
import com.example.readio.domain.repository.AudioRepository
import com.example.readio.domain.repository.ChapterAudioState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AudioRepo"

@Singleton
class AudioRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    engines: Set<@JvmSuppressWildcards TtsEngine>
) : AudioRepository {

    private val engineMap: Map<TtsProvider, TtsEngine> = engines.associateBy { it.provider }

    override fun getChapterAudio(chapter: Chapter, ttsConfig: TtsConfig): Flow<ChapterAudioState> =
        channelFlow {
            try {
                val engine = engineMap[ttsConfig.provider]
                    ?: run {
                        send(ChapterAudioState.Error("No engine registered for ${ttsConfig.provider.displayName}"))
                        return@channelFlow
                    }

                val audioDir = audioDirFor(chapter.id)
                val cacheKey = audioCacheKey(ttsConfig, chapter.chunkSize)

                loadCachedAudio(chapter, ttsConfig, cacheKey, audioDir)?.let {
                    send(ChapterAudioState.Ready(it))
                    return@channelFlow
                }

                audioDir.deleteRecursively()
                audioDir.mkdirs()

                val chunks = chapter.chunks
                send(ChapterAudioState.Generating(0, chunks.size))

                val files = mutableListOf<File>()

                chunks.forEachIndexed { index, chunk ->
                    val bytes = engine.synthesize(chunk.text, ttsConfig)
                    val file = File(audioDir, "chunk_$index.mp3")
                    file.writeBytes(bytes)
                    files += file
                    send(ChapterAudioState.ChunkReady(index, file))
                    send(ChapterAudioState.Generating(index + 1, chunks.size))
                }

                saveCacheKey(audioDir, cacheKey)
                send(ChapterAudioState.Ready(ChapterAudio(chapter.id, files, ttsConfig)))

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Audio generation failed", e)
                send(ChapterAudioState.Error(e.message ?: "Unknown error"))
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun hasChapterAudio(chapterId: String, ttsConfig: TtsConfig, chunkSize: Int): Boolean {
        val dir = audioDirFor(chapterId)
        return dir.exists() && readCacheKey(dir) == audioCacheKey(ttsConfig, chunkSize)
    }

    override suspend fun clearChapterAudio(chapterId: String) {
        audioDirFor(chapterId).deleteRecursively()
    }

    override suspend fun clearAllAudio() {
        File(context.filesDir, "audio").deleteRecursively()
    }

    // ---- Cache ----

    private fun audioCacheKey(ttsConfig: TtsConfig, chunkSize: Int) =
        "${ttsConfig.cacheKey}|$chunkSize"

    private fun audioDirFor(chapterId: String) = File(context.filesDir, "audio/$chapterId")

    private fun saveCacheKey(dir: File, key: String) = File(dir, "config").writeText(key)

    private fun readCacheKey(dir: File): String? = runCatching {
        File(dir, "config").readText().trim()
    }.getOrNull()

    private fun loadCachedAudio(
        chapter: Chapter,
        ttsConfig: TtsConfig,
        cacheKey: String,
        dir: File
    ): ChapterAudio? {
        if (!dir.exists() || readCacheKey(dir) != cacheKey) return null
        val files = chapter.chunks.mapIndexed { index, _ ->
            File(dir, "chunk_$index.mp3").takeIf { it.exists() } ?: return null
        }
        return ChapterAudio(chapter.id, files, ttsConfig)
    }
}
