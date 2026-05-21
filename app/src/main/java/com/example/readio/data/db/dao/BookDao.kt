package com.example.readio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.readio.data.db.BookWithChapters
import com.example.readio.data.db.entity.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Transaction
    @Query("SELECT * FROM books ORDER BY importedAt DESC")
    fun observeAllWithChapters(): Flow<List<BookWithChapters>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteById(bookId: String)
}
