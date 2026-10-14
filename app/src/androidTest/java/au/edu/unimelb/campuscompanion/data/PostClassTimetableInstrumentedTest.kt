package au.edu.unimelb.campuscompanion.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.edu.unimelb.campuscompanion.context.ContextEngine
import au.edu.unimelb.campuscompanion.context.ContextInputs
import au.edu.unimelb.campuscompanion.context.ContextState
import au.edu.unimelb.campuscompanion.context.TravelSnapshot
import au.edu.unimelb.campuscompanion.data.local.TimetableDatabase
import au.edu.unimelb.campuscompanion.data.remote.GroupSummaryRow
import au.edu.unimelb.campuscompanion.data.remote.toModel
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.GroupChatPreferences
import au.edu.unimelb.campuscompanion.ui.model.GroupOrigin
import au.edu.unimelb.campuscompanion.ui.model.HomeGroupUpdates
import au.edu.unimelb.campuscompanion.ui.model.SessionStatus
import au.edu.unimelb.campuscompanion.ui.model.TimetableState
import au.edu.unimelb.campuscompanion.ui.model.buildHomeCardState
import au.edu.unimelb.campuscompanion.ui.model.buildHomeGroupUpdates
import au.edu.unimelb.campuscompanion.ui.model.buildSchedulePage
import au.edu.unimelb.campuscompanion.ui.model.homePostClassCandidates
import au.edu.unimelb.campuscompanion.ui.model.isFolded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Exercises the reconstruction performed by the app-owned TimetableSession over Android's real
 * Room storage. It does not replace authentication, launch/recreate MainActivity, or contact a
 * backend; group updates below are repository-shaped response fixtures.
 */
