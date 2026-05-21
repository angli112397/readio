package com.example.readio.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.readio.domain.model.ReadingPosition

@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey val bookId: String,
    val chapterId: String,
    val indexInChapter: Int
)

fun ReadingProgressEntity.toDomain() = ReadingPosition(
    bookId = bookId,
    chapterId = chapterId,
    indexInChapter = indexInChapter
)

fun ReadingPosition.toEntity() = ReadingProgressEntity(
    bookId = bookId,
    chapterId = chapterId,
    indexInChapter = indexInChapter
)
