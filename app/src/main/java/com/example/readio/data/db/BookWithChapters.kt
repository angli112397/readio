package com.example.readio.data.db

import androidx.room.Embedded
import androidx.room.Relation
import com.example.readio.data.db.entity.BookEntity
import com.example.readio.data.db.entity.ChapterIndexEntity
import com.example.readio.data.db.entity.toDomain
import com.example.readio.domain.model.EpubBook

data class BookWithChapters(
    @Embedded val book: BookEntity,
    @Relation(parentColumn = "id", entityColumn = "bookId")
    val chapters: List<ChapterIndexEntity>
)

fun BookWithChapters.toDomain(): EpubBook = book.toDomain(chapters)
