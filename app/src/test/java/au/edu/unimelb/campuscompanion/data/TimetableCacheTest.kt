package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.data.model.TimetableGroupSpec
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.RouteEstimate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class TimetableCacheTest {
    private val disk = mutableMapOf<String, String>()
    private val now = Instant.parse("2026-10-10T01:00:00Z")
    private val zone = ZoneId.of("Australia/Melbourne")
    private val url = "https://calendar.example/private-calendar"
    private fun store() = TimetableSubscriptionStore(
        read = disk::get,
        write = { entries -> entries.forEach { (key, value) ->
            if (value == null) disk.remove(key) else disk[key] = value
        } }
    )
    private fun timetable(code: String = "COMP90018"): TimetableImport {
        val start = ZonedDateTime.parse("2026-10-12T09:00:00+11:00[Australia/Melbourne]")
        val tutorial = CourseSession("tutorial", code, "Mobile Computing", "Parkville", "PAR-160",
            start, start.plusHours(1), activity = "tutorial")
        val workshop = tutorial.copy(id = "workshop", activity = "workshop", start = start.plusDays(1), end = start.plusDays(1).plusHours(2))
        val specs = listOf(TimetableGroupSpec(code, "Mobile Computing", "course"),
            tutorial.timetableGroupSpec()!!, workshop.timetableGroupSpec()!!)
        return TimetableImport(listOf(tutorial, workshop), specs.map { spec ->
            CourseGroup(spec.key, spec.courseCode, spec.name, 0, 0,
                "Added from your connected timetable.", null, false,
                timetableKey = spec.key, timetableSlot = spec.slotLabel(), timetableSpec = spec)
        }, 2)
    }
    private fun session(
        user: String = "alice",
        download: suspend (String) -> Result<TimetableImport> = { Result.failure(IOException("Offline")) }
    ) = TimetableSession(user, store(), download, { now }, { zone })

    @Test fun restartRestoresClassesRoomsAndBothClassGroupTypesBeforeNetwork() {
        val original = timetable()
        store().save("alice", url, original, now.minusSeconds(60))
        val restored = session().state.value
        assertTrue(restored.isConnected)
        assertTrue(restored.isCached)
        assertTrue(restored.isLoading)
        assertEquals(original.sessions, restored.sessions)
        assertEquals(original.groups, restored.groups)
        assertEquals(now.minusSeconds(60), restored.lastSyncedAt)
        assertEquals(2, restored.detectedEventCount)
    }

    @Test fun failedRefreshPreservesDiskAndVisibleTimetable() = runBlocking<Unit> {
        store().save("alice", url, timetable(), now.minusSeconds(60))
        val before = disk.toMap()
        val session = session()
        assertTrue(session.refresh().isFailure)
        assertEquals(timetable().sessions, session.state.value.sessions)
        assertTrue(session.state.value.isConnected)
        assertTrue(session.state.value.isCached)
        assertFalse(session.state.value.isLoading)
        assertEquals("Offline", session.state.value.errorMessage)
        assertEquals(before, disk)
    }

    @Test fun successfulRefreshReplacesCacheAndClearsOfflineStatus() = runBlocking<Unit> {
        store().save("alice", url, timetable(), now.minusSeconds(60))
        val updated = timetable("SWEN90006")
        val session = session { Result.success(updated) }
        session.refresh().getOrThrow()
        assertEquals(updated.sessions, session.state.value.sessions)
        assertFalse(session.state.value.isCached)
        assertFalse(session.state.value.isLoading)
        assertNull(session.state.value.errorMessage)
        assertEquals(now, session.state.value.lastSyncedAt)
        assertEquals(updated, store().load("alice").timetable)
    }

    @Test fun failedReplacementKeepsPreviousSubscriptionAndCache() = runBlocking<Unit> {
        store().save("alice", url, timetable(), now)
        val session = session()
        assertTrue(session.connect("https://calendar.example/bad").isFailure)
        assertEquals(url, session.state.value.url)
        assertEquals(url, store().load("alice").url)
        assertEquals(timetable(), store().load("alice").timetable)
    }

    @Test fun successfulReplacementSavesNewSubscriptionAndSnapshotTogether() = runBlocking<Unit> {
        store().save("alice", url, timetable(), now)
        val replacement = timetable("SWEN90006")
        val session = session { Result.success(replacement) }
        session.connect(" https://calendar.example/new ").getOrThrow()
        assertEquals("https://calendar.example/new", store().load("alice").url)
        assertEquals(replacement, store().load("alice").timetable)
    }

    @Test fun cachesAreIsolatedByAccountAndRemovingOneDoesNotRemoveAnother() {
        store().save("alice", url, timetable(), now)
        store().save("bob", "https://calendar.example/bob", timetable("SWEN90006"), now)
        assertFalse(session("charlie").state.value.isConnected)
        assertEquals("SWEN90006", session("bob").state.value.sessions.first().code)
        session().clear()
        assertEquals(SavedTimetable(""), store().load("alice"))
        assertNotNull(store().load("bob").timetable)
    }

    @Test fun removingDuringDownloadCannotResurrectCache() = runBlocking<Unit> {
        store().save("alice", url, timetable(), now)
        val response = CompletableDeferred<Result<TimetableImport>>()
        val session = session { response.await() }
        val request = async(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
        session.clear()
        response.complete(Result.success(timetable()))
        assertTrue(request.await().isFailure)
        assertEquals("", session.state.value.url)
        assertFalse(session.state.value.isConnected)
        assertEquals(SavedTimetable(""), store().load("alice"))
    }

    @Test fun olderDownloadCannotOverwriteNewSubscription() = runBlocking<Unit> {
        store().save("alice", url, timetable(), now)
        val oldResponse = CompletableDeferred<Result<TimetableImport>>()
        val updated = timetable("SWEN90006")
        val session = session { requested ->
            if (requested == url) oldResponse.await() else Result.success(updated)
        }
        val request = async(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
        session.connect("https://calendar.example/new").getOrThrow()
        oldResponse.complete(Result.success(timetable()))
        request.await()
        assertEquals(updated, store().load("alice").timetable)
        assertEquals(updated.sessions, session.state.value.sessions)
    }

    @Test fun cancelledRefreshKeepsCacheAndStopsSpinner() = runBlocking<Unit> {
        store().save("alice", url, timetable(), now)
        val session = session { awaitCancellation() }
        val request = launch(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
        request.cancelAndJoin()
        assertFalse(session.state.value.isLoading)
        assertEquals(timetable(), store().load("alice").timetable)
        assertTrue(session.state.value.isConnected)
    }

    @Test fun corruptOrUnsupportedCacheFallsBackToSavedUrl() {
        disk["subscription_url_alice"] = url
        val encoded = TimetableCache.encode(url, timetable(), now)
        for (invalid in listOf("not json", encoded.replace("\"version\":1", "\"version\":99"),
            encoded.replace("2026-10-12T09:00", "invalid-date"))) {
            disk["snapshot_alice"] = invalid
            val state = session().state.value
            assertEquals(url, state.url)
            assertFalse(state.isConnected)
            assertTrue(state.sessions.isEmpty())
        }
        disk["snapshot_alice"] = TimetableCache.encode("https://calendar.example/different", timetable(), now)
        assertNull(store().load("alice").timetable)
    }

    @Test fun legacyUrlWithoutCacheStillRefreshesNormally() = runBlocking<Unit> {
        disk["subscription_url_alice"] = url
        val session = session { Result.success(timetable()) }
        assertFalse(session.state.value.isConnected)
        session.refresh().getOrThrow()
        assertNotNull(store().load("alice").timetable)
    }

    @Test fun emptySuccessfulCalendarIsStillCached() = runBlocking<Unit> {
        val empty = TimetableImport(emptyList(), emptyList(), 0)
        session { Result.success(empty) }.connect(url).getOrThrow()
        val restored = session().state.value
        assertTrue(restored.isConnected)
        assertTrue(restored.isCached)
        assertTrue(restored.sessions.isEmpty())
    }

    @Test fun expiredClassesAndLiveEstimatesAreNotRestoredAndTimeZoneIsUpdated() {
        val original = timetable()
        val expired = original.sessions.first().copy(id = "old", start = now.minusSeconds(7200).atZone(zone),
            end = now.minusSeconds(3600).atZone(zone))
        val upcoming = original.sessions.first().copy(etaMinutes = 12, routeEstimate = RouteEstimate(900, walkingMinutes = 12))
        store().save("alice", url, original.copy(sessions = listOf(expired, upcoming)), now)
        val restored = TimetableSession("alice", store(), { Result.failure(IOException()) }, { now }, { ZoneId.of("UTC") }).state.value
        assertEquals(1, restored.sessions.size)
        val actual = restored.sessions.single()
        assertEquals(upcoming.start.toInstant(), actual.start.toInstant())
        assertEquals(ZoneId.of("UTC"), actual.start.zone)
        assertEquals(upcoming.timetableGroupSpec(), actual.timetableGroupSpec())
        assertNull(actual.etaMinutes)
        assertNull(actual.routeEstimate)
    }
}
