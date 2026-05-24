package com.example.readio.domain.service

import com.example.readio.domain.model.Language

/**
 * Result of chunking a chapter's raw paragraphs.
 *
 * - [chunkTexts]      — display units (one per ChunkWheel item)
 * - [sentenceTexts]   — synthesis atoms (one audio file per entry)
 * - [sentenceToChunk] — sentenceToChunk[i] = chunk index for sentence i
 *
 * Invariant: sentenceTexts.size == sentenceToChunk.size
 */
data class ChunkedText(
    val chunkTexts: List<String>,
    val sentenceTexts: List<String>,
    val sentenceToChunk: List<Int>
)

object TextChunker {

    /**
     * Groups raw EPUB paragraph texts into chunks and records the constituent sentences.
     *
     * [maxChars] has a unified visual-density meaning across languages:
     *   CJK  — character count  (each square glyph ≈ one unit)
     *   Latin — word count × LATIN_WORD_WEIGHT (one word ≈ LATIN_WORD_WEIGHT units)
     *
     * Sentence splitting respects paired brackets: 。！？ inside 「」『』""''（）()
     * are never used as split points, keeping quoted speech in one piece.
     */
    fun chunk(rawTexts: List<String>, maxChars: Int, language: Language = Language.UNKNOWN): ChunkedText {
        val filtered = rawTexts.filter { it.isNotBlank() }
        if (maxChars <= 0 || filtered.isEmpty()) {
            return ChunkedText(
                chunkTexts      = filtered,
                sentenceTexts   = filtered,
                sentenceToChunk = filtered.indices.toList()
            )
        }
        val latin     = isLatin(language, filtered)
        val sentences = filtered.flatMap { splitSentences(it, latin) }
        return mergeSentences(sentences, maxChars, latin)
    }

    // 1 English word ≈ 4 CJK chars in screen area at the same font size.
    private const val LATIN_WORD_WEIGHT = 4

    // Paired brackets: sentence-end punctuation inside these is not a split point.
    // Unicode escapes used for curly quotes to avoid char-literal ambiguity.
    private val OPEN_BRACKETS  = setOf('「', '『', '“', '‘', '（', '(')
    private val CLOSE_BRACKETS = setOf('」', '』', '”', '’', '）', ')')
    private val COMMA_SET      = PunctuationTable.COMMA_DELIMITERS.toSet()

    // ── Language detection ────────────────────────────────────────────────────

    /**
     * Heuristic: sample the first few paragraphs, count Latin letters.
     * Latin if >1/3 of sampled characters are ASCII letters.
     */
    private fun isLatin(language: Language, rawTexts: List<String>): Boolean = when (language) {
        Language.EN                          -> true
        Language.ZH, Language.JA, Language.KO -> false
        Language.UNKNOWN -> {
            // Sample up to 3 paragraphs to survive chapter-number / title openers.
            val sample = rawTexts.take(3).joinToString("")
            sample.count { it in 'A'..'Z' || it in 'a'..'z' } > sample.length / 3
        }
    }

    // ── Size measurement ─────────────────────────────────────────────────────

    /** Visual-density units: word_count × LATIN_WORD_WEIGHT for Latin, char count for CJK. */
    private fun size(text: String, latin: Boolean): Int =
        if (latin) countWords(text) * LATIN_WORD_WEIGHT else text.length

    /** Count whitespace-separated words without allocating a split list. */
    private fun countWords(text: String): Int {
        var count = 0
        var inWord = false
        for (c in text) {
            if (c == ' ' || c == '\t') {
                inWord = false
            } else if (!inWord) {
                inWord = true
                count++
            }
        }
        return count
    }

    // ── Sentence splitting ────────────────────────────────────────────────────

    /**
     * Split [text] into sentences, then merge any fragments that were created
     * by splitting inside a bracket pair — keeping quoted/parenthesised spans whole.
     */
    private fun splitSentences(text: String, latin: Boolean): List<String> =
        mergeBracketSpans(splitSentencesRaw(text, latin))

    /** Naive split on sentence-end patterns without bracket awareness. */
    private fun splitSentencesRaw(text: String, latin: Boolean): List<String> {
        val pattern = if (latin) PunctuationTable.latinSentenceEndPattern
                      else PunctuationTable.sentenceEndPattern
        val result = mutableListOf<String>()
        var cursor = 0
        for (match in pattern.findAll(text)) {
            val sentence = text.substring(cursor, match.range.last + 1).trim()
            if (sentence.isNotBlank()) result += sentence
            cursor = match.range.last + 1
        }
        val tail = text.substring(cursor).trim()
        if (tail.isNotBlank()) result += tail
        return result
    }

    /**
     * Re-merge adjacent raw sentences that are inside an unclosed bracket pair.
     * Example: ["「甲说。", "乙答。」丙接道。"] → ["「甲说。乙答。」丙接道。"]
     */
    private fun mergeBracketSpans(sentences: List<String>): List<String> {
        val result  = mutableListOf<String>()
        val current = StringBuilder()
        var depth   = 0
        for (sentence in sentences) {
            if (current.isNotEmpty() && needsSeparator(current, sentence)) current.append(' ')
            current.append(sentence)
            depth = bracketDepthAfter(sentence, depth)
            if (depth == 0) {
                result += current.toString().trim()
                current.clear()
            }
        }
        if (current.isNotEmpty()) result += current.toString().trim()
        return result
    }

