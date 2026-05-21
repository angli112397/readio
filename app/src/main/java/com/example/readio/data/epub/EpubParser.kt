package com.example.readio.data.epub

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.text.BreakIterator
import java.util.Locale
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

private const val MIN_SENTENCE_CHARS = 4

@Singleton
class EpubParser @Inject constructor() {

    data class BookMetadata(
        val title: String,
        val author: String?,
        val language: String,
        val chapters: List<ChapterMeta>
    )

    data class ChapterMeta(
        val id: String,
        val title: String,
        val hrefInZip: String,
        val indexInBook: Int
    )

    fun parseBookMetadata(epubFile: File): BookMetadata {
        ZipFile(epubFile).use { zip ->
            val opfPath = parseContainerXml(zip)
            val opfDir = opfPath.substringBeforeLast("/", "")
            return parseOpf(zip, opfPath, opfDir)
        }
    }

    fun parseChapterParagraphs(epubFile: File, hrefInZip: String): List<String> {
        ZipFile(epubFile).use { zip ->
            val entry = zip.getEntry(hrefInZip) ?: return emptyList()
            val html = zip.getInputStream(entry).reader(Charsets.UTF_8).readText()
            val body = Jsoup.parse(html).body()

            // Use <p> blocks as text sources; fall back to full body text
            val textBlocks = body.select("p")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .ifEmpty { listOf(body.text()) }

            // Split every block into individual sentences
            return textBlocks.flatMap { splitSentences(it) }
        }
    }

    private fun splitSentences(text: String): List<String> {
        val iterator = BreakIterator.getSentenceInstance(Locale.CHINESE)
        iterator.setText(text)
        val result = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val sentence = text.substring(start, end).trim()
            if (sentence.length >= MIN_SENTENCE_CHARS) result.add(sentence)
            start = end
            end = iterator.next()
        }
        return result
    }

    private fun parseContainerXml(zip: ZipFile): String {
        val entry = zip.getEntry("META-INF/container.xml")
            ?: error("META-INF/container.xml not found — not a valid epub")
        val doc = zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) }
        return doc.selectFirst("rootfile")?.attr("full-path")
            ?: error("rootfile not found in container.xml")
    }

    private fun parseOpf(zip: ZipFile, opfPath: String, opfDir: String): BookMetadata {
        val entry = zip.getEntry(opfPath) ?: error("OPF not found: $opfPath")
        val doc = zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) }

        val title = doc.getElementsByTag("dc:title").first()?.text()?.ifBlank { null } ?: "Unknown"
        val author = doc.getElementsByTag("dc:creator").first()?.text()?.ifBlank { null }
        val language = doc.getElementsByTag("dc:language").first()?.text()?.ifBlank { null } ?: "en"

        // manifest: id → relative href
        val manifest: Map<String, String> = doc.select("manifest > item").associate { item ->
            item.attr("id") to item.attr("href")
        }

        // spine: ordered idrefs, skip non-linear items (covers, TOC pages etc.)
        val spineIdrefs = doc.select("spine > itemref")
            .filter { it.attr("linear") != "no" }
            .map { it.attr("idref") }

        val ncxTitles = tryParseNcx(zip, opfDir, manifest)

        val chapters = spineIdrefs.mapIndexedNotNull { index, idref ->
            val href = manifest[idref] ?: return@mapIndexedNotNull null
            val hrefInZip = if (opfDir.isEmpty()) href else "$opfDir/$href"
            val title = ncxTitles[href]
                ?: ncxTitles[href.substringAfterLast("/")]
                ?: "Chapter ${index + 1}"
            ChapterMeta(id = idref, title = title, hrefInZip = hrefInZip, indexInBook = index)
        }

        return BookMetadata(title, author, language, chapters)
    }

    private fun tryParseNcx(zip: ZipFile, opfDir: String, manifest: Map<String, String>): Map<String, String> {
        val ncxHref = manifest.values.firstOrNull { it.endsWith(".ncx") } ?: return emptyMap()
        val ncxPath = if (opfDir.isEmpty()) ncxHref else "$opfDir/$ncxHref"
        val entry = zip.getEntry(ncxPath) ?: return emptyMap()
        return runCatching {
            val doc = zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) }
            // navPoint: content src (before fragment) → navLabel text
            doc.select("navPoint").associate { navPoint ->
                val src = navPoint.selectFirst("content")?.attr("src")?.substringBefore("#") ?: ""
                val label = navPoint.selectFirst("navLabel > text, navLabel text")?.text() ?: ""
                src to label
            }
        }.getOrDefault(emptyMap())
    }
}
