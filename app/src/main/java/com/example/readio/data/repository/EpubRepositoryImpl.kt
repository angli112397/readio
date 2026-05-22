package com.example.readio.data.repository

import android.content.Context
import android.net.Uri
import com.example.readio.data.db.dao.BookDao
import com.example.readio.data.db.dao.ChapterIndexDao
import com.example.readio.data.db.entity.BookEntity
import com.example.readio.data.db.entity.ChapterIndexEntity
import com.example.readio.data.db.entity.toDomain
import com.example.readio.data.epub.EpubParser
import com.example.readio.domain.model.Chapter
import com.example.readio.domain.model.Chunk
import com.example.readio.domain.model.EpubBook
import com.example.readio.domain.model.Language
import com.example.readio.domain.repository.EpubRepository
import com.example.readio.domain.service.TextChunker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpubRepositoryImpl @Inject constructor(
    private val bookDao: BookDao,
    private val chapterIndexDao: ChapterIndexDao,
    private val epubParser: EpubParser,
    @ApplicationContext private val context: Context
) : EpubRepository {

    // LRU cache keyed by "$chapterId|$chunkSize" — changing chunk size causes a cache miss
    private val chapterCache = object : LinkedHashMap<String, Chapter>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Chapter>) = size > 3
    }

    override fun observeBooks(): Flow<List<EpubBook>> =
        bookDao.observeAllWithChapters().map { list -> list.map { it.book.toDomain(it.chapters) } }

    override suspend fun importBook(sourceUri: Uri): EpubBook = withContext(Dispatchers.IO) {
        val bookId = UUID.randomUUID().toString()
        val destFile = epubFileFor(bookId)

        context.contentResolver.openInputStream(sourceUri)
            ?.use { input -> destFile.outputStream().use { input.copyTo(it) } }
            ?: error("Cannot open $sourceUri")

        val metadata = epubParser.parseBookMetadata(destFile)

        val bookEntity = BookEntity(
            id = bookId,
            title = metadata.title,
            author = metadata.author,
            language = metadata.language,
            coverImagePath = null,
            importedAt = System.currentTimeMillis()
        )
        bookDao.insert(bookEntity)

        val chapterEntities = metadata.chapters.map { chapter ->
            ChapterIndexEntity(
                id = "${bookId}_${chapter.id}",
                bookId = bookId,
                title = chapter.title,
                href = chapter.hrefInZip,
                indexInBook = chapter.indexInBook
            )
        }
        chapterIndexDao.insertAll(chapterEntities)

        bookEntity.toDomain(chapterEntities)
    }

    override suspend fun deleteBook(bookId: String) {
        epubFileFor(bookId).delete()
        bookDao.deleteById(bookId)
        synchronized(chapterCache) {
            chapterCache.keys.removeIf { it.startsWith("${bookId}_") }
        }
    }

    override suspend fun loadChapter(bookId: String, chapterId: String, chunkSize: Int): Chapter =
        withContext(Dispatchers.IO) {
            val cacheKey = "$chapterId|$chunkSize"
            synchronized(chapterCache) { chapterCache[cacheKey] }?.let { return@withContext it }

            val chapterEntity = chapterIndexDao.getById(chapterId)
                ?: error("Chapter $chapterId not found")
            val bookEntity = bookDao.getById(bookId)
                ?: error("Book $bookId not found")

            val rawTexts = epubParser.parseChapterTexts(epubFileFor(bookId), chapterEntity.href)
            val bookLanguage = Language.fromTag(bookEntity.language)
            val chunkTexts = TextChunker.chunk(rawTexts, chunkSize, bookLanguage)

            val chapter = Chapter(
                id = chapterId,
                bookId = bookId,
                title = chapterEntity.title,
                indexInBook = chapterEntity.indexInBook,
                language = Language.fromTag(bookEntity.language),
                chunkSize = chunkSize,
                chunks = chunkTexts.mapIndexed { index, text ->
                    Chunk(
                        id = Chunk.buildId(chapterId, index),
                        chapterId = chapterId,
                        indexInChapter = index,
                        text = text
                    )
                }
            )
            synchronized(chapterCache) { chapterCache[cacheKey] = chapter }
            chapter
        }

    private fun epubFileFor(bookId: String): File {
        val dir = File(context.filesDir, "epubs").also { it.mkdirs() }
        return File(dir, "$bookId.epub")
    }
}
