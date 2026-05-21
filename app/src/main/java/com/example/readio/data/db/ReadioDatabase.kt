package com.example.readio.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.readio.data.db.dao.BookDao
import com.example.readio.data.db.dao.ChapterIndexDao
import com.example.readio.data.db.dao.ReadingProgressDao
import com.example.readio.data.db.entity.BookEntity
import com.example.readio.data.db.entity.ChapterIndexEntity
import com.example.readio.data.db.entity.ReadingProgressEntity

@Database(
    entities = [BookEntity::class, ChapterIndexEntity::class, ReadingProgressEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ReadioDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterIndexDao(): ChapterIndexDao
    abstract fun readingProgressDao(): ReadingProgressDao
}
