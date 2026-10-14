package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** The travel engine and the departure reminders together, as the application runs them. */
class DeparturePromptPipelineTest {
    private var now = at(-22) // 11:38 for a 12:00 class
    // These lifecycle fixtures deliberately use a ten-minute arrival buffer.
    private var preference = CourseReminderPreference(leadMinutes = 10)
    private val preferenceChanges = MutableStateFlow(0)
    private val eta = GatedEta()
    private val shade = FakeDepartureShade()
    private val engine = TravelEngine(
        eta = eta,
        buildings = FakeBuildingLookup(),
        preferences = { TravelPreferences() },
        clock = { now },
        tickInterval = Duration.ofHours(1),
        reminderPreference = { preference },
        reminderChanges = preferenceChanges.map { }
    )
    private val lecture = CourseSession(
        id = "lecture",
        code = "COMP90018",
        title = "Mobile Computing",
        location = "Parkville Campus",
        room = "PAR-160-B117",
        start = at(0).atZone(MELBOURNE),
        end = at(60).atZone(MELBOURNE),
        activity = "lecture"
    )
    private val key = lecture.departurePromptKey()

    @Test
    fun theEngineMarksWorkInProgressAsPending() = runBlocking<Unit> {
        assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(HOME)
        refresh(minutes = 15)
        assertFalse(engine.snapshot.value.pending)

        engine.updateSessions(listOf(lecture.copy(room = "PAR-104-G01")))
        assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)

