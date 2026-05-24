package com.example.readio.domain.usecase

import com.example.readio.domain.model.Chapter
import com.example.readio.domain.model.TtsConfig
import com.example.readio.domain.repository.AudioRepository
import com.example.readio.domain.repository.ChapterAudioState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

class PrepareChapterAudioUseCase @Inject constructor(
    private val audioRepository: AudioRepository
) {
    operator fun invoke(
        chapter: Chapter,
        startSentenceIndex: Int = 0,
        ttsConfig: TtsConfig
    ): Flow<ChapterAudioState> = flow {
        emitAll(audioRepository.getChapterAudio(chapter, ttsConfig, startSentenceIndex))
    }
}
