package au.edu.unimelb.campuscompanion.data.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class EpochMicrosTest {

    @Test
    fun microsecondsSurviveARoundTrip() {
        val instant = Instant.parse("2026-09-25T05:58:02.449382Z")

        assertEquals(1_790_315_882_449_382L, instant.toEpochMicros())
        assertEquals(instant, instantOfEpochMicros(instant.toEpochMicros()))
    }

    @Test
    fun instantsBeforeTheEpochAreSupported() {
        val instant = Instant.parse("1969-12-31T23:59:59.999999Z")

        assertEquals(-1L, instant.toEpochMicros())
        assertEquals(instant, instantOfEpochMicros(-1L))
    }
}
