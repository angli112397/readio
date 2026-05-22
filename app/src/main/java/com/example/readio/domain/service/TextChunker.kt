package com.example.readio.domain.service

import com.example.readio.domain.model.Language

object TextChunker {

    /**
     * Groups raw EPUB paragraph texts into chunks.
     *
     * [maxChars] has a unified visual-density meaning across languages:
     *   CJK  — character count  (each square glyph ≈ one unit)
     *   Latin — word count × LATIN_WORD_WEIGHT (one word ≈ LATIN_WORD_WEIGHT units)
     *
     * Sentence splitting respects paired brackets: 。！？ inside 「」『』""''（）()
     * are never used as split points, keeping quoted speech in one piece.
     */
    fun chunk(rawTexts: List<String>, maxChars: Int, language: Language = Language.UNKNOWN): List<String> {
        if (maxChars <= 0 || rawTexts.isEmpty()) return rawTexts.filter { it.isNotBlank() }
        val latin = isLatin(language, rawTexts)
        val sentences = rawTexts.flatMap { splitSentences(it, latin) }
        return mergeSentences(sentences, maxChars, latin)
    }

    // 1 English word ≈ 4 CJK chars in screen area at the same font size.
    private const val LATIN_WORD_WEIGHT = 4

    // Paired brackets: sentence-end punctuation inside these is not a split point.
    private val OPEN_BRACKETS  = setOf('「', '『', '“', '‘', '（', '(')
    private val CLOSE_BRACKETS = setOf('」', '』', '”', '’', '）', ')')
    private val COMMA_SET      = PunctuationTable.COMMA_DELIMITERS.toSet()

    // ── Language detection ────────────────────────────────────────────────────

    private fun isLatin(language: Language, rawTexts: List<String>): Boolean = when (language) {
        Language.EN -> true
        Language.ZH, Language.JA, Language.KO -> false
        Language.UNKNOWN -> rawTexts.firstOrNull()?.let { text ->
            text.count { it in 'A'..'Z' || it in 'a'..'z' } > text.length / 3
        } ?: false
    }

    /** Visual-density units: word_count × LATIN_WORD_WEIGHT for Latin, char count for CJK. */
    private fun size(text: String, latin: Boolean): Int =
        if (latin) text.split(' ').count { it.isNotBlank() } * LATIN_WORD_WEIGHT
        else text.length

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
        pattern.findAll(text).forEach { match ->
            val sentence = text.substring(cursor, match.range.last + 1).trim()
            if (sentence.isNotBlank()) result.add(sentence)
            cursor = match.range.last + 1
        }
        val remaining = text.substring(cursor).trim()
        if (remaining.isNotBlank()) result.add(remaining)
        return result
    }

    /**
     * Re-merge adjacent raw sentences that are inside an unclosed bracket pair.
     * Example: ["「甲说。", "乙答。」丙接道。"] → ["「甲说。乙答。」丙接道。"]
     */
    private fun mergeBracketSpans(sentences: List<String>): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0

        for (sentence in sentences) {
            if (current.isNotEmpty() && needsSeparator(current, sentence)) current.append(' ')
            current.append(sentence)
            depth = bracketDepthAfter(sentence, depth)
            if (depth == 0) {
                result.add(current.toString().trim())
                current.clear()
            }
        }
        if (current.isNotEmpty()) result.add(current.toString().trim())
        return result
    }

    /** Returns true when merging two fragments needs a space (Latin/punctuation context). */
    private fun needsSeparator(current: StringBuilder, next: String): Boolean {
        val lastChar = current.lastOrNull() ?: return false
        if (lastChar == ' ') return false
        val firstChar = next.firstOrNull() ?: return false
        if (firstChar == ' ') return false
        // CJK text has no inter-word spaces; everything else (Latin letters, punctuation) does.
        val lastIsCJK = lastChar.code in 0x4E00..0x9FFF
        val firstIsCJK = firstChar.code in 0x4E00..0x9FFF
        return !lastIsCJK && !firstIsCJK
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

    private fun mergeSentences(sentences: List<String>, maxChars: Int, latin: Boolean): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentSize = 0

        for (sentence in sentences) {
            val sentenceSize = size(sentence, latin)
            when {
                sentenceSize > maxChars -> {
                    if (current.isNotEmpty()) {
                        chunks += current.toString(); current.clear(); currentSize = 0
                    }
                    chunks += splitOversized(sentence, maxChars, latin)
                }
                current.isNotEmpty() && currentSize + sentenceSize > maxChars -> {
                    chunks += current.toString()
                    current.clear()
                    current.append(sentence)
                    currentSize = sentenceSize
                }
                else -> {
                    if (latin && current.isNotEmpty()) current.append(' ')
                    current.append(sentence)
                    currentSize += sentenceSize
                }
            }
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    private fun splitOversized(text: String, maxChars: Int, latin: Boolean): List<String> {
        // Try comma split first, respecting brackets
        val segments = splitAtCommasRespectingBrackets(text)
        if (segments.size > 1) return mergeSentences(segments, maxChars, latin)

        // Hard split: word boundaries for Latin, character boundaries for CJK
        if (latin) {
            val words = text.split(' ').filter { it.isNotBlank() }
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var currentSize = 0
            for (word in words) {
                if (currentSize > 0 && currentSize + LATIN_WORD_WEIGHT > maxChars) {
                    parts += current.toString()
                    current.clear()
                    currentSize = 0
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
                currentSize += LATIN_WORD_WEIGHT
            }
            if (current.isNotEmpty()) parts += current.toString()
            return parts
        }

        return (0 until text.length step maxChars)
            .map { text.substring(it, minOf(it + maxChars, text.length)) }
    }

    /**
     * Split [text] at comma-class delimiters, but skip any comma that is
     * inside a bracket pair — quoted or parenthesised content stays intact.
     */
    private fun splitAtCommasRespectingBrackets(text: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var cursor = 0
        for (i in text.indices) {
            val c = text[i]
            when {
                c in OPEN_BRACKETS  -> depth++
                c in CLOSE_BRACKETS -> if (depth > 0) depth--
                depth == 0 && c in COMMA_SET -> {
                    val segment = text.substring(cursor, i).trim()
                    if (segment.isNotBlank()) result.add(segment)
                    cursor = i + 1
                }
            }
        }
        val remaining = text.substring(cursor).trim()
        if (remaining.isNotBlank()) result.add(remaining)
        return result
    }
}
