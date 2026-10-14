package au.edu.unimelb.campuscompanion.ui.model

internal const val MAX_TRANSCRIPT_LENGTH = 200_000
internal const val MAX_TRANSCRIPT_QUERY_LENGTH = 200

internal data class TranscriptChunk(val start: Int, val text: String) {
    val end: Int get() = start + text.length
}

/** Keep long transcripts virtualized without losing spaces, line breaks or UTF-16 offsets. */
internal fun transcriptChunks(text: String, maxLength: Int = 1200): List<TranscriptChunk> {
    require(maxLength >= 2)
    val chunks = mutableListOf<TranscriptChunk>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + maxLength, text.length)
        if (end < text.length) {
            val lineBreak = text.lastIndexOf('\n', end - 1)
            val space = text.lastIndexOf(' ', end - 1)
            val boundary = if (lineBreak >= start + maxLength / 2) lineBreak else space
            if (boundary >= start + maxLength / 2) end = boundary + 1
            if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        }
        chunks += TranscriptChunk(start, text.substring(start, end))
        start = end
    }
    return chunks
}

/** Literal, case-insensitive search. Query punctuation is never interpreted as a regex. */
internal fun transcriptMatches(text: String, query: String): List<IntRange> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    val matches = mutableListOf<IntRange>()
    var from = 0
    while (from <= text.length - needle.length) {
        val position = text.indexOf(needle, startIndex = from, ignoreCase = true)
        if (position < 0) break
        matches += position until position + needle.length
        from = position + needle.length
    }
    return matches
}

/** Locate intersections efficiently even when a one-letter query has thousands of matches. */
internal fun TranscriptChunk.intersectingMatches(matches: List<IntRange>): List<IntRange> {
    var low = 0
    var high = matches.size
    while (low < high) {
        val mid = (low + high) / 2
        if (matches[mid].last < start) low = mid + 1 else high = mid
    }
    val result = mutableListOf<IntRange>()
    while (low < matches.size && matches[low].first < end) result += matches[low++]
    return result
}
