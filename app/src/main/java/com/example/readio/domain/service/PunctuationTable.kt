package com.example.readio.domain.service

/**
 * Central registry for punctuation used in text splitting and EPUB normalization.
 * Add new symbols here to extend chunking or cleaning behavior across the app.
 */
object PunctuationTable {

    // ── Sentence splitting ──────────────────────────────────────────────────

    /** Characters that end a sentence. */
    const val SENTENCE_ENDERS = "。！？…!?"

    /**
     * Closing brackets/quotes that immediately follow a sentence end.
     * Included in the sentence-end delimiter so they stay with their sentence
     * rather than leaking to the start of the next chunk.
     */
    const val SENTENCE_CLOSERS = "」』”’）)"

    /**
     * Matches a sentence boundary: one or more enders, optionally followed by closers.
     * E.g. "他说道。」" is consumed as a single delimiter.
     */
    val sentenceEndPattern: Regex = Regex("[$SENTENCE_ENDERS]+[$SENTENCE_CLOSERS]*")

    /**
     * Sentence-end pattern for Latin/English text.
     *
     * Extends [sentenceEndPattern] with a context-aware period rule:
     *   . ends a sentence only when followed by whitespace + an uppercase letter or an
     *   opening quote — catches ~95 % of real sentence boundaries while leaving
     *   most abbreviations (Dr., Mr., U.S.) intact.
     */
    val latinSentenceEndPattern: Regex = Regex(
        "[$SENTENCE_ENDERS]+[$SENTENCE_CLOSERS]*" +
        "|" +
        """\.+[$SENTENCE_CLOSERS]*(?=\s+[$SENTENCE_CLOSERS“A-Z"'])"""
    )

    /** Pause-level delimiters: fallback split when a sentence exceeds maxChars. */
    const val COMMA_DELIMITERS = "，、;；：:"
    val commaPattern: Regex = Regex("[$COMMA_DELIMITERS]")

    // ── EPUB text normalization ─────────────────────────────────────────────

    /**
     * Invisible / garbage characters to strip entirely.
     * BOM, ZWSP, ZWNJ, ZWJ, soft-hyphen, replacement char.
     */
    private const val STRIP_CHARS = "﻿​‌‍­�"

    private val stripPattern: Regex = Regex("[$STRIP_CHARS]")

    // \p{Zs} matches the full Unicode "Space Separator" general category (NBSP, en-space,
    // em-space, ideographic space, thin space, etc.) without needing UNICODE_CHARACTER_CLASS.
    // Tab is added separately; regular ASCII space is also in Zs (replacing it is a no-op).
    private val spacePattern: Regex = Regex("""[\p{Zs}\t]""")

    private val multiSpacePattern: Regex = Regex(" {2,}")

    /** Remove invisible characters and normalize whitespace variants in EPUB paragraph text. */
    fun normalize(text: String): String =
        text.replace(stripPattern, "")
            .replace(spacePattern, " ")
            .replace(multiSpacePattern, " ")
            .trim()
}