    /**
     * Returns true when joining two fragments requires a space.
     *
     * Only Basic Latin + Latin Extended characters (U+0021–U+024F) use inter-word spaces.
     * CJK ideographs, kana, hangul, full-width symbols, and all Unicode punctuation
     * (。！？「」 etc.) are intentionally excluded — they never need a separator space.
     */
    private fun needsSeparator(current: StringBuilder, next: String): Boolean {
        val last  = current.lastOrNull()  ?: return false
        if (last  == ' ') return false
        val first = next.firstOrNull()    ?: return false
        if (first == ' ') return false
        fun Char.isLatinRange() = code in 0x0021..0x024F
        return last.isLatinRange() && first.isLatinRange()
    }

    /** Compute bracket nesting depth after scanning [text], starting from [startDepth]. */
    private fun bracketDepthAfter(text: String, startDepth: Int = 0): Int {
        var depth = startDepth
        for (c in text) {
            when {
                c in OPEN_BRACKETS  -> depth++
                c in CLOSE_BRACKETS -> if (depth > 0) depth--
            }
        }
        return depth
    }

    // ── Chunk assembly ────────────────────────────────────────────────────────

    /**
     * Merge sentences into display chunks, tracking which sentence belongs to which chunk.
     *
     * Two-pass algorithm:
     *
     *  **Pass 1 — Expand**: decompose any sentence that exceeds [maxChars] into atoms
     *  using a priority cascade:
     *    sentence → comma segments (respecting brackets) → hard split by word/char boundary.
     *  All resulting atoms are guaranteed to be ≤ [maxChars], so the packing pass
     *  never needs to make a split decision.
     *
     *  **Pass 2 — Pack**: greedy bin-packing over the atom list. Accumulate atoms into
     *  the current chunk until the next atom would overflow [maxChars], then flush.
     *
     *  Key improvement over the previous `splitOversized` design: comma-split sub-parts
     *  re-enter the *same* packing loop, so a dangling tail (e.g. "的句子") can naturally
     *  merge with the following sentence instead of being stranded in its own chunk.
     *
     * Returns a [ChunkedText] with:
     *  - chunkTexts:      merged display strings (one per ChunkWheel item)
     *  - sentenceTexts:   synthesis atoms (one audio file per entry)
     *  - sentenceToChunk: sentenceToChunk[i] = chunk index for atom i
     */
    private fun mergeSentences(sentences: List<String>, maxChars: Int, latin: Boolean): ChunkedText {
        val chunkTexts      = mutableListOf<String>()
        val sentenceTexts   = mutableListOf<String>()
        val sentenceToChunk = mutableListOf<Int>()

        val pending      = mutableListOf<String>()
        val currentChunk = StringBuilder()
        var currentSize  = 0

        fun flush() {
            if (currentChunk.isEmpty()) return
            val chunkIndex = chunkTexts.size
            chunkTexts += currentChunk.toString()
            pending.forEach { sentenceTexts += it; sentenceToChunk += chunkIndex }
            pending.clear()
            currentChunk.clear()
            currentSize = 0
        }

        // ── Pass 1: expand sentences → atoms (all ≤ maxChars) ────────────────
        val atoms = ArrayList<String>(sentences.size + 8)
        for (sentence in sentences) {
            if (size(sentence, latin) <= maxChars) {
                atoms += sentence
            } else {
                val commaSegs = splitAtCommasRespectingBrackets(sentence)
                if (commaSegs.size > 1) {
                    for (seg in commaSegs) {
                        if (size(seg, latin) <= maxChars) atoms += seg
                        else atoms += hardSplit(seg, maxChars, latin)
                    }
                } else {
                    atoms += hardSplit(sentence, maxChars, latin)
                }
            }
        }

        // ── Pass 2: greedy bin-packing ────────────────────────────────────────
        for (atom in atoms) {
            val atomSize = size(atom, latin)
            if (currentChunk.isNotEmpty() && currentSize + atomSize > maxChars) flush()
            if (latin && currentChunk.isNotEmpty()) currentChunk.append(' ')
            currentChunk.append(atom)
            currentSize += atomSize
            pending += atom
        }
        flush()

        return ChunkedText(chunkTexts, sentenceTexts, sentenceToChunk)
    }

    /** Hard-split [text] by word boundaries (Latin) or character boundaries (CJK). */
    private fun hardSplit(text: String, maxChars: Int, latin: Boolean): List<String> {
        if (!latin) {
            return (0 until text.length step maxChars)
                .map { text.substring(it, minOf(it + maxChars, text.length)) }
        }
        val parts   = mutableListOf<String>()
        val current = StringBuilder()
        var size    = 0
        for (word in text.splitToSequence(' ').filter { it.isNotEmpty() }) {
            if (size > 0 && size + LATIN_WORD_WEIGHT > maxChars) {
                parts += current.toString()
                current.clear()
                size = 0
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
            size += LATIN_WORD_WEIGHT
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    /**
     * Split [text] at comma-class delimiters, skipping any delimiter that is
     * inside a bracket pair — quoted or parenthesised content stays intact.
     */
    private fun splitAtCommasRespectingBrackets(text: String): List<String> {
        val result = mutableListOf<String>()
        var depth  = 0
        var cursor = 0
        for (i in text.indices) {
            when (val c = text[i]) {
                in OPEN_BRACKETS  -> depth++
                in CLOSE_BRACKETS -> if (depth > 0) depth--
                else -> if (depth == 0 && c in COMMA_SET) {
                    // Include the delimiter so merged chunks retain original punctuation.
                    // "A，B" → ["A，", "B"] not ["A", "B"] — chunks stay readable.
                    val segment = text.substring(cursor, i + 1).trim()
                    if (segment.isNotBlank()) result += segment
                    cursor = i + 1
                }
            }
        }
        val tail = text.substring(cursor).trim()
        if (tail.isNotBlank()) result += tail
        return result
    }
}
