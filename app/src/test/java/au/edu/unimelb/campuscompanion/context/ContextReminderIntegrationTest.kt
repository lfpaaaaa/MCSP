package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.english

import au.edu.unimelb.campuscompanion.ui.model.buildHomeCardState
import au.edu.unimelb.campuscompanion.ui.model.buildHomeCardGuidance
import au.edu.unimelb.campuscompanion.ui.model.reminderTiming
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderTiming
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.model.WeatherSnapshot
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
import au.edu.unimelb.campuscompanion.data.repository.WeatherRepository
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.reminderSeriesKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/** Keeps course preferences, context decisions and notification eligibility in the same pipeline. */
class ContextReminderIntegrationTest {
    @Test
    fun theSavedLeadPreparesTenMinutesBeforeDepartureWithoutAnExtraEarlyAlert() = runBlocking<Unit> {
        val trip = Trip()
        trip.engine.updateLeadMinutes(60) // Per-course settings override the global fallback.

        val early = trip.refresh(at(10, 25))
        assertEquals(at(10, 40), early.leaveBy)
        assertEquals(10, early.leadMinutes)
        assertEquals(ContextState.UPCOMING, early.state)
        assertNull(trip.reminders.consider(early)) // The preparation window has not opened yet.
        assertNull(trip.reminders.consider(trip.refresh(at(10, 30).minusSeconds(1))))

        val warning = trip.refresh(at(10, 30))
        val preparation = trip.reminders.consider(warning)!!
        assertEquals(DepartureReminderStage.Prepare, preparation.stage)
        val home = buildHomeCardGuidance(buildHomeCardState(listOf(trip.lecture), warning, at(10, 30)), at(10, 30))
        assertEquals("Leave in 10 minutes", home.headline.english())
        assertEquals("Leave by 10:40 AM", home.departureLine?.english())
        assertTrue(preparation.text.english().contains("Leave by 10:40 AM"))
        assertEquals(CourseReminderTiming.Ready(at(10, 40), at(10, 30), 20), trip.lecture.reminderTiming(warning, 10))
        assertNull(trip.reminders.consider(trip.refresh(at(10, 31))))

        val deadline = trip.refresh(at(10, 40))
        assertEquals(ContextState.LEAVE_SOON, deadline.state)
        assertEquals(DepartureReminderStage.LeaveNow, trip.reminders.consider(deadline)?.stage)
        val late = trip.refresh(at(10, 40).plusSeconds(1))
        assertEquals(ContextState.RUNNING_LATE, late.state)
        assertNull(trip.reminders.consider(late))
        assertEquals(listOf(DepartureReminderStage.Prepare, DepartureReminderStage.LeaveNow), trip.sent.map { it.stage })
        assertEquals(trip.sent[0].notificationId, trip.sent[1].notificationId)
    }

    @Test
    fun weatherAndRoomEntryAdvanceTheSameDepartureWarning() = runBlocking<Unit> {
        val trip = Trip(config = ContextConfig(entryAllowanceMinutes = 3))
        trip.rainfall = 0.4

        // Depart at 10:32 after travel, rain and entry; the 10-minute warning is at 10:22.
        val beforeWarning = trip.refresh(at(10, 22).minusSeconds(1))
        assertEquals(at(10, 32), beforeWarning.leaveBy)
        assertEquals(5, beforeWarning.weatherBufferMinutes)
        assertEquals(3, beforeWarning.entryAllowanceMinutes)
        assertEquals(ContextState.UPCOMING, beforeWarning.state)
        assertNull(trip.reminders.consider(beforeWarning))

        val warning = trip.refresh(at(10, 22))
        assertEquals(ContextState.LEAVE_SOON, warning.state)
        val reminder = trip.reminders.consider(warning)
        assertNotNull(reminder)
        assertTrue(reminder!!.text.english().contains("Includes 5 min extra for weather"))
        assertEquals(1, trip.sent.size)
    }

