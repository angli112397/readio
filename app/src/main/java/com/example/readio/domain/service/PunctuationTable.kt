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
     * 」U+300D  』U+300F  " U+201D  ' U+2019  ） U+FF09  ) U+0029
     */
    const val SENTENCE_CLOSERS = "」』”’）)"

    /**
     * Matches a sentence boundary: one or more enders, optionally followed by closers.
     * E.g. "他说道。」" is consumed as a single delimiter.
     */
    val sentenceEndPattern: Regex = Regex("[$SENTENCE_ENDERS]+[$SENTENCE_CLOSERS]*")

    /** Pause-level delimiters: fallback split when a sentence exceeds maxChars. */
    const val COMMA_DELIMITERS = "，、;；：:"
    val commaPattern: Regex = Regex("[$COMMA_DELIMITERS]")

    // ── EPUB text normalization ─────────────────────────────────────────────

    /**
     * Invisible / garbage characters to strip entirely.
     * ﻿ BOM  ​ ZWSP  ‌ ZWNJ  ‍ ZWJ  ­ soft-hyphen  � replacement
     */
    private const val STRIP_CHARS = "﻿​‌‍­�"

    /**
     * Whitespace variants to normalize to a regular ASCII space.
     *   NBSP  　 ideographic space  \t tab
     */
    private const val SPACE_VARIANTS = " 　\t"

    private val stripPattern: Regex = Regex("[$STRIP_CHARS]")
    private val spacePattern: Regex = Regex("[$SPACE_VARIANTS]")
    private val multiSpacePattern: Regex = Regex(" {2,}")

    /** Remove invisible characters and normalize whitespace variants in EPUB paragraph text. */
    fun normalize(text: String): String =
        text.replace(stripPattern, "")
            .replace(spacePattern, " ")
            .replace(multiSpacePattern, " ")
            .trim()
}
