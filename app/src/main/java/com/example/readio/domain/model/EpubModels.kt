package com.example.readio.domain.model

data class EpubBook(
    val id: String,
    val title: String,
    val author: String?,
    val language: Language,
    val coverImagePath: String?,
    val chapters: List<ChapterIndex>,
    val importedAt: Long = System.currentTimeMillis()
) {
    val chapterCount: Int get() = chapters.size
}

data class ChapterIndex(
    val id: String,
    val bookId: String,
    val title: String,
    val href: String,
    val indexInBook: Int
)

data class Chapter(
    val id: String,
    val bookId: String,
    val title: String,
    val indexInBook: Int,
    val language: Language,
    val chunkSize: Int,
    val chunks: List<Chunk>
) {
    val chunkCount: Int get() = chunks.size
    fun chunkAt(index: Int): Chunk? = chunks.getOrNull(index)
}

/** Display and audio atom: one wheel item, one audio file. */
data class Chunk(
    val id: String,
    val chapterId: String,
    val indexInChapter: Int,
    val text: String
) {
    companion object {
        fun buildId(chapterId: String, index: Int) = "${chapterId}_$index"
    }
}

data class ReadingPosition(
    val bookId: String,
    val chapterId: String,
    val indexInChapter: Int
)
