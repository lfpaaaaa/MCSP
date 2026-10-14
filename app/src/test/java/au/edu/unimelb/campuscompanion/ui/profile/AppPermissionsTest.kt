package au.edu.unimelb.campuscompanion.ui.profile

import au.edu.unimelb.campuscompanion.english
import au.edu.unimelb.campuscompanion.sensing.location.LocationPermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPermissionsTest {
    @Test
    fun locationIsGrantedWithEitherAccuracyAndSaysWhichOne() {
        val precise = AppPermissions.location(fine = true, coarse = true)
        val approximate = AppPermissions.location(fine = false, coarse = true)
        val none = AppPermissions.location(fine = false, coarse = false)

        assertTrue(precise.granted)
        assertEquals("Precise location", precise.detail.english())
        assertTrue(approximate.granted)
        assertTrue(approximate.detail.english().startsWith("Approximate location only"))
        assertTrue(approximate.detail.english().contains("timetable-only"))
        assertTrue(approximate.detail.english().contains("without fresh travel times or arrival detection"))
        assertFalse(none.granted)
        assertTrue(none.detail.english().startsWith("Not allowed"))
        assertEquals(AppPermission.Location, none.permission)
    }

    @Test
    fun theOtherPermissionsReportAllowedOrExplainWhatIsMissing() {
        assertEquals("Allowed", AppPermissions.notifications(allowed = true).detail.english())
        assertFalse(AppPermissions.notifications(allowed = false).granted)
        assertTrue(AppPermissions.notifications(allowed = false).detail.english().contains("silent"))

        assertEquals("Allowed", AppPermissions.camera(granted = true).detail.english())
        assertTrue(AppPermissions.camera(granted = false).detail.english().contains("take a photo"))

        assertEquals("Allowed", AppPermissions.physicalActivity(granted = true).detail.english())
        assertTrue(AppPermissions.physicalActivity(granted = false).detail.english().contains("walking fallback"))
    }

    @Test
    fun backgroundAccessNeedsPreciseLocationEvenWhenAndroidGrantedBackgroundPermission() {
        val state = AppPermissions.backgroundArrival(LocationPermissionState(false, true, true, 35))
        assertFalse(state.granted)
        assertTrue(state.detail.english().contains("Background access allowed"))
        assertTrue(state.detail.english().contains("precise location is needed"))
    }

    @Test
    fun foregroundOnlyPreciseAccessExplainsThatGpsArrivalStillWorks() {
        val state = AppPermissions.backgroundArrival(LocationPermissionState(true, true, false, 35))
        assertFalse(state.granted)
        assertTrue(state.detail.english().contains("GPS arrival stays available"))
    }

    @Test
    fun backgroundProfileStatusReflectsGrantAndRevocation() {
        val allowed = AppPermissions.backgroundArrival(LocationPermissionState(true, true, true, 35))
        val revoked = AppPermissions.backgroundArrival(LocationPermissionState(true, true, false, 35))
        assertTrue(allowed.granted)
        assertEquals("Allowed all the time with precise location", allowed.detail.english())
        assertFalse(revoked.granted)
    }

    @Test
    fun legacyAndroidDoesNotNeedASeparateBackgroundGrant() {
        val state = AppPermissions.backgroundArrival(LocationPermissionState(true, true, false, 28))
        assertTrue(state.granted)
        assertTrue(state.detail.english().contains("this Android version"))
    }

    @Test
    fun microphoneStatusRetainsRecordingPermissionSupport() {
        assertEquals("Allowed", AppPermissions.microphone(granted = true).detail.english())
        assertFalse(AppPermissions.microphone(granted = false).granted)
        assertTrue(AppPermissions.microphone(granted = false).detail.english().contains("recording"))
    }

    @Test
    fun everyPermissionHasATitleAndAPurpose() {
        AppPermission.entries.forEach { permission ->
            assertTrue(permission.name, permission.title.english().isNotBlank())
            assertTrue(permission.name, permission.purpose.english().endsWith("."))
        }
    }
}
