package com.example.readio.domain.repository

import com.example.readio.domain.model.Chapter
import com.example.readio.domain.model.ChapterAudio
import com.example.readio.domain.model.TtsConfig
import java.io.File
import kotlinx.coroutines.flow.Flow

sealed class ChapterAudioState {
    data class Generating(val done: Int, val total: Int) : ChapterAudioState()
    /** [index] is the absolute sentence index; [file] is the synthesized audio file. */
    data class ChunkReady(val index: Int, val file: File) : ChapterAudioState()
    data class Ready(val audio: ChapterAudio) : ChapterAudioState()
    data class Error(val message: String) : ChapterAudioState()
}

interface AudioRepository {

    /**
     * Returns a Flow of [ChapterAudioState] for **playback**:
     *  - LOCAL_ANDROID: synthesizes sentences one by one starting from [startSentenceIndex].
     *  - VOLCENGINE (and future cloud providers): loads from disk only;
     *    emits [ChapterAudioState.Error] if not yet cached.
     */
    fun getChapterAudio(
        chapter: Chapter,
        ttsConfig: TtsConfig,
        startSentenceIndex: Int = 0
    ): Flow<ChapterAudioState>

    /** Delete audio for a single chapter across all providers (used when deleting a book). */
    suspend fun clearChapterAudio(chapterId: String)

    /** Delete all downloaded and cached audio across all providers. */
    suspend fun clearAllAudio()
}
