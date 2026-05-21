package com.example.readio.domain.usecase

import com.example.readio.domain.model.EpubBook
import com.example.readio.domain.repository.AudioRepository
import com.example.readio.domain.repository.EpubRepository
import com.example.readio.domain.repository.ReadingProgressRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

class DeleteBookUseCase @Inject constructor(
    private val epubRepository: EpubRepository,
    private val audioRepository: AudioRepository,
    private val progressRepository: ReadingProgressRepository
) {
    suspend operator fun invoke(book: EpubBook) {
        coroutineScope {
            book.chapters.forEach { launch { audioRepository.clearChapterAudio(it.id) } }
        }
        progressRepository.clearPosition(book.id)
        epubRepository.deleteBook(book.id)
    }
}
