package com.example.readio.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.readio.data.db.dao.BookDao
import com.example.readio.data.db.dao.ChapterIndexDao
import com.example.readio.data.db.dao.ReadingProgressDao
import com.example.readio.data.db.entity.BookEntity
import com.example.readio.data.db.entity.ChapterIndexEntity
import com.example.readio.data.db.entity.ReadingProgressEntity

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE books ADD COLUMN ttsProvider TEXT DEFAULT NULL")
        database.execSQL("ALTER TABLE books ADD COLUMN ttsVoice TEXT DEFAULT NULL")
    }
}

@Database(
    entities = [BookEntity::class, ChapterIndexEntity::class, ReadingProgressEntity::class],
    version = 2,
    exportSchema = false
)
abstract class ReadioDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterIndexDao(): ChapterIndexDao
    abstract fun readingProgressDao(): ReadingProgressDao
}
