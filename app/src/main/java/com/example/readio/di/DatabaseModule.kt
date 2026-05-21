package com.example.readio.di

import android.content.Context
import androidx.room.Room
import com.example.readio.data.db.ReadioDatabase
import com.example.readio.data.db.dao.BookDao
import com.example.readio.data.db.dao.ChapterIndexDao
import com.example.readio.data.db.dao.ReadingProgressDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ReadioDatabase =
        Room.databaseBuilder(context, ReadioDatabase::class.java, "readio.db")
            .fallbackToDestructiveMigration(true)
            .build()

    @Provides
    fun provideBookDao(db: ReadioDatabase): BookDao = db.bookDao()

    @Provides
    fun provideChapterIndexDao(db: ReadioDatabase): ChapterIndexDao = db.chapterIndexDao()

    @Provides
    fun provideReadingProgressDao(db: ReadioDatabase): ReadingProgressDao = db.readingProgressDao()
}
