package com.example.readio.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.readio.domain.model.ChapterIndex
import com.example.readio.domain.model.EpubBook

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val language: String,
    val coverImagePath: String?,
    val importedAt: Long
)

fun BookEntity.toDomain(chapters: List<ChapterIndexEntity>): EpubBook = EpubBook(
    id = id,
    title = title,
    author = author,
    language = language,
    coverImagePath = coverImagePath,
    chapters = chapters.sortedBy { it.indexInBook }.map { it.toDomain() },
    importedAt = importedAt
)