    @Test
    fun aCoursePreferenceChangeUpdatesTheRunningPipelineWithoutAnotherLocationFix() = runBlocking<Unit> {
        val trip = Trip()
        trip.now = at(10, 30)
        trip.savePreference(trip.lecture, CourseReminderPreference(leadMinutes = 0))
        trip.engine.updateOrigin(AWAY)
        val delivered = Channel<DepartureReminder>(Channel.UNLIMITED)
        val reminders = DepartureReminders(
            snapshots = trip.engine.snapshot,
            preferences = trip::preference,
            preferenceChanges = trip.preferenceChanges.map { Unit },
            notify = { delivered.trySend(it).isSuccess }
        )
        val engineJob = launch { trip.engine.run() }
        val reminderJob = launch { reminders.run() }
        try {
            val initial = withTimeout(2_000) {
                trip.engine.snapshot.first { it.session?.id == trip.lecture.id }
            }
            assertEquals(ContextState.UPCOMING, initial.state)
            assertEquals(at(10, 40), initial.leaveBy)
            assertTrue(delivered.tryReceive().isFailure)

            trip.savePreference(trip.lecture, CourseReminderPreference(leadMinutes = 10))
            trip.preferenceChanges.value++

            val reminder = withTimeout(2_000) { delivered.receive() }
            assertEquals(DepartureReminderStage.Prepare, reminder.stage)
            assertEquals(trip.lecture.id, reminder.sessionId)
            assertEquals(at(10, 40), trip.engine.snapshot.value.leaveBy)
        } finally {
            engineJob.cancel()
            reminderJob.cancel()
            engineJob.join()
            reminderJob.join()
            delivered.close()
        }
    }

    @Test
    fun settingOffEarlyAndWaitingSuppressRemindersThroughArrivalAndClassStart() = runBlocking<Unit> {
        val trip = Trip()
        trip.refresh(at(10, 0))
        // The optional fused verdict is not a veto of raw movement plus closing distance.
        trip.engine.updateEnRouteSignal(false)
        val settingOff = trip.refresh(at(10, 2), origin = CLOSER, moving = true)
        assertSuppressed(trip, settingOff, ContextState.EN_ROUTE)

        val waiting = trip.refresh(at(10, 25), origin = CLOSER)
        assertSuppressed(trip, waiting, ContextState.EN_ROUTE)
        val pastDeadline = trip.refresh(at(10, 41), origin = CLOSER)
        assertSuppressed(trip, pastDeadline, ContextState.EN_ROUTE)

        val arrived = trip.refresh(at(10, 54), origin = FakeBuildingLookup.PETER_HALL.location)
        assertSuppressed(trip, arrived, ContextState.ARRIVED)
        val inClass = trip.refresh(at(11, 0), origin = FakeBuildingLookup.PETER_HALL.location)
        assertSuppressed(trip, inClass, ContextState.IN_CLASS)
        assertTrue(trip.sent.isEmpty())
    }

    @Test
    fun turningBackMakesDepartureRemindersEligibleAgain() = runBlocking<Unit> {
        val trip = Trip()
        trip.refresh(at(10, 0))
        assertSuppressed(trip, trip.refresh(at(10, 2), CLOSER, moving = true), ContextState.EN_ROUTE)

        // More than 150 m farther from the building than the closest point of this trip.
        val turnedBack = trip.refresh(at(10, 30), FARTHER, moving = true)
        assertEquals(ContextState.LEAVE_SOON, turnedBack.state)
        assertEquals(DepartureReminderStage.Prepare, trip.reminders.consider(turnedBack)?.stage)
        assertEquals(1, trip.sent.size)
    }

