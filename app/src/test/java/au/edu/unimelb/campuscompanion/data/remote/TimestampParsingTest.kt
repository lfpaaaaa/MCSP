package au.edu.unimelb.campuscompanion.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TimestampParsingTest {
    private val expected = Instant.parse("2026-09-25T05:58:02.449382Z")

    @Test
    fun postgrestTimestampsAreParsed() {
        assertEquals(expected, parseTimestamp("2026-09-25T05:58:02.449382+00:00"))
    }

    @Test
    fun postgresTextTimestampsAreParsed() {
        assertEquals(expected, parseTimestamp("2026-09-25 05:58:02.449382+00"))
    }

    @Test
    fun otherOffsetsAreConvertedToUtc() {
        assertEquals(expected, parseTimestamp("2026-09-25T15:58:02.449382+10:00"))
        assertEquals(Instant.parse("2026-09-25T05:58:02Z"), parseTimestamp("2026-09-25T05:58:02Z"))
    }
}
