package au.edu.unimelb.campuscompanion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class TimetableCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val url = "https://timetable.example.edu/private/abc.ics"
    private val calendar = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n".toByteArray()

    @Test
    fun aSavedCalendarComesBackWithItsUrlAndTime() {
        val cache = TimetableCache(File(folder.root, "timetable"))
        val savedAt = Instant.parse("2026-10-09T11:01:00Z")

        cache.save("user-1", url, calendar, savedAt)
        val entry = cache.load("user-1")!!

        assertEquals(url, entry.url)
        assertEquals(savedAt, entry.savedAt)
        assertArrayEquals(calendar, entry.bytes)
    }

    @Test
    fun eachUserHasTheirOwnCopyAndClearingRemovesIt() {
        val cache = TimetableCache(File(folder.root, "timetable"))
        cache.save("user-1", url, calendar)
        cache.save("user-2", "https://other.example.edu/x.ics", "BEGIN:VCALENDAR".toByteArray())

        cache.clear("user-1")

        assertNull(cache.load("user-1"))
        assertEquals("https://other.example.edu/x.ics", cache.load("user-2")?.url)
    }

    @Test
    fun aMissingOrDamagedCopyIsIgnored() {
        val directory = File(folder.root, "timetable")
        val cache = TimetableCache(directory)
        assertNull(cache.load("nobody"))

        cache.save("user-1", url, calendar)
        val metadata = directory.listFiles { file -> file.name.endsWith(".meta") }!!.single()
        metadata.writeText("$url\nnot-a-time\n")

        assertNull(cache.load("user-1"))
    }

    @Test
    fun savingAgainReplacesTheCalendar() {
        val cache = TimetableCache(File(folder.root, "timetable"))
        cache.save("user-1", url, calendar)

        val updated = "BEGIN:VCALENDAR\r\nX-UPDATED:1\r\nEND:VCALENDAR\r\n".toByteArray()
        cache.save("user-1", url, updated)

        assertArrayEquals(updated, cache.load("user-1")!!.bytes)
        assertTrue(File(folder.root, "timetable").listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
