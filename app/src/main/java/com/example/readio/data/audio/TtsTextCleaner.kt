package com.example.readio.data.audio

/**
 * Sanitize text before sending to any TTS engine (local or remote).
 *
 * Removes visual-only characters that TTS engines never vocalize, reducing
 * character count (= Volcengine cost) without changing audio output.
 *
 * Kept — all affect prosody or name pronunciation:
 *   。，！？；：、…   sentence/clause boundaries that drive pausing and rhythm
 *   ·               middle-dot for transliterated names (哈利·波特)
 *   Latin letters, digits — unchanged
 *
 * Used by:
 *   [AndroidTtsEngine]  — local synthesis (validates cleaning without burning quota)
 *   [VolcengineEngine]  — remote async download (reduces billed character count)
 */
internal fun cleanForTts(text: String): String {
    var s = text

    // 1. Collapse all whitespace (handles EPUB newlines, indentation, <br> artifacts)
    s = s.replace(Regex("""\s+"""), " ").trim()

    // 2. Remove spaces between adjacent CJK characters (EPUB formatting noise)
    s = s.replace(Regex("""(?<=[一-鿿぀-ヿ])\s+(?=[一-鿿぀-ヿ])"""), "")

    // 3. Delete visual-only symbols: CJK/western quotation marks, title marks,
    //    angle/square/round brackets (all forms), asterisk, underscore, em-dash,
    //    fullwidth hyphen
    s = s.replace(Regex("""[""''「」『』《》〈〉〔〕【】（）()\[\]{}\*_—－]"""), "")

    // 4. Deduplicate runs of the same pause/end punctuation (e.g. ，，→ ，  ！！→ ！)
    s = s.replace(Regex("""([，。？！；：,.?!;:、])\1+"""), "$1")

    return s
}
