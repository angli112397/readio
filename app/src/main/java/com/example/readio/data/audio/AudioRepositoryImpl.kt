package com.example.readio.data.audio

import android.content.Context
import android.util.Log
import com.example.readio.domain.model.ChapterAudio
import com.example.readio.domain.model.Chapter
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

    override fun getChapterAudio(chapter: Chapter, config: TtsConfig): Flow<ChapterAudioState> =
        channelFlow {
            try {
                val engine = engineMap[config.provider]
                    ?: run {
                        send(ChapterAudioState.Error("No engine registered for ${config.provider.displayName}"))
                        return@channelFlow
                    }

                val audioDir = audioDirFor(chapter.id)

                loadCachedAudio(chapter, config, audioDir)?.let {
                    send(ChapterAudioState.Ready(it))
                    return@channelFlow
                }

                audioDir.deleteRecursively()
                audioDir.mkdirs()

                val paragraphs = chapter.paragraphs
                send(ChapterAudioState.Generating(0, paragraphs.size))

                val files = mutableListOf<File>()

                paragraphs.forEachIndexed { index, paragraph ->
                    val bytes = engine.synthesize(paragraph.text, config)
                    val file = File(audioDir, "para_$index.mp3")
                    file.writeBytes(bytes)
                    files += file
                    send(ChapterAudioState.ParagraphReady(index, file))
                    send(ChapterAudioState.Generating(index + 1, paragraphs.size))
                }

                saveConfigMeta(audioDir, config)
                send(ChapterAudioState.Ready(ChapterAudio(chapter.id, files, config)))

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Audio generation failed", e)
                send(ChapterAudioState.Error(e.message ?: "Unknown error"))
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun hasChapterAudio(chapterId: String, config: TtsConfig): Boolean {
        val dir = audioDirFor(chapterId)
        return dir.exists() && readCacheKey(dir) == config.cacheKey
    }

    override suspend fun clearChapterAudio(chapterId: String) {
        audioDirFor(chapterId).deleteRecursively()
    }

    override suspend fun clearAllAudio() {
        File(context.filesDir, "audio").deleteRecursively()
    }

    // ---- Cache ----

    private fun audioDirFor(chapterId: String) = File(context.filesDir, "audio/$chapterId")

    private fun saveConfigMeta(dir: File, config: TtsConfig) =
        File(dir, "config").writeText(config.cacheKey)

    private fun readCacheKey(dir: File): String? = runCatching {
        File(dir, "config").readText().trim()
    }.getOrNull()

    private fun loadCachedAudio(chapter: Chapter, config: TtsConfig, audioDir: File): ChapterAudio? {
        if (!audioDir.exists() || readCacheKey(audioDir) != config.cacheKey) return null
        val files = chapter.paragraphs.mapIndexed { index, _ ->
            File(audioDir, "para_$index.mp3").takeIf { it.exists() } ?: return null
        }
        return ChapterAudio(chapter.id, files, config)
    }
}
