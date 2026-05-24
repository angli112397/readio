package com.example.readio.data.epub

import com.example.readio.domain.service.PunctuationTable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeVisitor
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

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
            val opfDir  = opfPath.substringBeforeLast("/", "")
            return parseOpf(zip, opfPath, opfDir)
        }
    }

    // ── Chapter text extraction ───────────────────────────────────────────────

    /**
     * Extracts the readable text paragraphs from a single EPUB chapter file.
     *
     * Instead of guessing which HTML tags the author used, we traverse the entire DOM
     * and flush the current text accumulator on every **block-level boundary**.
     * This single traversal correctly handles all known EPUB content structures:
     *
     * - Standard `<p>`-based EPUBs (most commercial publishers, Calibre exports)
     * - `<div>`-based CJK novel EPUBs (many aggregator conversions)
     * - Old bare-TextNode + `<br>` EPUBs (wenku8 / light novel archives)
     * - Heading-only splash pages (volume / part title files)
     * - Nested or mixed structures
     *
     * Pre-processing removes elements whose text must never appear as body content:
     * - `<rt>` / `<rp>` — furigana readings in Japanese EPUBs
     * - `<nav>`          — navigation sections occasionally embedded in EPUB body files
     */
    fun parseChapterTexts(epubFile: File, hrefInZip: String): List<String> {
        ZipFile(epubFile).use { zip ->
            val entry = zip.getEntry(hrefInZip) ?: return emptyList()
            val html  = zip.getInputStream(entry).reader(Charsets.UTF_8).readText()
            val doc   = Jsoup.parse(html)

            // Strip noise that must never appear as readable text.
            doc.select("rt, rp, nav").remove()

            return extractTextBlocks(doc.body() ?: return emptyList())
                .map    { PunctuationTable.normalize(it) }
                .filter { it.isNotBlank() }
        }
    }

    /**
     * Walks the DOM in document order, accumulating text into a buffer and flushing it
     * on every block-level element boundary.
     *
     * **Why this works universally:**
     * Every HTML rendering engine produces a new visual line at block boundaries,
     * regardless of which specific tag was used. By flushing on the same canonical set
     * of block tags, we mirror the renderer's behaviour and produce one entry per visual
     * paragraph — completely tag-agnostic.
     *
     * **Latin inter-word spaces:**
     * When two Latin-range text nodes are joined across an inline element
     * (e.g. `<em>word</em>`) a space is inserted to prevent "wordsfrom" merging.
     * CJK characters never need this treatment.
     */
    private fun extractTextBlocks(body: Element): List<String> {
        val blocks  = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            val text = current.toString().trim()
            if (text.isNotBlank()) blocks += text
            current.clear()
        }

        body.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when {
                    node is TextNode -> {
                        // trimStart strips NBSP indentation (&#xA0;) common in old CJK EPUBs.
                        val text = node.text().trimStart(' ', ' ', '\t')
                        if (text.isNotBlank()) {
                            // Insert an inter-word space when joining two Latin fragments
                            // across an inline element boundary (e.g. <em>foo</em> bar).
                            if (current.isNotEmpty() &&
                                current.last().code    in LATIN_RANGE &&
                                text.trimStart().first().code in LATIN_RANGE
                            ) current.append(' ')
                            current.append(text.trimEnd())
                        }
                    }
                    node is Element && node.tagName() in BLOCK_TAGS -> flush()
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.tagName() in BLOCK_TAGS) flush()
            }
        })
        flush()
        return blocks
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    /**
     * HTML tags that produce a block-level boundary in a visual renderer.
     *
     * Includes `<ul>`/`<ol>` as containers so their direct text (e.g. a watermark
     * inside a bare `<ul>` without `<li>` children) gets its own block and does not
     * bleed into adjacent content.
     * `<nav>` is excluded — it is stripped before traversal.
     */
    private val BLOCK_TAGS = setOf(
        // Sectioning
        "p", "div", "section", "article", "aside", "header", "footer", "main",
        // Headings
        "h1", "h2", "h3", "h4", "h5", "h6",
        // Lists — containers AND items
        "ul", "ol", "li", "dt", "dd",
        // Semantic blocks
        "blockquote", "pre", "address", "figure", "figcaption",
        // Table cells
        "td", "th", "caption",
        // Line/rule breaks
        "br", "hr"
    )

    /** U+0021–U+024F: Basic Latin + Latin-1 Supplement + Latin Extended-A/B. */
    private val LATIN_RANGE = 0x0021..0x024F

    // ── OPF parsing ───────────────────────────────────────────────────────────

    private fun parseContainerXml(zip: ZipFile): String {
        val entry = zip.getEntry("META-INF/container.xml")
            ?: error("META-INF/container.xml not found — not a valid epub")
        val doc = zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) }
        return doc.selectFirst("rootfile")?.attr("full-path")
            ?: error("rootfile not found in container.xml")
    }

    private fun parseOpf(zip: ZipFile, opfPath: String, opfDir: String): BookMetadata {
        val entry = zip.getEntry(opfPath) ?: error("OPF not found: $opfPath")
        val opfDoc = zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) }

        val title    = opfDoc.getElementsByTag("dc:title").first()?.text()?.ifBlank { null } ?: "Unknown"
        val author   = opfDoc.getElementsByTag("dc:creator").first()?.text()?.ifBlank { null }
        val language = opfDoc.getElementsByTag("dc:language").first()?.text()?.ifBlank { null } ?: "en"

        // manifest: idref → href (relative to OPF directory)
        val manifest: Map<String, String> = opfDoc.select("manifest > item").associate { item ->
            item.attr("id") to item.attr("href")
        }

        // spine: ordered idrefs; skip explicitly non-linear items (covers, TOC pages, etc.)
        val spineIdrefs = opfDoc.select("spine > itemref")
            .filter { it.attr("linear") != "no" }
            .map    { it.attr("idref") }

        // Chapter title lookup: prefer EPUB3 Navigation Document, fall back to EPUB2 NCX.
        val chapterTitles = tryParseNav(zip, opfDir, opfDoc)
            .ifEmpty    { tryParseNcx(zip, opfDir, opfDoc) }

        val chapters = spineIdrefs.mapIndexedNotNull { index, idref ->
            val href = manifest[idref] ?: return@mapIndexedNotNull null

            // Strip URL fragment before constructing the zip entry path.
            // Some OPF files point to anchors: "Text/ch1.html#section2".
            // ZipFile entries never contain fragments, so the lookup would silently fail.
            val cleanHref = href.substringBefore("#")
            val hrefInZip = if (opfDir.isEmpty()) cleanHref else "$opfDir/$cleanHref"

            val chapterTitle = chapterTitles[cleanHref]
                ?: chapterTitles[cleanHref.substringAfterLast("/")]
                ?: "Chapter ${index + 1}"

            ChapterMeta(id = idref, title = chapterTitle, hrefInZip = hrefInZip, indexInBook = index)
        }

        return BookMetadata(title, author, language, chapters)
    }

    // ── TOC parsing — EPUB3 Navigation Document ───────────────────────────────

    /**
     * Parses the EPUB3 Navigation Document to build a `filename → chapter title` map.
     *
     * EPUB3 identifies the navigation document by `properties="nav"` on its manifest
     * item. The file is a regular XHTML document containing a `<nav epub:type="toc">`
     * element whose `<a href>` children are the chapter links.
     *
     * Returns an empty map when no navigation document is found (EPUB2-only books).
     */
    private fun tryParseNav(zip: ZipFile, opfDir: String, opfDoc: Document): Map<String, String> {
        // The nav item is identified by "nav" appearing in its space-separated properties list.
        val navHref = opfDoc.selectFirst("manifest > item[properties~=nav]")
            ?.attr("href") ?: return emptyMap()

        val navPath = if (opfDir.isEmpty()) navHref else "$opfDir/$navHref"
        val entry   = zip.getEntry(navPath) ?: return emptyMap()

        return runCatching {
            // Parse as HTML (not XML) so Jsoup handles XHTML5 / HTML5 nav files correctly.
            val doc = zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "") }

            // Prefer the landmark TOC nav; fall back to the first nav in the document.
            // CSS selector: epub\:type~=toc matches the space-separated attribute value.
            val tocNav = doc.selectFirst("nav[epub\\:type~=toc]")
                ?: doc.selectFirst("nav")
                ?: return@runCatching emptyMap<String, String>()

            // Map each link's filename (no fragment) → visible link text.
            tocNav.select("a[href]").associate { a ->
                a.attr("href").substringAfterLast("/").substringBefore("#") to a.text()
            }
        }.getOrDefault(emptyMap())
    }

    // ── TOC parsing — EPUB2 NCX ───────────────────────────────────────────────

    /**
     * Parses the EPUB2 NCX file to build a `filename → chapter title` map.
     *
     * Detection uses the standard media-type `application/x-dtbncx+xml` rather than
     * a filename suffix, which is more reliable across varied EPUB toolchains.
     *
     * Returns an empty map when no NCX is found (EPUB3-only books).
     */
    private fun tryParseNcx(zip: ZipFile, opfDir: String, opfDoc: Document): Map<String, String> {
        val ncxHref = opfDoc
            .selectFirst("manifest > item[media-type=application/x-dtbncx+xml]")
            ?.attr("href")
            ?: opfDoc.select("manifest > item").firstOrNull { it.attr("href").endsWith(".ncx") }
                ?.attr("href")  // fallback: filename suffix for non-standard toolchains
            ?: return emptyMap()

        val ncxPath = if (opfDir.isEmpty()) ncxHref else "$opfDir/$ncxHref"
        val entry   = zip.getEntry(ncxPath) ?: return emptyMap()

        return runCatching {
            val doc = zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) }
            doc.select("navPoint").associate { navPoint ->
                val src   = navPoint.selectFirst("content")?.attr("src")
                    ?.substringAfterLast("/")?.substringBefore("#") ?: ""
                val label = navPoint.selectFirst("navLabel > text, navLabel text")?.text() ?: ""
                src to label
            }
        }.getOrDefault(emptyMap())
    }
}
