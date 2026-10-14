package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.context.ContextConfig
import au.edu.unimelb.campuscompanion.context.ContextState
import au.edu.unimelb.campuscompanion.context.FakeBuildingLookup
import au.edu.unimelb.campuscompanion.context.TravelEngine
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.remote.GroupSummaryRow
import au.edu.unimelb.campuscompanion.data.remote.toModel
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.GroupOrigin
import au.edu.unimelb.campuscompanion.ui.model.HomeCardUiState
import au.edu.unimelb.campuscompanion.ui.model.SessionStatus
import au.edu.unimelb.campuscompanion.ui.model.buildHomeCardState
import au.edu.unimelb.campuscompanion.ui.model.buildHomeGroupUpdates
import au.edu.unimelb.campuscompanion.ui.model.homePostClassCandidates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Exercises the same parse/store/reload/engine/Home path as the app-owned TimetableSession. */
class PostClassTimetableIntegrationTest {
    private val zone = ZoneId.of("Australia/Melbourne")
    private val end = ZonedDateTime.of(2026, 10, 13, 16, 0, 0, 0, zone)
    private var now = end.minusMinutes(10).toInstant()
    private val repository = MemoryTimetableRepository()
    private val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val parser = IcsTimetableParser()
    private val url = "https://calendar.example/alice/private.ics"
    private val finishedFeed = calendar(event())
    private val joinedGroups = listOf(membership("other", "SWEN90014"), membership("finished", "COMP90018"))

    @Test
    fun finishedCourseUpdatesSurviveRecreationRefreshAndOfflineRestartThenExpireExactly() = runBlocking<Unit> {
        val connected = session(download = { Result.success(finishedFeed.toByteArray()) })
        connected.connect(url).getOrThrow()
        val occurrence = connected.state.value.sessions.single()
        val slotKey = occurrence.timetableGroupSpec()!!.key
        assertTrue(connected.state.value.groups.any { it.timetableKey == slotKey })
        assertEquals(ContextState.IN_CLASS, home(connected).state)

        now = end.plusMinutes(4).toInstant()
        val recreated = session()
        assertTrue(recreated.state.value.isCached)
        assertFalse(recreated.state.value.isLoading)
        assertEquals(SessionStatus.Finished, recreated.state.value.sessions.single().statusAt(now.atZone(zone)))
        assertPostClass(home(recreated))

        // A normal successful download after the end must preserve the occurrence as well.
        now = end.plusMinutes(9).toInstant()
        val refreshing = session(download = { Result.success(finishedFeed.toByteArray()) })
        refreshing.refresh().getOrThrow()
        assertFalse(refreshing.state.value.isCached)
        assertEquals(occurrence.id, refreshing.state.value.sessions.single().id)
        assertFalse(refreshing.state.value.groups.any { it.timetableKey == slotKey })
        assertPostClass(home(refreshing))
        assertEquals(occurrence.id, repository.rows.getValue("alice").imported.single().id)

        // Recreate again with no network, including a display-zone change.
        now = end.plusMinutes(ContextConfig().postClassMinutes.toLong()).minusNanos(1).toInstant()
        val offline = session(displayZone = ZoneId.of("UTC"))
        val savedRows = repository.rows.toMap()
        assertTrue(offline.refresh().isFailure)
        assertTrue(offline.state.value.isCached)
        assertEquals("Offline", offline.state.value.errorMessage)
        assertEquals(savedRows, repository.rows)
        assertEquals(ZoneId.of("UTC"), offline.state.value.sessions.single().start.zone)
        val engine = engine()
        assertPostClass(home(offline, engine))

        // The engine expires the card on its tick even before another cache load or refresh.
        now = end.plusMinutes(ContextConfig().postClassMinutes.toLong()).toInstant()
        val expired = home(offline, engine)
        assertEquals(ContextState.NO_UPCOMING_CLASS, expired.state)
        assertNull(expired.finishedSession)
        assertNull(updates(expired).featuredGroup)
        assertNull(updates(expired).contextLabel)
        assertTrue(session().state.value.sessions.isEmpty())
        assertTrue(parse(finishedFeed).sessions.isEmpty())
    }