    @Test
    fun nonDepartureDayStatesDoNotNotifyEvenWhenAnEstimateExists() = runBlocking<Unit> {
        val trip = Trip()
        val morning = trip.lecture.copy(id = "morning", start = at(9, 0), end = at(10, 0))
        val afternoon = trip.lecture.copy(id = "afternoon", start = at(15, 0), end = at(16, 0))
        trip.engine.updateSessions(listOf(morning, afternoon))

        val postClass = trip.refresh(at(10, 5))
        assertNotNull(postClass.estimate)
        assertEquals(morning.id, postClass.finishedSession?.id)
        assertSuppressed(trip, postClass, ContextState.POST_CLASS)
        assertSuppressed(trip, trip.refresh(at(10, 15)), ContextState.LONG_BREAK)

        val tomorrow = trip.lecture.copy(
            id = "tomorrow", start = trip.lecture.start.plusDays(1), end = trip.lecture.end.plusDays(1)
        )
        trip.engine.updateSessions(listOf(tomorrow))
        val noClassToday = trip.refresh(at(12, 0))
        assertNotNull(noClassToday.estimate)
        assertSuppressed(trip, noClassToday, ContextState.NO_UPCOMING_CLASS)
        assertTrue(trip.sent.isEmpty())
    }

    @Test
    fun aKnownPositionWithoutAnEtaDoesNotInventADepartureDeadline() = runBlocking<Unit> {
        val trip = Trip()
        trip.travelMinutes = null

        val late = trip.refresh(at(11, 5))
        assertNotNull(late.distanceMeters)
        assertNull(late.estimate)
        assertNull(late.leaveBy)
        assertEquals(ContextState.RUNNING_LATE, late.state)
        assertNull(trip.reminders.consider(late))
        assertTrue(trip.sent.isEmpty())

        val graceEnded = trip.refresh(at(11, 15))
        assertSuppressed(trip, graceEnded, ContextState.IN_CLASS)
        assertTrue(trip.sent.isEmpty())
    }

    @Test
    fun anExpiredPositionDoesNotSupplyAnEtaOrDepartureReminder() = runBlocking<Unit> {
        val trip = Trip()
        trip.refresh(at(10, 27))

        trip.now = at(10, 30)
        trip.engine.refresh()
        val stale = trip.engine.snapshot.value
        assertNull(stale.estimate)
        assertNull(stale.distanceMeters)
        assertNull(stale.leaveBy)
        assertNull(trip.reminders.consider(stale))

        // The next fresh fix makes the real 10-minute warning available again.
        val fresh = trip.refresh(at(10, 30))
        assertNotNull(fresh.estimate)
        assertEquals(DepartureReminderStage.Prepare, trip.reminders.consider(fresh)?.stage)
    }

    @Test
    fun stationaryUrgencyAtFiveMinutesAgreesAcrossHomeAndNotifications() = runBlocking<Unit> {
        val trip = Trip()
        trip.reminders.consider(trip.refresh(at(10, 30)))
        repeat(6) { index ->
            val snapshot = trip.refresh(at(10, 34).plusSeconds(index * 10L))
            assertNull(trip.reminders.consider(snapshot))
        }
        val urgent = trip.refresh(at(10, 35))
        assertEquals(DepartureReminderStage.LeaveNow, trip.reminders.consider(urgent)?.stage)
        val card = buildHomeCardState(listOf(trip.lecture), urgent, at(10, 35))
        assertTrue(card.urgentDeparture)
        assertEquals("Leave now", buildHomeCardGuidance(card, at(10, 35)).headline.english())
        assertEquals("Leave by 10:40 AM", buildHomeCardGuidance(card, at(10, 35)).departureLine?.english())
        assertNull(trip.reminders.consider(trip.refresh(at(10, 40))))
        assertEquals(2, trip.sent.size)
    }

    @Test
    fun preparationLeadChangesOnlyWarningTimeAndNeverHomeDepartureDeadline() = runBlocking<Unit> {
        for (lead in listOf(0, 5, 10, 20)) {
            val trip = Trip()
            trip.savePreference(trip.lecture, CourseReminderPreference(leadMinutes = lead))
            val preparationAt = at(10, 40).minusMinutes(lead.toLong())
            val before = trip.refresh(preparationAt.minusSeconds(1))
            assertNull(trip.reminders.consider(before))
            val due = trip.refresh(preparationAt)
            assertEquals(at(10, 40), due.leaveBy)
            assertEquals(CourseReminderTiming.Ready(at(10, 40), preparationAt, 20), trip.lecture.reminderTiming(due, lead))
            assertNotNull(trip.reminders.consider(due))
            val guidance = buildHomeCardGuidance(buildHomeCardState(listOf(trip.lecture), due, preparationAt), preparationAt)
            assertEquals("Leave by 10:40 AM", guidance.departureLine?.english())
        }
    }

