package com.example.readio.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.readio.domain.model.ChapterIndex

@Entity(
    tableName = "chapter_indices",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId")]
)
data class ChapterIndexEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val title: String,
    val href: String,
    val indexInBook: Int
)

fun ChapterIndexEntity.toDomain(): ChapterIndex = ChapterIndex(
    id = id,
    bookId = bookId,
    title = title,
    href = href,
    indexInBook = indexInBook
)
