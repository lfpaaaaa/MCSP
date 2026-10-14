package au.edu.unimelb.campuscompanion.ui.model

import org.junit.Assert.*
import org.junit.Test

class TranscriptSearchTest {
    @Test fun literalSearchIgnoresCaseAndFindsAllNonOverlappingOccurrences() {
        assertEquals(listOf(0..3, 12..15), transcriptMatches("Word, other WORD.", "word"))
        assertEquals(listOf(0..2, 4..6), transcriptMatches("a.b A.B aab", "a.b"))
        assertEquals(listOf(0..1, 2..3), transcriptMatches("aaaaa", "aa"))
    }
    @Test fun emptyQueriesAndNoMatchesAreSafe() {
        assertTrue(transcriptMatches("hello", "  ").isEmpty())
        assertTrue(transcriptMatches("", "hello").isEmpty())
        assertTrue(transcriptMatches("hi", "hello").isEmpty())
    }
    @Test fun emojiAndPunctuationKeepTheirOriginalOffsets() {
        val text = "Hi 😀 (test) Hi"
        val ranges = transcriptMatches(text, "Hi")
        assertEquals(2, ranges.size)
        assertTrue(ranges.all { text.substring(it) == "Hi" })
        assertEquals(listOf(3..4), transcriptMatches(text, "😀"))
        assertEquals(listOf(6..11), transcriptMatches(text, "(test)"))
    }
    @Test fun longTranscriptChunksReconstructExactlyWithoutSplittingEmoji() {
        val text = ("😀 multiple words\nSecond line\n".repeat(10_000)).take(MAX_TRANSCRIPT_LENGTH)
        val chunks = transcriptChunks(text, 31)
        assertEquals(text, chunks.joinToString("") { it.text })
        assertTrue(chunks.all { it.text.length <= 31 && it.text.isNotEmpty() })
        assertTrue(chunks.dropLast(1).none { it.text.last().isHighSurrogate() })
        assertTrue(chunks.zipWithNext().all { (first, second) -> first.end == second.start })
    }
    @Test fun aMatchAcrossAChunkBoundaryHighlightsBothChunks() {
        val chunks = transcriptChunks("abcdefghijkl", 4)
        val matches = transcriptMatches("abcdefghijkl", "def")
        assertEquals(matches, chunks[0].intersectingMatches(matches))
        assertEquals(matches, chunks[1].intersectingMatches(matches))
        assertTrue(chunks[2].intersectingMatches(matches).isEmpty())
    }
    @Test fun maximumLengthTextWithManyHitsIsNotTruncated() {
        val text = "a".repeat(MAX_TRANSCRIPT_LENGTH)
        val chunks = transcriptChunks(text)
        val matches = transcriptMatches(text, "a")
        assertEquals(MAX_TRANSCRIPT_LENGTH, matches.size)
        assertEquals(MAX_TRANSCRIPT_LENGTH, chunks.sumOf { it.intersectingMatches(matches).size })
        assertEquals(MAX_TRANSCRIPT_LENGTH - 1, matches.last().first)
    }
}
