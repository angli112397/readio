package com.example.readio.domain.service

object TextChunker {

    /**
     * Groups raw EPUB paragraph texts into chunks of at most [maxChars] characters.
     * Algorithm: fill by sentence boundary → fall back to comma → hard-split.
     * Splitting rules are defined in [PunctuationTable].
     */
    fun chunk(rawTexts: List<String>, maxChars: Int): List<String> {
        if (maxChars <= 0 || rawTexts.isEmpty()) return rawTexts.filter { it.isNotBlank() }

        val sentences = rawTexts.flatMap { splitSentences(it) }
        return mergeSentences(sentences, maxChars)
    }

    private fun splitSentences(text: String): List<String> {
        val pattern = PunctuationTable.sentenceEndPattern
        val parts = pattern.split(text)
        val delimiters = pattern.findAll(text).map { it.value }.toList()
        return buildList {
            parts.forEachIndexed { i, part ->
                val sentence = (part + (delimiters.getOrElse(i) { "" })).trim()
                if (sentence.isNotBlank()) add(sentence)
            }
        }
    }

    private fun mergeSentences(sentences: List<String>, maxChars: Int): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        for (sentence in sentences) {
            if (sentence.length > maxChars) {
                if (current.isNotEmpty()) { chunks += current.toString(); current.clear() }
                chunks += splitOversized(sentence, maxChars)
            } else if (current.isNotEmpty() && current.length + sentence.length > maxChars) {
                chunks += current.toString()
                current.clear()
                current.append(sentence)
            } else {
                current.append(sentence)
            }
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    private fun splitOversized(text: String, maxChars: Int): List<String> {
        val segments = PunctuationTable.commaPattern.split(text).map { it.trim() }.filter { it.isNotBlank() }
        if (segments.size > 1) return mergeSentences(segments, maxChars)
        return (0 until text.length step maxChars).map { text.substring(it, minOf(it + maxChars, text.length)) }
    }
}
