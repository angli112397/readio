package com.example.readio.domain.usecase

import com.example.readio.domain.model.Chapter
import com.example.readio.domain.repository.AudioRepository
import com.example.readio.domain.repository.ChapterAudioState
import com.example.readio.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

class PrepareChapterAudioUseCase @Inject constructor(
    private val audioRepository: AudioRepository,
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(chapter: Chapter, startSentenceIndex: Int = 0): Flow<ChapterAudioState> = flow {
        val ttsConfig = settingsRepository.getTtsConfig()
        emitAll(audioRepository.getChapterAudio(chapter, ttsConfig, startSentenceIndex))
    }
}
