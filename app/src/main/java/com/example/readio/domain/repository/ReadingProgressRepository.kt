package com.example.readio.domain.repository

import com.example.readio.domain.model.ReadingPosition

interface ReadingProgressRepository {

    /** 获取书籍的上次阅读位置，从未阅读时返回 null */
    suspend fun getPosition(bookId: String): ReadingPosition?

    /** 持久化当前阅读位置 */
    suspend fun savePosition(position: ReadingPosition)

    /** 删除书籍的阅读进度记录，删书时调用 */
    suspend fun clearPosition(bookId: String)
}