    private fun assertSuppressed(trip: Trip, snapshot: TravelSnapshot, expected: ContextState) {
        assertEquals(expected, snapshot.state)
        // A fresh notifier prevents earlier deduplication from hiding a broken suppression rule.
        val fresh = DepartureReminders(
            snapshots = trip.engine.snapshot, preferences = trip::preference, notify = trip.sent::add
        )
        val sentBefore = trip.sent.size
        assertNull(fresh.consider(snapshot))
        assertEquals(sentBefore, trip.sent.size)
    }

    private class Trip(config: ContextConfig = ContextConfig()) {
        var now = at(10, 0)
        var travelMinutes: Int? = 20
        var rainfall = 0.0
        val lecture = CourseSession(
            id = "comp-lecture", code = "COMP90018", title = "Mobile Computing lecture",
            location = "Parkville Campus", room = "PAR-160", start = at(11, 0), end = at(12, 0), activity = "lecture"
        )
        val sent = mutableListOf<DepartureReminder>()
        val preferenceChanges = MutableStateFlow(0)
        private val savedPreferences = mutableMapOf(
            lecture.reminderSeriesKey() to CourseReminderPreference(leadMinutes = 10)
        )

        // Test doubles supply course settings and remote data; the engines themselves are real.
        val engine = TravelEngine(
            eta = object : EtaRepository {
                override suspend fun estimate(
                    origin: GeoPoint, destination: GeoPoint, mode: TravelMode
                ): Result<TravelEstimate> {
                    val minutes = travelMinutes ?: return Result.failure(IllegalStateException("ETA unavailable"))
                    return Result.success(
                        TravelEstimate(mode, minutes * 60L, 1_200, EstimateSource.Routing, now.toInstant())
                    )
                }
            },
            buildings = FakeBuildingLookup(),
            preferences = { TravelPreferences(walkingThresholdMeters = 5_000) },
            weather = object : WeatherRepository {
                override suspend fun currentWeather(location: GeoPoint): Result<WeatherSnapshot> = Result.success(
                    WeatherSnapshot(
                        temperatureCelsius = 16.0, precipitationMillimetres = rainfall,
                        precipitationProbabilityPercent = 10, observedAt = now.toInstant()
                    )
                )
            },
            clock = { now.toInstant() },
            tickInterval = Duration.ofHours(1),
            reminderPreference = ::preference,
            reminderChanges = preferenceChanges.map { Unit },
            config = config
        )
        val reminders = DepartureReminders(
            snapshots = engine.snapshot, preferences = ::preference, notify = sent::add
        )

        init {
            engine.updateSessions(listOf(lecture))
        }

        fun preference(session: CourseSession): CourseReminderPreference =
            savedPreferences[session.reminderSeriesKey()] ?: CourseReminderPreference()

        fun savePreference(session: CourseSession, preference: CourseReminderPreference) {
            savedPreferences[session.reminderSeriesKey()] = preference
        }

        suspend fun refresh(at: ZonedDateTime, origin: GeoPoint = AWAY, moving: Boolean = false): TravelSnapshot {
            now = at
            engine.updateOrigin(origin)
            engine.updateMoving(moving)
            engine.refresh()
            return engine.snapshot.value
        }
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Australia/Melbourne")
        val AWAY = GeoPoint(-37.8100, 144.9630)
        val CLOSER = GeoPoint(-37.8090, 144.9628)
        val FARTHER = GeoPoint(-37.8120, 144.9630)

        fun at(hour: Int, minute: Int): ZonedDateTime = ZonedDateTime.of(2026, 10, 5, hour, minute, 0, 0, ZONE)
    }
}