        engine.updateSessions(emptyList())
        engine.refresh()
        // A result without a class is not pending: it is what the reminders withdraw prompts on.
        assertEquals(TravelSnapshot(), engine.snapshot.value)
    }

    @Test
    fun aVenueEditAfterTheWarningKeepsThePromptUntilTheNewResultThenUpdatesItSilently() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(HOME)
        withReminders {
            refresh(minutes = 15)
            awaitShade { shade.alerts.size == 1 }
            assertTrue(shade.textOf(key)!!.contains("Peter Hall"))

            engine.updateSessions(listOf(lecture.copy(room = "PAR-104-G01")))
            val fresh = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
            val request = eta.next()
            // While the new travel time is loading, the student still sees the prompt they heard.
            delay(50)
            assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
            assertEquals(setOf(key), shade.shownKeys())
            assertTrue(shade.withdrawn.isEmpty())

            request.complete(7)
            withTimeout(TIMEOUT_MS) { fresh.await() }
            awaitShade { shade.updates.size == 1 }
            assertTrue(shade.textOf(key)!!.contains("Alan Gilbert"))
            assertEquals(1, shade.alerts.size)
        }
    }

    @Test
    fun deletingTheClassAfterTheWarningWithdrawsThePrompt() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(HOME)
        withReminders {
            refresh(minutes = 15)
            awaitShade { shade.alerts.size == 1 }

            engine.updateSessions(emptyList())
            engine.refresh()

            awaitShade { shade.shown.isEmpty() }
            assertEquals(listOf(key), shade.withdrawn)
        }
    }

    @Test
    fun arrivingAfterTheWarningWithdrawsThePrompt() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(HOME)
        withReminders {
            refresh(minutes = 15)
            awaitShade { shade.alerts.size == 1 }

            engine.updateOrigin(FakeBuildingLookup.PETER_HALL.location)
            refresh(minutes = 1)

            awaitShade { shade.shown.isEmpty() }
            assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)
        }
    }

    @Test
    fun aBufferChangedWhileTheEtaIsLoadingNeitherWarnsEarlyNorSkipsTheWarning() = runBlocking<Unit> {
        now = at(-31) // 11:29, before the default ten-minute preparation window
        preference = CourseReminderPreference(leadMinutes = 10)
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(HOME)
        withReminders {
            refresh(minutes = 10)
            assertEquals(ContextState.UPCOMING, engine.snapshot.value.state)

            // 11:38: the buffered 11:40 deadline warns at 11:30; without a buffer it warns at 11:40.
            now = at(-22)
            val inFlight = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
            val request = eta.next()
            preference = CourseReminderPreference(leadMinutes = 0)
            preferenceChanges.value++
            request.complete(10)
            withTimeout(TIMEOUT_MS) { inFlight.await() }
            assertEquals(10, engine.snapshot.value.leadMinutes)
            assertEquals(ContextState.LEAVE_SOON, engine.snapshot.value.state)
            delay(50)
            assertTrue(shade.alerts.isEmpty())

            refresh(minutes = 10)
            assertEquals(ContextState.UPCOMING, engine.snapshot.value.state)

            now = at(-14) // 11:46
            refresh(minutes = 10)
            awaitShade { shade.alerts.size == 1 }
            assertEquals(ContextState.LEAVE_SOON, shade.alerts.single().state)
        }
    }

    @Test
    fun aPromptShownBeforeARestartSurvivesStartupAndIsUpdatedSilently() = runBlocking<Unit> {
        // The previous process delivered the warning; the shade and the history outlived it.
        val history = mutableSetOf<Pair<String, ContextState>>()
        history += key to ContextState.LEAVE_SOON
        shade.shown[key] = "Time to leave for COMP90018" to "Mobile Computing starts at 12:00 PM in Peter Hall."
        val reminders = DepartureReminders(
            snapshots = flowOf(),
            preferences = { preference },
            notify = shade::post,
            shade = shade,
            wasSent = { _, session, state -> (session.departurePromptKey() to state) in history },
            rememberSent = { _, session, state -> history += session.departurePromptKey() to state }
        )

        reminders.consider(engine.snapshot.value)
        engine.updateSessions(listOf(lecture), reset = true)
        reminders.consider(engine.snapshot.value)
        assertEquals(setOf(key), shade.shownKeys())

        engine.updateOrigin(HOME)
        refresh(minutes = 15)
        reminders.consider(engine.snapshot.value)

        assertTrue(shade.alerts.isEmpty())
        assertEquals(1, shade.updates.size)
        assertTrue(shade.textOf(key)!!.contains("About 15 min"))
    }

    @Test
    fun startingTheEngineBeforeRestoringTheTimetableKeepsTheExistingPrompt() = runBlocking<Unit> {
        // Supabase can restore the user before AppRepositories finishes loading their timetable.
        val userId = "restored-student"
        shade.shown[key] = "Time to leave for COMP90018" to "Mobile Computing starts at 12:00 PM in Peter Hall."
        val reminders = DepartureReminders(
            snapshots = engine.snapshot,
            preferences = { preference },
            notify = shade::post,
            shade = shade,
            currentUserId = { userId },
            wasSent = { user, session, state ->
                user == userId && session.departurePromptKey() == key && state == ContextState.LEAVE_SOON
            }
        )
        engine.updateOrigin(HOME)
        val reminderJob = launch(start = CoroutineStart.UNDISPATCHED) { reminders.run() }
        val engineJob = launch(start = CoroutineStart.UNDISPATCHED) { engine.run() }
        try {
            // Force a refresh before restoration as well as letting run() process its initial tick.
            // This makes the assertion independent of the combine collectors' scheduling order.
            engine.refresh()
            yield()
            assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
            assertEquals(setOf(key), shade.shownKeys())
            assertTrue(shade.withdrawn.isEmpty())

            engine.updateSessions(listOf(lecture), reset = true)
            eta.next().complete(15)
            awaitShade { shade.updates.size == 1 }

            assertEquals(setOf(key), shade.shownKeys())
            assertTrue(shade.withdrawn.isEmpty())
            assertTrue(shade.alerts.isEmpty())
            assertTrue(shade.textOf(key)!!.contains("About 15 min"))
        } finally {
            engineJob.cancelAndJoin()
            reminderJob.cancelAndJoin()
        }
    }

    @Test
    fun restoringAnEmptyTimetableWithdrawsTheExistingPrompt() = runBlocking<Unit> {
        shade.shown[key] = "Time to leave for COMP90018" to "A reminder from the previous process"
        val reminders = DepartureReminders(
            snapshots = engine.snapshot,
            preferences = { preference },
            notify = shade::post,
            shade = shade,
            currentUserId = { "restored-student" }
        )
        val reminderJob = launch(start = CoroutineStart.UNDISPATCHED) { reminders.run() }
        val engineJob = launch(start = CoroutineStart.UNDISPATCHED) { engine.run() }
        try {
            engine.refresh()
            yield()
            assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
            assertEquals(setOf(key), shade.shownKeys())

            // An explicit empty result is different from the timetable not having loaded yet.
            engine.updateSessions(emptyList())
            awaitShade { shade.shown.isEmpty() }

            assertEquals(TravelSnapshot(), engine.snapshot.value)
            assertEquals(listOf(key), shade.withdrawn)
            assertTrue(shade.alerts.isEmpty())
        } finally {
            engineJob.cancelAndJoin()
            reminderJob.cancelAndJoin()
        }
    }

    private suspend fun withReminders(block: suspend () -> Unit) = coroutineScope {
        val reminders = DepartureReminders(
            snapshots = engine.snapshot,
            preferences = { preference },
            notify = shade::post,
            preferenceChanges = preferenceChanges.map { },
            shade = shade
        )
        val job = launch(start = CoroutineStart.UNDISPATCHED) { reminders.run() }
        try {
            block()
        } finally {
            job.cancelAndJoin()
        }
    }

    private suspend fun refresh(minutes: Int) = coroutineScope {
        val job = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
        eta.next().complete(minutes)
        withTimeout(TIMEOUT_MS) { job.await() }
    }

    private suspend fun awaitShade(condition: () -> Boolean) {
        withTimeout(TIMEOUT_MS) { while (!condition()) delay(1) }
    }

    private class GatedEta : EtaRepository {
        private val requests = Channel<Pending>(Channel.UNLIMITED)

        class Pending(private val mode: TravelMode) {
            val answer = CompletableDeferred<TravelEstimate>()

            fun complete(minutes: Int) {
                answer.complete(TravelEstimate(mode, minutes * 60L, 900, EstimateSource.Routing, Instant.EPOCH))
            }
        }

        override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): Result<TravelEstimate> {
            val pending = Pending(mode)
            requests.send(pending)
            return Result.success(pending.answer.await())
        }

        suspend fun next(): Pending = withTimeout(TIMEOUT_MS) { requests.receive() }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        val MELBOURNE: ZoneId = ZoneId.of("Australia/Melbourne")
        val HOME = GeoPoint(-37.8100, 144.9630)

        /** Minutes from the class's 12:00 start on Monday 5 October 2026, Melbourne time. */
        fun at(minutesFromStart: Long): Instant =
            Instant.parse("2026-10-05T01:00:00Z").plus(Duration.ofMinutes(minutesFromStart))
    }
}
