package com.example.readio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.readio.data.db.entity.ChapterIndexEntity

@Dao
interface ChapterIndexDao {

    @Query("SELECT * FROM chapter_indices WHERE bookId = :bookId ORDER BY indexInBook ASC")
    suspend fun getByBookId(bookId: String): List<ChapterIndexEntity>

    @Query("SELECT * FROM chapter_indices WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ChapterIndexEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<ChapterIndexEntity>)
}