    @Test
    fun postClassCacheAndFeaturedMembershipRemainScopedToTheAccountAndRemoval() = runBlocking<Unit> {
        now = end.plusMinutes(5).toInstant()
        session(download = { Result.success(finishedFeed.toByteArray()) }).connect(url).getOrThrow()
        val bob = session(userId = "bob", download = {
            Result.success(calendar(event(code = "SWEN90014", uid = "bob-workshop")).toByteArray())
        })
        bob.connect("https://calendar.example/bob/private.ics").getOrThrow()

        val engine = engine()
        val alice = session()
        assertPostClass(home(alice, engine))
        val staleAlice = engine.snapshot.value
        val restoredBob = session(userId = "bob")
        engine.updateSessions(restoredBob.state.value.sessions, reset = true)
        engine.refresh()
        val bobCard = buildHomeCardState(restoredBob.state.value.sessions, engine.snapshot.value, now.atZone(zone))
        assertEquals("SWEN90014", bobCard.finishedSession?.code)
        assertEquals("other", updates(bobCard).featuredGroup?.id)
        assertNull(buildHomeCardState(restoredBob.state.value.sessions, staleAlice, now.atZone(zone)).finishedSession)
        assertFalse(session(userId = "charlie").state.value.isConnected)

        // Clearing Alice immediately invalidates her finished occurrence, including an old snapshot.
        alice.clear()
        assertNull(buildHomeCardState(alice.state.value.sessions, staleAlice, now.atZone(zone)).finishedSession)
        assertTrue(session().state.value.sessions.isEmpty())
        assertFalse(session().state.value.isConnected)
        assertEquals("SWEN90014", session(userId = "bob").state.value.sessions.single().code)
    }

    @Test
    fun editedFeedCannotKeepFeaturingTheOldFinishedOccurrence() = runBlocking<Unit> {
        now = end.plusMinutes(5).toInstant()
        var feed = finishedFeed
        val connected = session(download = { Result.success(feed.toByteArray()) })
        connected.connect(url).getOrThrow()
        val engine = engine()
        assertPostClass(home(connected, engine))
        val oldSnapshot = engine.snapshot.value

        // The same feed UID now ends after the current time: it is no longer a finished class.
        feed = calendar(event(endTime = "170000"))
        connected.refresh().getOrThrow()
        val corrected = connected.state.value.sessions.single()
        assertEquals(oldSnapshot.finishedSession?.id, corrected.id)
        val pending = buildHomeCardState(connected.state.value.sessions, oldSnapshot, now.atZone(zone))
        assertTrue(pending.isUpdating)
        assertNull(pending.finishedSession)
        assertNull(updates(pending).featuredGroup)

        val current = home(connected, engine)
        assertEquals(ContextState.IN_CLASS, current.state)
        assertNull(current.finishedSession)
        val recreated = session()
        assertEquals(corrected.end.toInstant(), recreated.state.value.sessions.single().end.toInstant())
        assertNull(home(recreated).finishedSession)
    }

    @Test
    fun urgentNextClassGuidanceTakesPrecedenceWhileTheFinishedCourseStillHasUpdates() = runBlocking<Unit> {
        now = end.plusMinutes(5).toInstant()
        for ((nextStart, expectedState) in listOf(
            "163000" to ContextState.LEAVE_SOON,
            "161000" to ContextState.RUNNING_LATE
        )) {
            val connected = session(download = {
                Result.success(calendar(event(), event(
                    uid = "next-workshop", code = "SWEN90014", startTime = nextStart,
                    endTime = "173000", location = "PAR-104"
                )).toByteArray())
            })
            connected.connect(url).getOrThrow()
            val engine = engine()
            engine.updateOrigin(GeoPoint(-37.8100, 144.9628))
            val card = home(connected, engine)

            assertEquals(expectedState, card.state)
            assertEquals("SWEN90014", card.session?.code)
            assertEquals(15, card.estimate?.durationMinutes)
            assertPostClassUpdates(card)
        }
    }

    /** A new session per "process start": the shared repository plays the role of Room. */
    private suspend fun session(
        userId: String = "alice",
        displayZone: ZoneId = zone,
        download: suspend (String) -> Result<ByteArray> = { Result.failure(IOException("Offline")) }
    ): TimetableSession = TimetableSession(
        userId, repository, parent, download,
        parse = { bytes, retained -> Result.success(parser.parse(bytes, now.atZone(zone), zone, retained)) },
        lifecycleDispatcher = Dispatchers.Unconfined, clock = { now }, displayZone = { displayZone }
    ).also { it.initialize().getOrThrow() }

