package com.example.readio.domain.model

data class EpubBook(
    val id: String,
    val title: String,
    val author: String?,
    val language: String,
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

/**
 * 完整章节内容，阶段二懒加载产出，LRU 最多保留 3 章。
 * 进度条 0.0~1.0 归属于 Chapter（"一首歌"的概念）。
 */
data class Chapter(
    val id: String,
    val bookId: String,
    val title: String,
    val indexInBook: Int,
    val paragraphs: List<Paragraph>
) {
    val paragraphCount: Int get() = paragraphs.size

    /** O(1) 随机访问，进度条 seek 直接调用 */
    fun paragraphAt(index: Int): Paragraph? = paragraphs.getOrNull(index)
}

/**
 * 全 app 的原子单元，既是显示单位也是音频定位锚点。
 *
 * 阅读层坐标链：bookId → chapterId + indexInBook → indexInChapter
 * 音频层坐标通过 ChapterAudio.paragraphIndex[indexInChapter] 查询，与阅读层解耦。
 */
data class Paragraph(
    val id: String,
    val chapterId: String,
    val indexInChapter: Int,
    val text: String
) {
    companion object {
        fun buildId(chapterId: String, indexInChapter: Int) =
            "${chapterId}_${indexInChapter}"
    }
}

/**
 * 阅读进度的唯一真理，以段落为锚点。
 * 音频偏移量通过 ChapterAudio.paragraphStartTimeMs(indexInChapter) 按需计算，无需存储。
 */
data class ReadingPosition(
    val bookId: String,
    val chapterId: String,
    val indexInChapter: Int
)
