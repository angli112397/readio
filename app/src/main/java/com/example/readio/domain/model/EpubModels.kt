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
    val chunks: List<Chunk>,
    /** Synthesis atoms — one audio file per sentence. Sentence index = ExoPlayer playlist index. */
    val sentences: List<Sentence>
) {
    val chunkCount: Int get() = chunks.size
    fun chunkAt(index: Int): Chunk? = chunks.getOrNull(index)
}

/** Display unit: one wheel item. May span multiple [Sentence]s. */
data class Chunk(
    val id: String,
    val chapterId: String,
    val indexInChapter: Int,
    val text: String,
    /** ExoPlayer playlist index of the first sentence inside this chunk — used for seekTo. */
    val firstSentenceIndex: Int
) {
    companion object {
        fun buildId(chapterId: String, index: Int) = "${chapterId}_$index"
    }
}

/** Synthesis atom: maps to one audio file (= one ExoPlayer playlist item). */
data class Sentence(
    /** Index in the chapter's sentence list — equals the ExoPlayer playlist index. */
    val indexInChapter: Int,
    val text: String,
    /** Which [Chunk] this sentence belongs to — used for display sync. */
    val chunkIndex: Int
)

data class ReadingPosition(
    val bookId: String,
    val chapterId: String,
    val indexInChapter: Int
)
