package com.example.readio.data.repository

import com.example.readio.data.db.dao.ReadingProgressDao
import com.example.readio.data.db.entity.toDomain
import com.example.readio.data.db.entity.toEntity
import com.example.readio.domain.model.ReadingPosition
import com.example.readio.domain.repository.ReadingProgressRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadingProgressRepositoryImpl @Inject constructor(
    private val dao: ReadingProgressDao
) : ReadingProgressRepository {

    override suspend fun getPosition(bookId: String): ReadingPosition? =
        dao.getByBookId(bookId)?.toDomain()

    override suspend fun savePosition(position: ReadingPosition) =
        dao.upsert(position.toEntity())

    override suspend fun clearPosition(bookId: String) =
        dao.deleteByBookId(bookId)
}
