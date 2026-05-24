package com.example.readio.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.readio.domain.model.EpubBook
import com.example.readio.domain.model.Language
import com.example.readio.domain.model.TtsProvider

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val language: String,
    val coverImagePath: String?,
    val importedAt: Long,
    val ttsProvider: String? = null,
    val ttsVoice: String? = null,
)

fun BookEntity.toDomain(chapters: List<ChapterIndexEntity>): EpubBook = EpubBook(
    id = id,
    title = title,
    author = author,
    language = Language.fromTag(language),
    coverImagePath = coverImagePath,
    chapters = chapters.sortedBy { it.indexInBook }.map { it.toDomain() },
    importedAt = importedAt,
    ttsProvider = ttsProvider?.let { runCatching { TtsProvider.valueOf(it) }.getOrNull() },
    ttsVoice = ttsVoice,
)
