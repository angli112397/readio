package com.example.readio.domain.repository

import android.net.Uri
import com.example.readio.domain.model.Chapter
import com.example.readio.domain.model.EpubBook
import kotlinx.coroutines.flow.Flow

interface EpubRepository {

    fun observeBooks(): Flow<List<EpubBook>>

    suspend fun importBook(sourceUri: Uri): EpubBook

    suspend fun deleteBook(bookId: String)

    /**
     * 懒加载章节内容，以 [chunkSize] 为目标字数切分文本。
     * LRU 缓存由实现层管理（key = chapterId|chunkSize）。
     */
    suspend fun loadChapter(bookId: String, chapterId: String, chunkSize: Int): Chapter
}