@RunWith(AndroidJUnit4::class)
class PostClassTimetableInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val zone = ZoneId.of("Australia/Melbourne")
    private val end = ZonedDateTime.of(2026, 10, 13, 16, 0, 0, 0, zone)
    private val parent = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var database: TimetableDatabase
    private lateinit var preferences: SharedPreferences
    private lateinit var repository: RoomTimetableRepository

    @Before fun createStorage() {
        database = Room.inMemoryDatabaseBuilder(context, TimetableDatabase::class.java).build()
        preferences = context.getSharedPreferences("post-class-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        repository = RoomTimetableRepository(database, TimetableSubscriptionStore(preferences))
    }

    @After fun closeStorage() {
        parent.cancel()
        database.close()
        preferences.edit().clear().commit()
    }

    @Test
    fun parserAndRoomStorageKeepFinishedCourseUpdatesThroughReloadRefreshAndOfflineRestart() =
        runBlocking<Unit>(Dispatchers.Main) {
            val account = "post-class-android-${UUID.randomUUID()}"
            val otherAccount = "post-class-android-${UUID.randomUUID()}"
            val url = "https://calendar.example.invalid/$account"
            var now = end.minusMinutes(1)
            val initial = session(account, now = { now }) { Result.success(calendar()) }
            initial.connect(url).getOrThrow()
            val occurrenceId = initial.state.value.sessions.single().id
            val initialGroupKeys = initial.state.value.groups.map(CourseGroup::timetableKey)
            assertTrue(initialGroupKeys.contains("COMP90018|course"))
            assertEquals(2, initialGroupKeys.size) // Course and the still-upcoming tutorial slot.

            // A separate account has its own URL and upcoming course throughout this journey.
            val otherUrl = "https://calendar.example.invalid/$otherAccount"
            repository.replaceImport(otherAccount, otherUrl, parse(now, "SWEN90014", end.plusHours(2)), now.toInstant())

            now = end.plusMinutes(5)
            val recreated = session(account, now = { now }) { Result.success(calendar()) }
            assertTrue(recreated.state.value.isCached)
            assertFalse(recreated.state.value.isLoading)
            assertEquals(occurrenceId, recreated.state.value.sessions.single().id)
            assertPostClassUpdates(recreated.state.value, now)

            now = end.plusMinutes(6)
            recreated.refresh().getOrThrow()
            assertFalse(recreated.state.value.isCached)
            assertFalse(recreated.state.value.isLoading)
            assertEquals(occurrenceId, recreated.state.value.sessions.single().id)
            assertEquals(listOf("COMP90018|course"), recreated.state.value.groups.map(CourseGroup::timetableKey))
            assertPostClassUpdates(recreated.state.value, now)

            // Construct another owner just as an offline app restart reloads storage.
            now = end.plusMinutes(7)
            val offline = session(account, now = { now }) { Result.failure(IOException("Offline test")) }
            val savedBeforeFailure = repository.load(account)
            assertEquals(occurrenceId, offline.state.value.sessions.single().id)
            assertTrue(offline.refresh().isFailure)
            assertTrue(offline.state.value.isCached)
            assertFalse(offline.state.value.isLoading)
            assertNotNull(offline.state.value.offlineNotice(zone))
            assertEquals(savedBeforeFailure, repository.load(account))
            assertPostClassUpdates(offline.state.value, now)

            val isolated = session(otherAccount, now = { now }) { Result.failure(IOException("Offline test")) }
            assertEquals(otherUrl, isolated.state.value.url)
            assertEquals("SWEN90014", isolated.state.value.sessions.single().code)
            assertEquals(SessionStatus.Upcoming, isolated.state.value.sessions.single().statusAt(now))
            assertFalse(session("post-class-unused-${UUID.randomUUID()}", { now }) {
                Result.failure(IOException("Offline test"))
            }.state.value.isConnected)

            offline.clear().getOrThrow()
            assertNull(repository.load(account).source)
            assertTrue(repository.load(account).imported.isEmpty())
            assertEquals(otherUrl, repository.load(otherAccount).source?.url)
            assertEquals("SWEN90014", repository.load(otherAccount).imported.single().code)
        }

    @Test
    fun parserReloadAndHomeSelectionExpireAtExactlyFifteenMinutes() =
        runBlocking<Unit>(Dispatchers.Main) {
            val account = "post-class-expiry-${UUID.randomUUID()}"
            var now = end.plusMinutes(15).minusNanos(1)
            val imported = parse(now)
            assertEquals(1, imported.sessions.size)
            repository.replaceImport(account, "https://calendar.example.invalid/$account", imported, now.toInstant())
            val justInside = session(account, { now }) { Result.failure(IOException("Offline test")) }
            assertPostClassUpdates(justInside.state.value, now)

            now = end.plusMinutes(15)
            // Engine expiry must work even before a refresh/reload removes the retained row.
            val expiredUpdates = homeUpdates(justInside.state.value, now)
            assertNull(expiredUpdates.featuredGroup)
            assertNull(expiredUpdates.contextLabel)
            assertTrue(expiredUpdates.rows.isEmpty())
            assertEquals(SessionStatus.Finished, justInside.state.value.sessions.single().statusAt(now))

            val reloaded = session(account, { now }) { Result.failure(IOException("Offline test")) }
            assertTrue(reloaded.state.value.isConnected)
            assertTrue(reloaded.state.value.sessions.isEmpty())
            assertTrue(reloaded.refresh().isFailure)
            assertTrue(reloaded.state.value.sessions.isEmpty())
            assertNull(homeUpdates(reloaded.state.value, now).featuredGroup)

            val refreshed = session(account, { now }) { Result.success(calendar()) }
            refreshed.refresh().getOrThrow()
            assertTrue(refreshed.state.value.sessions.isEmpty())
            assertTrue(parse(now).sessions.isEmpty())
            assertNull(homeUpdates(refreshed.state.value, now).featuredGroup)
            assertTrue(session(account, { now }) { Result.failure(IOException("Offline test")) }
                .state.value.sessions.isEmpty())
        }

    /** A new session per "process start"; the shared Room database is what survives between them. */
    private suspend fun session(
        account: String,
        now: () -> ZonedDateTime,
        download: suspend (String) -> Result<ByteArray>
    ): TimetableSession = TimetableSession(
        account, repository, parent, download,
        parse = { bytes, retained -> Result.success(IcsTimetableParser().parse(bytes, now(), zone, retained)) },
        clock = { now().toInstant() }, displayZone = { zone }
    ).also { it.initialize().getOrThrow() }

    private fun assertPostClassUpdates(state: TimetableState, now: ZonedDateTime) {
        val finished = state.sessions.single()
        val scheduleRow = buildSchedulePage(state.sessions, 0).days.single().sessions.single()
        assertEquals(finished.id, scheduleRow.id)
        assertEquals(SessionStatus.Finished, scheduleRow.statusAt(now))
        val group = repositoryGroup()
        assertTrue(group.isFolded(GroupChatPreferences(), state.sessions, now))
        val updates = homeUpdates(state, now)
        assertEquals(group, updates.featuredGroup)
        assertEquals(listOf(group), updates.rows)
        assertEquals("After COMP90018", updates.contextLabel)
        assertEquals(4, updates.rows.single().unreadCount)
        assertEquals("Review the post-class proposal", updates.rows.single().latestMessage)
        assertEquals("proposal.pdf", updates.rows.single().latestFileName)
        assertTrue(state.groups.all { it.unreadCount == 0 && it.latestFileName == null })
        assertTrue(state.groups.none { it.id == group.id })
    }

    private fun homeUpdates(state: TimetableState, now: ZonedDateTime): HomeGroupUpdates {
        val decision = ContextEngine.decide(ContextInputs(
            now = now.toInstant(), sessions = state.sessions, travelMinutes = null,
            bufferMinutes = 0, distanceMeters = null, isMoving = false
        ))
        val snapshot = TravelSnapshot(
            session = decision.session, state = decision.state, finishedSession = decision.finishedSession
        )
        val card = buildHomeCardState(state.sessions, snapshot, now)
        assertEquals(if (now.isBefore(end.plusMinutes(15))) ContextState.POST_CLASS
            else ContextState.NO_UPCOMING_CLASS, card.state)
        val candidates = homePostClassCandidates(listOf(repositoryGroup()), emptyMap())
        return buildHomeGroupUpdates(card.finishedSession, emptyList(), candidates)
    }

    /** The live repository maps this response shape to CourseGroup in CampusCompanionApp. */
    private fun repositoryGroup(): CourseGroup {
        val summary = GroupSummaryRow(
            id = "repository-course-membership", name = "Mobile Computing course group",
            courseCode = "COMP90018", createdBy = "another-member",
            createdAt = "2026-10-01T00:00:00Z", myRole = "member", memberCount = 12,
            unreadCount = 4, latestMessagePreview = "Review the post-class proposal",
            latestActivityAt = "2026-10-13T05:02:00Z", latestFileName = "proposal.pdf",
            timetableKey = "COMP90018|course"
        ).toModel()
        return CourseGroup(
            id = summary.group.id, courseCode = summary.group.courseCode.orEmpty(), name = summary.group.name,
            members = summary.memberCount, unreadCount = summary.unreadCount,
            latestMessage = summary.latestMessagePreview.orEmpty(), latestFileName = summary.latestFileName,
            origin = GroupOrigin.Timetable, timetableKey = summary.group.timetableKey
        )
    }

    private fun parse(
        now: ZonedDateTime,
        code: String = "COMP90018",
        classEnd: ZonedDateTime = end
    ): TimetableImport = IcsTimetableParser().parse(calendar(code, classEnd), now, zone)

    private fun calendar(code: String = "COMP90018", classEnd: ZonedDateTime = end): ByteArray {
        val format = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Campus Companion//Android Post Class Test//EN
            BEGIN:VTIMEZONE
            TZID:Australia/Melbourne
            BEGIN:STANDARD
            DTSTART:19700405T030000
            TZOFFSETFROM:+1100
            TZOFFSETTO:+1000
            TZNAME:AEST
            RRULE:FREQ=YEARLY;BYMONTH=4;BYDAY=1SU
            END:STANDARD
            BEGIN:DAYLIGHT
            DTSTART:19701004T020000
            TZOFFSETFROM:+1000
            TZOFFSETTO:+1100
            TZNAME:AEDT
            RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=1SU
            END:DAYLIGHT
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:post-class-$code
            DTSTAMP:20261001T000000Z
            DTSTART;TZID=Australia/Melbourne:${classEnd.minusHours(1).format(format)}
            DTEND;TZID=Australia/Melbourne:${classEnd.format(format)}
            SUMMARY:$code Mobile Computing Systems Programming Tutorial
            LOCATION:Parkville\, PAR-160
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n").toByteArray()
    }
}
