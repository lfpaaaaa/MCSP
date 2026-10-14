package au.edu.unimelb.campuscompanion.sensing.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityObservationReducerTest {
    @Test
    fun inactivePeriodRejectsTransitionsAndInitialSnapshots() {
        val reducer = ActivityObservationReducer()
        assertFalse(reducer.acceptTransition(enter(UserActivityState.WALKING, 2), nanos(2)))
        assertFalse(reducer.acceptSnapshot(UserActivityState.IN_VEHICLE, 90, nanos(2), nanos(2)))
        assertNull(reducer.observation)
    }

    @Test
    fun orderedEnterEstablishesEachSupportedActivityWithoutConfidence() {
        listOf(UserActivityState.WALKING, UserActivityState.RUNNING, UserActivityState.IN_VEHICLE,
            UserActivityState.STILL).forEach { state ->
            val reducer = started()
            assertTrue(reducer.acceptTransition(enter(state, 2), nanos(2)))
            assertEquals(ActivityObservation(state, nanos(2)), reducer.observation)
        }
    }

    @Test
    fun matchedExitClearsMovementAndStillState() {
        listOf(UserActivityState.WALKING, UserActivityState.RUNNING, UserActivityState.IN_VEHICLE,
            UserActivityState.STILL).forEach { state ->
            val reducer = started()
            reducer.acceptTransition(enter(state, 2), nanos(2))
            assertTrue(reducer.acceptTransition(exit(state, 3), nanos(3)))
            assertEquals(ActivityObservation(UserActivityState.UNKNOWN, nanos(3)), reducer.observation)
        }
    }

    @Test
    fun delayedExitCannotClearNewerDifferentActivity() {
        val reducer = started()
        reducer.acceptTransition(enter(UserActivityState.WALKING, 2), nanos(2))
        reducer.acceptTransition(enter(UserActivityState.IN_VEHICLE, 4), nanos(4))
        assertFalse(reducer.acceptTransition(exit(UserActivityState.WALKING, 3), nanos(5)))
        assertEquals(UserActivityState.IN_VEHICLE, reducer.observation?.state)
        assertTrue(reducer.acceptTransition(exit(UserActivityState.WALKING, 5), nanos(5)))
        assertEquals(ActivityObservation(UserActivityState.IN_VEHICLE, nanos(4)), reducer.observation)
    }

    @Test
    fun newerWalkingEntryReplacesVehicleSeedEvenWhenVehicleExitArrivesLate() {
        val reducer = started()
        assertTrue(reducer.acceptSnapshot(UserActivityState.IN_VEHICLE, 90, nanos(2), nanos(2)))
        reducer.acceptTransition(enter(UserActivityState.WALKING, 4), nanos(4))
        assertEquals(UserActivityState.WALKING, reducer.observation?.state)
        assertFalse(reducer.acceptTransition(exit(UserActivityState.IN_VEHICLE, 3), nanos(5)))
        assertEquals(ActivityObservation(UserActivityState.WALKING, nanos(4)), reducer.observation)
    }

    @Test
    fun batchReducesMeasuredOrderWithExitBeforeEqualTimeEnter() {
        val reducer = started()
        val accepted = reducer.acceptBatch(listOf(
            enter(UserActivityState.IN_VEHICLE, 4),
            exit(UserActivityState.WALKING, 4),
            enter(UserActivityState.WALKING, 2),
            enter(UserActivityState.RUNNING, 3)
        ), nanos(5))
        assertEquals(listOf(nanos(2), nanos(3), nanos(4), nanos(4)), accepted.map { it.observedAtNanos })
        assertEquals(ActivityObservation(UserActivityState.IN_VEHICLE, nanos(4)), reducer.observation)
    }

    @Test
    fun duplicateAndOutOfOrderEventsCannotRestoreExitedState() {
        val reducer = started()
        val entering = enter(UserActivityState.WALKING, 2)
        assertTrue(reducer.acceptTransition(entering, nanos(2)))
        assertFalse(reducer.acceptTransition(entering, nanos(2)))
        assertTrue(reducer.acceptTransition(exit(UserActivityState.WALKING, 3), nanos(3)))
        assertFalse(reducer.acceptTransition(entering, nanos(4)))
        assertEquals(UserActivityState.UNKNOWN, reducer.observation?.state)
    }

    @Test
    fun beforeOrExactlyAtActivationFutureAndStaleEventsAreRejected() {
        val reducer = started()
        assertFalse(reducer.acceptTransition(enter(UserActivityState.WALKING, 0), nanos(2)))
        assertFalse(reducer.acceptTransition(enter(UserActivityState.WALKING, 1), nanos(2)))
        assertFalse(reducer.acceptTransition(enter(UserActivityState.WALKING, 3), nanos(2)))
        assertFalse(reducer.acceptTransition(enter(UserActivityState.WALKING, 2), nanos(63)))
        assertNull(reducer.observation)
        assertTrue(reducer.acceptTransition(enter(UserActivityState.WALKING, 3), nanos(63)))
        assertEquals(nanos(3), reducer.observation?.observedAtNanos)
    }

    @Test
    fun acceptedTransitionDoesNotExpireDuringUnchangedActivity() {
        val reducer = started()
        reducer.acceptTransition(enter(UserActivityState.IN_VEHICLE, 2), nanos(2))
        // The newest delivery describes a different activity; it must not age out the vehicle entry.
        assertTrue(reducer.acceptTransition(exit(UserActivityState.WALKING, 300), nanos(301)))
        assertEquals(ActivityObservation(UserActivityState.IN_VEHICLE, nanos(2)), reducer.observation)
    }

    @Test
    fun stillEnterClearsMovingActivityUntilRelevantExit() {
        val reducer = started()
        reducer.acceptTransition(enter(UserActivityState.RUNNING, 2), nanos(2))
        reducer.acceptTransition(enter(UserActivityState.STILL, 3), nanos(3))
        assertEquals(false, reducer.observation?.state?.isMoving)
        reducer.acceptTransition(exit(UserActivityState.RUNNING, 4), nanos(4))
        assertEquals(UserActivityState.STILL, reducer.observation?.state)
        reducer.acceptTransition(exit(UserActivityState.STILL, 5), nanos(5))
        assertEquals(UserActivityState.UNKNOWN, reducer.observation?.state)
    }

    @Test
    fun repeatedStartPreservesStateWhileChangedActivationResetsIt() {
        val reducer = started()
        reducer.acceptTransition(enter(UserActivityState.WALKING, 2), nanos(2))
        reducer.start(nanos(1))
        assertEquals(UserActivityState.WALKING, reducer.observation?.state)
        reducer.start(nanos(3))
        assertNull(reducer.observation)
        assertFalse(reducer.acceptTransition(enter(UserActivityState.IN_VEHICLE, 2), nanos(4)))
        assertTrue(reducer.acceptTransition(enter(UserActivityState.IN_VEHICLE, 4), nanos(4)))
    }

    @Test
    fun stopAndRegistrationLossClearStateAndRequireASeedOrNewEnter() {
        val reducer = started()
        reducer.acceptTransition(enter(UserActivityState.RUNNING, 2), nanos(2))
        reducer.stop()
        assertNull(reducer.observation)
        assertFalse(reducer.acceptTransition(enter(UserActivityState.WALKING, 3), nanos(3)))
        reducer.start(nanos(4))
        assertNull(reducer.observation)
        assertTrue(reducer.acceptSnapshot(UserActivityState.IN_VEHICLE, 90, nanos(5), nanos(5)))
        assertEquals(UserActivityState.IN_VEHICLE, reducer.observation?.state)
    }

    @Test
    fun seedCanRecognizeAlreadyInVehicleOrGenericOnFoot() {
        listOf(UserActivityState.IN_VEHICLE, UserActivityState.ON_FOOT).forEach { state ->
            val reducer = started()
            assertTrue(reducer.acceptSnapshot(state, 70, nanos(2), nanos(3)))
            assertEquals(ActivityObservation(state, nanos(2)), reducer.observation)
            assertEquals(true, reducer.observation?.state?.isMoving)
        }
    }

    @Test
    fun seedConfidenceThresholdIsConfigurableAndUnknownIsUnusable() {
        val reducer = ActivityObservationReducer(minimumSnapshotConfidence = 25)
        reducer.start(nanos(1))
        assertFalse(reducer.acceptSnapshot(UserActivityState.ON_FOOT, 24, nanos(2), nanos(2)))
        assertFalse(reducer.acceptSnapshot(UserActivityState.UNKNOWN, 100, nanos(2), nanos(2)))
        assertFalse(reducer.acceptSnapshot(UserActivityState.ON_FOOT, 101, nanos(2), nanos(2)))
        assertTrue(reducer.acceptSnapshot(UserActivityState.ON_FOOT, 25, nanos(2), nanos(2)))
    }

    @Test
    fun seedRejectsPreactivationStaleFutureAndReplayedMeasurements() {
        val reducer = started()
        assertFalse(reducer.acceptSnapshot(UserActivityState.IN_VEHICLE, 90, nanos(1), nanos(2)))
        assertFalse(reducer.acceptSnapshot(UserActivityState.IN_VEHICLE, 90, nanos(2), nanos(63)))
        assertFalse(reducer.acceptSnapshot(UserActivityState.IN_VEHICLE, 90, nanos(4), nanos(3)))
        assertTrue(reducer.acceptSnapshot(UserActivityState.IN_VEHICLE, 90, nanos(3), nanos(3)))
        assertFalse(reducer.acceptSnapshot(UserActivityState.WALKING, 90, nanos(3), nanos(3)))
        assertFalse(reducer.acceptSnapshot(UserActivityState.WALKING, 90, nanos(2), nanos(3)))
        assertEquals(UserActivityState.IN_VEHICLE, reducer.observation?.state)
    }

    @Test
    fun snapshotCannotOverwriteTransitionEvenIfSnapshotIsNewer() {
        val reducer = started()
        reducer.acceptTransition(enter(UserActivityState.WALKING, 2), nanos(2))
        assertFalse(reducer.acceptSnapshot(UserActivityState.IN_VEHICLE, 100, nanos(3), nanos(3)))
        reducer.acceptTransition(exit(UserActivityState.WALKING, 4), nanos(4))
        assertFalse(reducer.acceptSnapshot(UserActivityState.WALKING, 100, nanos(5), nanos(5)))
        assertEquals(ActivityObservation(UserActivityState.UNKNOWN, nanos(4)), reducer.observation)
    }

    @Test
    fun initialOnFootIsClearedByPedestrianExit() {
        listOf(UserActivityState.WALKING, UserActivityState.RUNNING).forEach { state ->
            val reducer = started()
            reducer.acceptSnapshot(UserActivityState.ON_FOOT, 80, nanos(2), nanos(2))
            reducer.acceptTransition(exit(state, 3), nanos(3))
            assertEquals(UserActivityState.UNKNOWN, reducer.observation?.state)
        }
    }

    @Test
    fun unknownTransitionAndUnmatchedExitDoNotInventMovement() {
        val reducer = started()
        assertFalse(reducer.acceptTransition(enter(UserActivityState.UNKNOWN, 2), nanos(2)))
        assertTrue(reducer.acceptTransition(exit(UserActivityState.WALKING, 2), nanos(2)))
        assertNull(reducer.observation)
        assertFalse(reducer.acceptSnapshot(UserActivityState.WALKING, 90, nanos(2), nanos(3)))
        assertTrue(reducer.acceptSnapshot(UserActivityState.WALKING, 90, nanos(3), nanos(3)))
    }

    @Test
    fun stateMovementContributionHasExplicitUnavailableCase() {
        listOf(UserActivityState.WALKING, UserActivityState.RUNNING, UserActivityState.ON_FOOT,
            UserActivityState.IN_VEHICLE).forEach { assertEquals(true, it.isMoving) }
        assertEquals(false, UserActivityState.STILL.isMoving)
        assertNull(UserActivityState.UNKNOWN.isMoving)
    }

    private fun started() = ActivityObservationReducer().also { it.start(nanos(1)) }
    private fun enter(state: UserActivityState, seconds: Long) =
        MeasuredActivityTransition(state, ActivityTransitionKind.ENTER, nanos(seconds))
    private fun exit(state: UserActivityState, seconds: Long) =
        MeasuredActivityTransition(state, ActivityTransitionKind.EXIT, nanos(seconds))
    private fun nanos(seconds: Long) = seconds * 1_000_000_000L
}
