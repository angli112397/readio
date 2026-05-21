package com.example.readio.domain.usecase

import javax.inject.Inject

import com.example.readio.domain.model.EpubBook
import com.example.readio.domain.model.ReadingPosition
import com.example.readio.domain.repository.ReadingProgressRepository

class GetReadingPositionUseCase @Inject constructor(private val progressRepository: ReadingProgressRepository) {
    suspend operator fun invoke(book: EpubBook): ReadingPosition =
        progressRepository.getPosition(book.id)
            ?: ReadingPosition(
                bookId = book.id,
                chapterId = book.chapters.first().id,
                indexInChapter = 0
            )
}
