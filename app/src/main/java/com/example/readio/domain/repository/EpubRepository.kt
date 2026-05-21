package com.example.readio.domain.repository

import android.net.Uri
import com.example.readio.domain.model.Chapter
import com.example.readio.domain.model.EpubBook
import kotlinx.coroutines.flow.Flow

interface EpubRepository {

    /** 监听书库，书目变化时自动推送 */
    fun observeBooks(): Flow<List<EpubBook>>

    /**
     * 导入 epub 文件，触发阶段一解析：提取书籍元数据和 ChapterIndex 列表。
     * [sourceUri] 为来源标识符（如 content:// URI），文件持久化由实现层负责。
     */
    suspend fun importBook(sourceUri: Uri): EpubBook

    /** 删除书籍及其所有关联数据（章节缓存、音频等由各自 repository 负责） */
    suspend fun deleteBook(bookId: String)

    /**
     * 阶段二懒加载：解析完整章节内容，产出含 Paragraph 列表的 Chapter。
     * LRU 缓存策略由实现层管理，最多保留 3 章。
     */
    suspend fun loadChapter(bookId: String, chapterId: String): Chapter
}
