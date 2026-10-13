package au.edu.unimelb.campuscompanion.sensing.location

import au.edu.unimelb.campuscompanion.data.building.BuildingLocation
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceRegistrationControllerTest {
    @Test
    fun startupRemovesPersistentRegistrationsBeforeAddingCurrentTarget() {
        val controller = controller()
        controller.setTarget(target(1))
        assertEquals(GeofenceRegistrationOperation.RemoveOrphans, controller.nextOperation())
        assertNull(controller.nextOperation())
        assertNull(controller.currentRegistration(requireNotNull(controller.desired).requestId))
        controller.complete(GeofenceRegistrationOperation.RemoveOrphans, true)
        assertTrue(controller.nextOperation() is GeofenceRegistrationOperation.Add)
    }

    @Test
    fun onlySuccessfulCurrentAddCanForwardEntry() {
        val controller = ready()
        controller.setTarget(target(1))
        val add = controller.nextOperation() as GeofenceRegistrationOperation.Add
        assertNull(controller.currentRegistration(add.registration.requestId))
        controller.complete(add, true)
        assertEquals(add.registration, controller.currentRegistration(add.registration.requestId))
        assertNull(controller.currentRegistration("wrong-id"))
        assertNull(controller.nextOperation())
    }

    @Test
    fun failedAddCannotForwardAndRepeatedTargetDoesNotRetryInATightLoop() {
        val controller = ready()
        controller.setTarget(target(1))
        val add = controller.nextOperation() as GeofenceRegistrationOperation.Add
        controller.complete(add, false)
        controller.setTarget(target(1))
        assertNull(controller.currentRegistration(add.registration.requestId))
        assertNull(controller.nextOperation())
    }

    @Test
    fun identicalTargetRetainsSuccessfulRegistrationAndRequestId() {
        val controller = ready()
        val current = registered(controller, target(1))
        controller.setTarget(target(1))
        assertEquals(current, controller.registered)
        assertEquals(current, controller.desired)
        assertNull(controller.nextOperation())
    }

    @Test
    fun replacementInvalidatesImmediatelyAndRemovesBeforeNewAdd() {
        val controller = ready()
        val old = registered(controller, target(1))
        controller.setTarget(target(2, "B"))
        assertNull(controller.currentRegistration(old.requestId))
        val remove = controller.nextOperation() as GeofenceRegistrationOperation.Remove
        assertEquals(old, remove.registration)
        assertNull(controller.nextOperation())
        controller.complete(remove, true)
        val add = controller.nextOperation() as GeofenceRegistrationOperation.Add
        assertEquals("B", add.registration.buildingCode)
    }

    @Test
    fun lateAddSuccessIsRemovedBeforeReplacementCanBeRegistered() {
        val controller = ready()
        controller.setTarget(target(1))
        val oldAdd = controller.nextOperation() as GeofenceRegistrationOperation.Add
        controller.setTarget(target(2, "B"))
        assertNull(controller.nextOperation())
        controller.complete(oldAdd, true)
        assertNull(controller.currentRegistration(oldAdd.registration.requestId))
        val remove = controller.nextOperation() as GeofenceRegistrationOperation.Remove
        assertEquals(oldAdd.registration, remove.registration)
        controller.complete(remove, true)
        val newAdd = controller.nextOperation() as GeofenceRegistrationOperation.Add
        assertEquals(2L, newAdd.registration.generation)
        controller.complete(newAdd, true)
        assertEquals(newAdd.registration, controller.currentRegistration(newAdd.registration.requestId))
    }

    @Test
    fun stopDuringAddInvalidatesAndCleansUpLateSuccessWithoutAddingAgain() {
        val controller = ready()
        controller.setTarget(target(1))
        val add = controller.nextOperation() as GeofenceRegistrationOperation.Add
        controller.setTarget(null)
        controller.complete(add, true)
        assertNull(controller.currentRegistration(add.registration.requestId))
        val remove = controller.nextOperation() as GeofenceRegistrationOperation.Remove
        controller.complete(remove, true)
        assertFalse(controller.hasPendingCleanup)
        assertNull(controller.nextOperation())
    }

    @Test
    fun rapidReturnToSameBuildingCannotAcceptEarlierOccurrenceId() {
        val controller = ready()
        val old = registered(controller, target(1))
        controller.setTarget(target(2, "B"))
        controller.setTarget(target(3))
        val current = requireNotNull(controller.desired)
        assertEquals(old.buildingCode, current.buildingCode)
        assertNotEquals(old.requestId, current.requestId)
        assertNotEquals(old.generation, current.generation)
        assertNull(controller.currentRegistration(old.requestId))
        val remove = controller.nextOperation() as GeofenceRegistrationOperation.Remove
        controller.complete(remove, true)
        val add = controller.nextOperation() as GeofenceRegistrationOperation.Add
        assertEquals(current, add.registration)
    }

    @Test
    fun replacementsDuringRemovalCoalesceToLatestTarget() {
        val controller = ready()
        registered(controller, target(1))
        controller.setTarget(target(2, "B"))
        val remove = controller.nextOperation() as GeofenceRegistrationOperation.Remove
        controller.setTarget(target(3, "C"))
        controller.complete(remove, true)
        val add = controller.nextOperation() as GeofenceRegistrationOperation.Add
        assertEquals("C", add.registration.buildingCode)
    }

    @Test
    fun failedCleanupBlocksReplacementUntilLaterRetrySucceeds() {
        val controller = ready()
        registered(controller, target(1))
        controller.setTarget(target(2, "B"))
        val remove = controller.nextOperation() as GeofenceRegistrationOperation.Remove
        controller.complete(remove, false)
        assertTrue(controller.hasFailedCleanup)
        assertNull(controller.nextOperation())
        controller.retryCleanup()
        assertEquals(remove, controller.nextOperation())
        controller.complete(remove, true)
        assertFalse(controller.hasFailedCleanup)
        assertTrue(controller.nextOperation() is GeofenceRegistrationOperation.Add)
    }

    @Test
    fun failedOrphanCleanupDoesNotAddUntilSuccessfulLifecycleRetry() {
        val controller = controller()
        controller.setTarget(target(1))
        val cleanup = requireNotNull(controller.nextOperation())
        controller.complete(cleanup, false)
        assertTrue(controller.hasFailedCleanup)
        assertNull(controller.nextOperation())
        controller.retryCleanup()
        assertEquals(cleanup, controller.nextOperation())
        controller.complete(cleanup, true)
        assertTrue(controller.nextOperation() is GeofenceRegistrationOperation.Add)
    }

    @Test
    fun permissionLossThenRegrantCreatesNewIdEvenInSameSensingGeneration() {
        val controller = ready()
        val old = registered(controller, target(1))
        controller.setTarget(null)
        assertNull(controller.currentRegistration(old.requestId))
        controller.setTarget(target(1))
        assertNotEquals(old.requestId, requireNotNull(controller.desired).requestId)
        val remove = controller.nextOperation() as GeofenceRegistrationOperation.Remove
        controller.complete(remove, true)
        assertTrue(controller.nextOperation() is GeofenceRegistrationOperation.Add)
    }

    @Test
    fun inactiveOrUnknownTargetRemovesExistingRegistration() {
        listOf(target(2).copy(active = false), target(2).copy(building = null)).forEach { next ->
            val controller = ready()
            val old = registered(controller, target(1))
            controller.setTarget(next)
            assertNull(controller.desired)
            assertNull(controller.currentRegistration(old.requestId))
            assertTrue(controller.nextOperation() is GeofenceRegistrationOperation.Remove)
        }
    }

    @Test
    fun recreatedProcessUsesUniqueIdsAndRejectsEarlierPersistentId() {
        val before = GeofenceRegistrationController()
        before.setTarget(target(1))
        val old = requireNotNull(before.desired)
        val after = GeofenceRegistrationController()
        after.setTarget(target(1))
        assertNotEquals(old.requestId, requireNotNull(after.desired).requestId)
        assertNull(after.currentRegistration(old.requestId))
    }

    private fun controller(): GeofenceRegistrationController {
        var nextId = 0
        return GeofenceRegistrationController { "arrival-${++nextId}" }
    }

    private fun ready() = controller().also {
        val cleanup = requireNotNull(it.nextOperation())
        it.complete(cleanup, true)
    }

    private fun registered(controller: GeofenceRegistrationController, target: SensingTarget): ArrivalGeofenceRegistration {
        controller.setTarget(target)
        val add = controller.nextOperation() as GeofenceRegistrationOperation.Add
        controller.complete(add, true)
        return add.registration
    }

    private fun target(generation: Long, buildingCode: String = "A") = SensingTarget(
        generation, generation * 1_000_000_000L, true, "class-$generation",
        BuildingLocation(buildingCode, "1", "P", buildingCode, "", GeoPoint(-37.798, 144.960))
    )
}
