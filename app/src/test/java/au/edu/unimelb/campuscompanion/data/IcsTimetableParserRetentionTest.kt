package au.edu.unimelb.campuscompanion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class IcsTimetableParserRetentionTest {
    private val melbourne = ZoneId.of("Australia/Melbourne")
    private val parser = IcsTimetableParser()
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    @Test
    fun retainsSameDayEndedOriginalWithoutRetainingOtherEndedClassesOrAddingSlotGroups() {
        val original = time(13, 9)
        val bytes = calendar(
            event("edited", original),
            event("other-ended", time(13, 8)),
            event("upcoming", time(13, 16))
        )
        val now = time(13, 10, 30)
        val ordinary = parse(bytes, now)
        val retained = parse(bytes, now, retained("edited", original))

        assertEquals(listOf(id("upcoming", time(13, 16))), ordinary.sessions.map { it.id })
        assertEquals(listOf(id("edited", original), id("upcoming", time(13, 16))),
            retained.sessions.map { it.id })
        assertEquals(ordinary.groups, retained.groups)
        assertEquals(ordinary.sourceEventCount, retained.sourceEventCount)
    }

    @Test
    fun thursdayRefreshFindsMondayOriginalExactlyAtWidenedBoundary() {
        val monday = time(12, 9)
        val bytes = calendar(
            event("weekly", monday, properties = "RRULE:FREQ=WEEKLY;COUNT=3"),
            event("unrelated", time(12, 8))
        )
        val now = time(15, 8)
        val ordinary = parse(bytes, now)
        val result = parse(bytes, now, retained("weekly", monday))

        assertEquals(2, ordinary.sessions.size)
        assertEquals(3, result.sessions.size)
        assertEquals(id("weekly", monday), result.sessions.first().id)
        assertEquals(monday.toInstant(), result.sessions.first().start.toInstant())
        assertEquals(ordinary.groups, result.groups)
        assertFalse(result.sessions.any { it.id.startsWith("unrelated@") })
    }

    @Test
    fun findsOlderOriginalOutsideNormalWindowWithAnchorInAnotherZone() {
        val original = ZonedDateTime.of(2026, 1, 12, 9, 0, 0, 0, melbourne)
        val bytes = calendar(event("old", original), event("upcoming", time(16, 9)))
        val now = time(15, 8)
        val ordinary = parse(bytes, now)
        val result = parse(bytes, now,
            RetainedOccurrence(id("old", original), original.withZoneSameInstant(ZoneId.of("UTC"))))

        assertEquals(listOf(id("old", original), id("upcoming", time(16, 9))), result.sessions.map { it.id })
        assertEquals(ordinary.groups, result.groups)
    }

    @Test
    fun retainedTargetBeyondFiveHundredDoesNotConsumeOrdinaryQuotaOrAddItsSlotGroup() {
        val first = time(13, 9)
        val target = time(13, 18)
        val bytes = calendar(
            event("ordinary", first, durationMinutes = 1,
                summary = "COMP90018 - Mobile Computing - Lecture",
                properties = "RRULE:FREQ=MINUTELY;COUNT=500"),
            event("target", target)
        )
        val now = time(13, 8)
        val ordinary = parse(bytes, now)
        val result = parse(bytes, now, retained("target", target))

        assertEquals(500, ordinary.sessions.size)
        assertFalse(ordinary.sessions.any { it.id == id("target", target) })
        assertEquals(501, result.sessions.size)
        assertEquals(ordinary.sessions, result.sessions.take(500))
        assertEquals(id("target", target), result.sessions.last().id)
        assertEquals(ordinary.groups, result.groups)
        assertEquals(2, result.sourceEventCount)
    }

    @Test
    fun retainedUpcomingTargetIsDeduplicatedAndExplicitEmptyRetentionKeepsDefaultOutput() {
        val original = time(13, 9)
        val bytes = calendar(event("weekly", original, properties = "RRULE:FREQ=WEEKLY;COUNT=3"))
        val now = time(13, 8)
        val normal = parse(bytes, now)
        val explicitEmpty = parser.parse(bytes, now, melbourne, emptyList())
        val result = parse(bytes, now, retained("weekly", original), retained("weekly", original))

        assertEquals(normal, explicitEmpty)
        assertEquals(normal, result)
    }

    @Test
    fun removedOrCancelledSourceDoesNotRetainOldOccurrence() {
        val original = time(12, 9)
        val now = time(15, 8)
        val retained = retained("edited", original)
        val removed = parse(calendar(), now, retained)
        val cancelled = parse(calendar(event("edited", original, properties = "STATUS:CANCELLED")),
            now, retained)

        assertTrue(removed.sessions.isEmpty())
        assertTrue(cancelled.sessions.isEmpty())
        assertEquals(0, cancelled.sourceEventCount)
    }

    @Test
    fun exdateOnRecurringSourceDoesNotRetainExcludedOriginal() {
        val original = time(12, 9)
        val now = time(15, 8)
        val bytes = calendar(event("weekly", original,
            properties = "RRULE:FREQ=WEEKLY;COUNT=3\nEXDATE;TZID=Australia/Melbourne:${original.format(stamp)}"))
        val ordinary = parse(bytes, now)
        val result = parse(bytes, now, retained("weekly", original))

        assertEquals(ordinary, result)
        assertEquals(2, result.sessions.size)
        assertFalse(result.sessions.any { it.id == id("weekly", original) })
    }

    @Test
    fun sourceRescheduleUsesNewIdAndDoesNotInventRequestedOldId() {
        val original = time(12, 9)
        val newStart = time(16, 9)
        val result = parse(calendar(event("edited", newStart)), time(15, 8), retained("edited", original))

        assertEquals(listOf(id("edited", newStart)), result.sessions.map { it.id })
        assertFalse(result.sessions.any { it.id == id("edited", original) })
    }

    @Test
    fun wideningDoesNotAddOldLongRdateToOrdinaryQuotaOrGroups() {
        val original = time(1, 9)
        val extra = time(10, 9)
        val bytes = calendar(event("monthly", original, durationMinutes = 7 * 24 * 60,
            properties = "RRULE:FREQ=MONTHLY;COUNT=3\n" +
                "RDATE;TZID=Australia/Melbourne:${extra.format(stamp)}"))
        val now = time(15, 8)
        val ordinary = parse(bytes, now)
        val result = parse(bytes, now, retained("monthly", original))

        assertEquals(2, ordinary.sessions.size)
        assertEquals(listOf(id("monthly", original)) + ordinary.sessions.map { it.id },
            result.sessions.map { it.id })
        assertEquals(ordinary.groups, result.groups)
        assertFalse(result.sessions.any { it.id == id("monthly", extra) })
        val retainedExtra = parse(bytes, now, retained("monthly", original), retained("monthly", extra))
        assertEquals(4, retainedExtra.sessions.size)
        assertEquals(ordinary.groups, retainedExtra.groups)
    }

    @Test
    fun cancelledRecurrenceExceptionSuppressesRequestedOriginalFromMasterExpansion() {
        val original = time(12, 9)
        val bytes = calendar(
            event("weekly", original, properties = "RRULE:FREQ=WEEKLY;COUNT=3"),
            event("weekly", original,
                properties = "RECURRENCE-ID;TZID=Australia/Melbourne:${original.format(stamp)}\nSTATUS:CANCELLED")
        )
        val now = time(15, 8)
        val ordinary = parse(bytes, now)
        val result = parse(bytes, now, retained("weekly", original))

        assertEquals(ordinary, result)
        assertEquals(2, result.sessions.size)
        assertFalse(result.sessions.any { it.id == id("weekly", original) })
    }

    @Test
    fun rescheduledRecurrenceExceptionSuppressesRequestedOldIdAndKeepsNewOccurrence() {
        val original = time(12, 9)
        val replacement = time(16, 9)
        val bytes = calendar(
            event("weekly", original, properties = "RRULE:FREQ=WEEKLY;COUNT=3"),
            event("weekly", replacement,
                properties = "RECURRENCE-ID;TZID=Australia/Melbourne:${original.format(stamp)}")
        )
        val now = time(15, 8)
        val ordinary = parse(bytes, now)
        val result = parse(bytes, now, retained("weekly", original))

        assertEquals(ordinary, result)
        assertEquals(3, result.sessions.size)
        assertEquals(id("weekly", replacement), result.sessions.first().id)
        assertFalse(result.sessions.any { it.id == id("weekly", original) })
    }

    @Test
    fun requestedUpcomingCancelledExceptionDoesNotEscapeThroughOrdinaryList() {
        val original = time(16, 9)
        val bytes = calendar(
            event("weekly", original, properties = "RRULE:FREQ=WEEKLY;COUNT=3"),
            event("weekly", original,
                properties = "RECURRENCE-ID;TZID=Australia/Melbourne:${original.format(stamp)}\nSTATUS:CANCELLED")
        )
        val now = time(15, 8)
        val ordinary = parse(bytes, now)
        val explicitEmpty = parser.parse(bytes, now, melbourne, emptyList())
        val result = parse(bytes, now, retained("weekly", original))

        assertEquals(ordinary, explicitEmpty)
        assertEquals(3, ordinary.sessions.size)
        assertEquals(2, result.sessions.size)
        assertEquals(ordinary.groups, result.groups)
        assertFalse(result.sessions.any { it.id == id("weekly", original) })
    }

    @Test
    fun floatingOriginalAtWidenedBoundaryIsRetainedWhenDeviceIsAheadOfMelbourne() {
        val deviceZone = ZoneId.of("Pacific/Kiritimati")
        val original = time(12, 9)
        val bytes = calendar(event("floating", original, properties = "RRULE:FREQ=WEEKLY;COUNT=3")
            .replace(";TZID=Australia/Melbourne", ""))
        val now = ZonedDateTime.of(2026, 10, 15, 8, 0, 0, 0, deviceZone)
        val ordinary = parser.parse(bytes, now, deviceZone)
        val result = parser.parse(bytes, now, deviceZone, listOf(retained("floating", original)))

        assertEquals(2, ordinary.sessions.size)
        assertEquals(3, result.sessions.size)
        assertEquals(id("floating", original), result.sessions.first().id)
        assertEquals(original.toInstant(), result.sessions.first().start.toInstant())
        assertEquals(deviceZone, result.sessions.first().start.zone)
        assertEquals(ordinary.groups, result.groups)
    }

    @Test
    fun floatingOriginalInsideNormalInstantWindowStillWidensDeviceWallClockBoundary() {
        val deviceZone = ZoneId.of("Pacific/Kiritimati")
        val original = time(14, 6)
        val bytes = calendar(event("floating", original).replace(";TZID=Australia/Melbourne", ""))
        val now = ZonedDateTime.of(2026, 10, 15, 8, 0, 0, 0, deviceZone)
        assertTrue(original.toInstant().isAfter(now.minusDays(1).toInstant()))
        val ordinary = parser.parse(bytes, now, deviceZone)
        val result = parser.parse(bytes, now, deviceZone, listOf(retained("floating", original)))

        assertTrue(ordinary.sessions.isEmpty())
        assertEquals(listOf(id("floating", original)), result.sessions.map { it.id })
        assertEquals(original.toInstant(), result.sessions.single().start.toInstant())
        assertEquals(ordinary.groups, result.groups)
        assertEquals(ordinary, parser.parse(bytes, now, deviceZone, emptyList()))
    }

    @Test
    fun widenedDuplicateOutsideNormalWindowCannotHideOrdinaryOccurrenceWithSameId() {
        val anchor = time(1, 9)
        val duplicateStart = time(12, 9)
        val bytes = calendar(
            event("retained", anchor),
            event("duplicate", duplicateStart),
            event("duplicate", duplicateStart, durationMinutes = 7 * 24 * 60)
        )
        val now = time(15, 8)
        val ordinary = parse(bytes, now)
        val result = parse(bytes, now, retained("retained", anchor))
        assertEquals(1, ordinary.sessions.size)
        assertEquals(ordinary.sessions, result.sessions.filterNot { it.id == id("retained", anchor) })
        assertEquals(ordinary.groups, result.groups)
    }

    private fun parse(bytes: ByteArray, now: ZonedDateTime, vararg retained: RetainedOccurrence) =
        parser.parse(bytes, now, melbourne, retained.toList())

    private fun retained(uid: String, start: ZonedDateTime) = RetainedOccurrence(id(uid, start), start)

    private fun id(uid: String, start: ZonedDateTime) = "$uid@${start.toInstant().toEpochMilli()}"

    private fun time(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, melbourne)

    private fun calendar(vararg events: String): ByteArray =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Campus Companion//Retention Test//EN") +
            events + "END:VCALENDAR").joinToString("\r\n").toByteArray()

    private fun event(
        uid: String,
        start: ZonedDateTime,
        durationMinutes: Long = 60,
        summary: String = "COMP90018 - Mobile Computing - Tutorial",
        properties: String = ""
    ): String = (listOf(
        "BEGIN:VEVENT",
        "UID:$uid",
        "DTSTAMP:20261001T000000Z",
        "DTSTART;TZID=Australia/Melbourne:${start.format(stamp)}",
        "DTEND;TZID=Australia/Melbourne:${start.plusMinutes(durationMinutes).format(stamp)}",
        "SUMMARY:$summary",
        "LOCATION:Parkville Campus\\, PAR-160"
    ) + properties.lines().filter(String::isNotBlank) + "END:VEVENT").joinToString("\r\n")
}
