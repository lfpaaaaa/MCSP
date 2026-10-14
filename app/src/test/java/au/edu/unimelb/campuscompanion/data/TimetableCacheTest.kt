package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.data.model.TimetableGroupSpec
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.RouteEstimate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Legacy-format checks remain independent of the Room-backed account session. */
class TimetableCacheTest {
    private val disk = mutableMapOf<String, String>()
    private val now = Instant.parse("2026-10-10T01:00:00Z")
    private val zone = ZoneId.of("Australia/Melbourne")
    private val url = "https://calendar.example/private-calendar"
    private fun store() = TimetableSubscriptionStore(
        read = disk::get,
        confirmedCleanup = { keys -> keys.forEach(disk::remove); true }
    )
    private fun seedLegacy(ownerId: String, sourceUrl: String, imported: TimetableImport, savedAt: Instant) {
        disk["subscription_url_$ownerId"] = sourceUrl
        disk["snapshot_$ownerId"] = TimetableCache.encode(sourceUrl, imported, savedAt)
    }
    private fun timetable(code: String = "COMP90018"): TimetableImport {
        val start = ZonedDateTime.parse("2026-10-12T09:00:00+11:00[Australia/Melbourne]")
        val tutorial = CourseSession("tutorial", code, "Mobile Computing", "Parkville", "PAR-160",
            start, start.plusHours(1), activity = "tutorial")
        val workshop = tutorial.copy(id = "workshop", activity = "workshop", start = start.plusDays(1), end = start.plusDays(1).plusHours(2))
        val specs = listOf(TimetableGroupSpec(code, "Mobile Computing", "course"),
            tutorial.timetableGroupSpec()!!, workshop.timetableGroupSpec()!!)
        return TimetableImport(listOf(tutorial, workshop), specs.map { it.toTimetableGroup() }, 2)
    }

    @Test fun legacyRoundTripPreservesIdsRoomsGroupsAndMetadata() {
        val original = timetable()
        seedLegacy("alice", url, original, now.minusSeconds(60))
        val restored = store().readLegacy("alice")
        assertEquals(url, restored.url)
        assertEquals(original, restored.timetable)
        assertEquals(now.minusSeconds(60), restored.savedAt)
    }

    @Test fun rawLegacyReadIncludesExpiredClassesForMigration() {
        val expired = timetable().sessions.first().copy(id = "legacy-id-never-regenerated",
            start = now.minusSeconds(7200).atZone(zone), end = now.minusSeconds(3600).atZone(zone))
        seedLegacy("alice", url, timetable().copy(sessions = listOf(expired)), now)
        assertEquals(listOf(expired), store().readLegacy("alice").timetable!!.sessions)
    }

    @Test fun accountKeysAreIsolatedAndCleanupRemovesOnlyTheRequestedOwner() = runBlocking {
        seedLegacy("alice", url, timetable(), now)
        seedLegacy("bob", "https://calendar.example/bob", timetable("SWEN90006"), now)
        assertEquals(SavedTimetable(""), store().readLegacy("charlie"))
        assertTrue(store().clearLegacy("alice"))
        assertEquals(SavedTimetable(""), store().readLegacy("alice"))
        assertEquals("SWEN90006", store().readLegacy("bob").timetable!!.sessions.first().code)
    }

    @Test fun corruptUnsupportedOrMismatchedCacheFallsBackToSavedUrl() {
        disk["subscription_url_alice"] = url
        val encoded = TimetableCache.encode(url, timetable(), now)
        for (invalid in listOf("not json", encoded.replace("\"version\":1", "\"version\":99"),
            encoded.replace("2026-10-12T09:00", "invalid-date"),
            TimetableCache.encode("https://calendar.example/different", timetable(), now))) {
            disk["snapshot_alice"] = invalid
            assertEquals(SavedTimetable(url), store().readLegacy("alice"))
        }
    }

    @Test fun legacyUrlWithoutCacheRetainsAnUnsyncedUrl() {
        disk["subscription_url_alice"] = url
        assertEquals(SavedTimetable(url), store().readLegacy("alice"))
    }

    @Test fun emptySuccessfulCalendarStillRoundTripsWithItsTimestamp() {
        val empty = TimetableImport(emptyList(), emptyList(), 0)
        seedLegacy("alice", url, empty, now)
        assertEquals(SavedTimetable(url, empty, now), store().readLegacy("alice"))
    }

    @Test fun displayFiltersExpiredClassesClearsEstimatesAndConvertsTimeZone() {
        val original = timetable()
        val expired = original.sessions.first().copy(id = "old", start = now.minusSeconds(7200).atZone(zone),
            end = now.minusSeconds(3600).atZone(zone))
        val upcoming = original.sessions.first().copy(etaMinutes = 12, routeEstimate = RouteEstimate(900, walkingMinutes = 12))
        seedLegacy("alice", url, original.copy(sessions = listOf(expired, upcoming)), now)
        val restored = store().readLegacy("alice").timetable!!.forDisplay(now, ZoneId.of("UTC"))
        val actual = restored.sessions.single()
        assertEquals(upcoming.start.toInstant(), actual.start.toInstant())
        assertEquals(ZoneId.of("UTC"), actual.start.zone)
        assertEquals(upcoming.timetableGroupSpec(), actual.timetableGroupSpec())
        assertNull(actual.etaMinutes)
        assertNull(actual.routeEstimate)
    }
}