    private fun engine() = TravelEngine(
        eta = object : EtaRepository {
            override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode) =
                Result.success(TravelEstimate(mode, 15 * 60L, 1_500, EstimateSource.Routing, now))
        },
        buildings = FakeBuildingLookup(), preferences = { TravelPreferences() }, clock = { now }
    ).also { it.updateLeadMinutes(10) }

    private suspend fun home(session: TimetableSession, engine: TravelEngine = engine()): HomeCardUiState {
        engine.updateSessions(session.state.value.sessions)
        engine.refresh()
        return buildHomeCardState(session.state.value.sessions, engine.snapshot.value, now.atZone(zone))
    }

    private fun updates(card: HomeCardUiState) = buildHomeGroupUpdates(
        card.finishedSession, joinedGroups, homePostClassCandidates(joinedGroups, emptyMap())
    )

    private fun assertPostClass(card: HomeCardUiState) {
        assertEquals(ContextState.POST_CLASS, card.state)
        assertNull(card.session)
        assertPostClassUpdates(card)
    }

    private fun assertPostClassUpdates(card: HomeCardUiState) {
        assertEquals("COMP90018", card.finishedSession?.code)
        val updates = updates(card)
        val featured = updates.featuredGroup!!
        assertEquals("finished", featured.id)
        assertEquals(27, featured.members)
        assertEquals(4, featured.unreadCount)
        assertEquals("Upload your completed worksheet.", featured.latestMessage)
        assertEquals("lab-notes.pdf", featured.latestFileName)
        assertNull(featured.timetableSpec)
        assertEquals("After COMP90018", updates.contextLabel)
        assertEquals(listOf("finished", "other"), updates.rows.map { it.id })
    }

    /** Joined server summaries carry real unread/file data, unlike parser/cache placeholders. */
    private fun membership(id: String, code: String): CourseGroup {
        val summary = GroupSummaryRow(
            id = id, name = "$code course group", courseCode = code,
            createdAt = "2026-09-01T00:00:00Z", myRole = "member", memberCount = 27,
            unreadCount = 4, latestMessagePreview = "Upload your completed worksheet.",
            latestActivityAt = "2026-10-13T05:02:00Z", latestFileName = "lab-notes.pdf",
            timetableKey = "$code|course"
        ).toModel()
        return CourseGroup(
            id = summary.group.id, courseCode = summary.group.courseCode.orEmpty(), name = summary.group.name,
            members = summary.memberCount, unreadCount = summary.unreadCount,
            latestMessage = summary.latestMessagePreview!!, latestFileName = summary.latestFileName,
            origin = GroupOrigin.Timetable, timetableKey = summary.group.timetableKey
        )
    }

    private fun parse(feed: String) = parser.parse(feed.toByteArray(), now.atZone(zone), zone)

    private class MemoryTimetableRepository : TimetableRepository {
        val rows = mutableMapOf<String, TimetableSnapshot>()

        override suspend fun load(ownerId: String): TimetableSnapshot = rows[ownerId] ?: TimetableSnapshot()

        override suspend fun replaceImport(ownerId: String, url: String, imported: TimetableImport, syncedAt: Instant): TimetableSnapshot {
            val before = rows[ownerId] ?: TimetableSnapshot()
            val local = if (before.source?.url == url) before.local else before.local.filter { it.kind == LocalClassKind.MANUAL }
            val source = TimetableSource(url, syncedAt, imported.sourceEventCount, imported.groups.mapNotNull { it.timetableSpec })
            return TimetableSnapshot(source, imported.sessions, local).also { rows[ownerId] = it }
        }

        override suspend fun disconnect(ownerId: String): TimetableSnapshot =
            TimetableSnapshot(local = rows[ownerId]?.local.orEmpty().filter { it.kind == LocalClassKind.MANUAL })
                .also { rows[ownerId] = it }

        override suspend fun upsertLocal(ownerId: String, row: LocalClass): TimetableSnapshot {
            val before = rows[ownerId] ?: TimetableSnapshot()
            return before.copy(local = before.local.filterNot { it.id == row.id } + row).also { rows[ownerId] = it }
        }

        override suspend fun deleteLocal(ownerId: String, id: String): TimetableSnapshot {
            val before = rows[ownerId] ?: TimetableSnapshot()
            return before.copy(local = before.local.filterNot { it.id == id }).also { rows[ownerId] = it }
        }
    }

    private fun calendar(vararg events: String): String =
        ("BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Campus Companion//Post-Class Test//EN\n" +
            events.joinToString("\n") + "\nEND:VCALENDAR").replace("\n", "\r\n")

    private fun event(
        uid: String = "finished-tutorial",
        code: String = "COMP90018",
        startTime: String = "150000",
        endTime: String = "160000",
        location: String = "PAR-160"
    ): String = """
        BEGIN:VEVENT
        UID:$uid
        DTSTAMP:20261001T000000Z
        DTSTART;TZID=Australia/Melbourne:20261013T$startTime
        DTEND;TZID=Australia/Melbourne:20261013T$endTime
        SUMMARY:$code - Mobile Computing - Tutorial
        LOCATION:Parkville Campus\, $location
        END:VEVENT
    """.trimIndent()
}
