package au.edu.unimelb.campuscompanion.context

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
    fun theSavedArrivalBufferDrivesTheWarningAndActualLateness() = runBlocking<Unit> {
        val trip = Trip()
        // The saved course preference must override this unrelated global fallback.
        trip.engine.updateLeadMinutes(60)

        val beforeWarning = trip.refresh(at(10, 25).minusSeconds(1))
        assertEquals(at(10, 30), beforeWarning.leaveBy)
        assertEquals(10, beforeWarning.leadMinutes)
        assertEquals(ContextState.UPCOMING, beforeWarning.state)
        assertNull(trip.reminders.consider(beforeWarning))

        val warning = trip.refresh(at(10, 25))
        assertEquals(ContextState.LEAVE_SOON, warning.state)
        assertEquals(ContextState.LEAVE_SOON, trip.reminders.consider(warning)?.state)

        // Losing the arrival buffer does not yet mean the student will miss the class start.
        assertEquals(ContextState.LEAVE_SOON, trip.refresh(at(10, 30)).state)
        assertNull(trip.reminders.consider(trip.engine.snapshot.value))
        assertEquals(ContextState.LEAVE_SOON, trip.refresh(at(10, 40)).state)
        assertNull(trip.reminders.consider(trip.engine.snapshot.value))

        val late = trip.refresh(at(10, 40).plusSeconds(1))
        assertEquals(ContextState.RUNNING_LATE, late.state)
        assertEquals(ContextState.RUNNING_LATE, trip.reminders.consider(late)?.state)
        assertNull(trip.reminders.consider(late))
        assertEquals(listOf(ContextState.LEAVE_SOON, ContextState.RUNNING_LATE), trip.sent.map { it.state })
        assertEquals(trip.sent[0].notificationId, trip.sent[1].notificationId)
    }

    @Test
    fun weatherAndRoomEntryAdvanceTheSameDepartureWarning() = runBlocking<Unit> {
        val trip = Trip(config = ContextConfig(entryAllowanceMinutes = 3))
        trip.rainfall = 0.4

        // 11:00 - 20-minute trip - 10-minute arrival buffer - 5-minute rain - 3-minute entry.
        val beforeWarning = trip.refresh(at(10, 17).minusSeconds(1))
        assertEquals(at(10, 22), beforeWarning.leaveBy)
        assertEquals(5, beforeWarning.weatherBufferMinutes)
        assertEquals(3, beforeWarning.entryAllowanceMinutes)
        assertEquals(ContextState.UPCOMING, beforeWarning.state)
        assertNull(trip.reminders.consider(beforeWarning))

        val warning = trip.refresh(at(10, 17))
        assertEquals(ContextState.LEAVE_SOON, warning.state)
        val reminder = trip.reminders.consider(warning)
        assertNotNull(reminder)
        assertTrue(reminder!!.text.contains("Allow 5 min more for the weather."))
        assertEquals(1, trip.sent.size)
    }

    @Test
    fun aCoursePreferenceChangeUpdatesTheRunningPipelineWithoutAnotherLocationFix() = runBlocking<Unit> {
        val trip = Trip()
        trip.now = at(10, 25)
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
            assertEquals(ContextState.LEAVE_SOON, reminder.state)
            assertEquals(trip.lecture.id, reminder.sessionId)
            assertEquals(at(10, 30), trip.engine.snapshot.value.leaveBy)
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
        // Unavailable fusion permits fresh movement plus closing distance.
        trip.engine.updateEnRouteSignal(null)
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
        val turnedBack = trip.refresh(at(10, 25), FARTHER, moving = true)
        assertEquals(ContextState.LEAVE_SOON, turnedBack.state)
        assertEquals(ContextState.LEAVE_SOON, trip.reminders.consider(turnedBack)?.state)
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
    fun aKnownPositionStillAllowsALateReminderWithoutAnEta() = runBlocking<Unit> {
        val trip = Trip()
        trip.travelMinutes = null

        val late = trip.refresh(at(11, 5))
        assertNotNull(late.distanceMeters)
        assertNull(late.estimate)
        assertNull(late.leaveBy)
        assertEquals(ContextState.RUNNING_LATE, late.state)
        assertEquals(ContextState.RUNNING_LATE, trip.reminders.consider(late)?.state)
        assertEquals(1, trip.sent.size)

        val graceEnded = trip.refresh(at(11, 15))
        assertSuppressed(trip, graceEnded, ContextState.IN_CLASS)
        assertEquals(1, trip.sent.size)
    }

    @Test
    fun theCurrentLocationPolicyKeepsTheLastFixAfterThreeMinutes() = runBlocking<Unit> {
        val trip = Trip()
        trip.refresh(at(10, 22))

        // Preserve the feature's location policy during integration; expiry is a separate change.
        trip.now = at(10, 25)
        trip.engine.refresh()
        val warning = trip.engine.snapshot.value

        assertNotNull(warning.estimate)
        assertNotNull(warning.distanceMeters)
        assertEquals(at(10, 30), warning.leaveBy)
        assertEquals(ContextState.LEAVE_SOON, warning.state)
        assertNotNull(trip.reminders.consider(warning))
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
