package au.edu.unimelb.campuscompanion.ui.profile

import au.edu.unimelb.campuscompanion.sensing.location.LocationPermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPermissionsTest {
    @Test
    fun microphoneExplainsOnDemandVoicePermissionWithoutChangingOtherPermissions() {
        assertEquals(listOf("android.permission.RECORD_AUDIO"), AppPermission.Microphone.runtimePermissions)
        assertEquals(AppPermission.Microphone, AppPermissions.microphone(false).permission)
        assertFalse(AppPermissions.microphone(false).granted)
        assertTrue(AppPermissions.microphone(false).detail.contains("start a voice recording"))
        assertEquals("Allowed", AppPermissions.microphone(true).detail)
        assertTrue(AppPermissions.microphone(true).granted)
    }

    @Test
    fun locationIsGrantedWithEitherAccuracyAndSaysWhichOne() {
        val precise = AppPermissions.location(fine = true, coarse = true)
        val approximate = AppPermissions.location(fine = false, coarse = true)
        val none = AppPermissions.location(fine = false, coarse = false)

        assertTrue(precise.granted)
        assertEquals("Precise location", precise.detail)
        assertTrue(approximate.granted)
        assertTrue(approximate.detail.startsWith("Approximate location only"))
        assertTrue(approximate.detail.contains("timetable-only"))
        assertTrue(approximate.detail.contains("without fresh travel times or arrival detection"))
        assertFalse(none.granted)
        assertTrue(none.detail.startsWith("Not allowed"))
        assertEquals(AppPermission.Location, none.permission)
    }

    @Test
    fun theOtherPermissionsReportAllowedOrExplainWhatIsMissing() {
        assertEquals("Allowed", AppPermissions.notifications(allowed = true).detail)
        assertFalse(AppPermissions.notifications(allowed = false).granted)
        assertTrue(AppPermissions.notifications(allowed = false).detail.contains("silent"))

        assertEquals("Allowed", AppPermissions.camera(granted = true).detail)
        assertTrue(AppPermissions.camera(granted = false).detail.contains("take a photo"))

        assertEquals("Allowed", AppPermissions.physicalActivity(granted = true).detail)
        assertTrue(AppPermissions.physicalActivity(granted = false).detail.contains("walking fallback"))
    }

    @Test
    fun backgroundAccessNeedsPreciseLocationEvenWhenAndroidGrantedBackgroundPermission() {
        val state = AppPermissions.backgroundArrival(LocationPermissionState(false, true, true, 35))
        assertFalse(state.granted)
        assertTrue(state.detail.contains("Background access allowed"))
        assertTrue(state.detail.contains("precise location is needed"))
    }

    @Test
    fun foregroundOnlyPreciseAccessExplainsThatGpsArrivalStillWorks() {
        val state = AppPermissions.backgroundArrival(LocationPermissionState(true, true, false, 35))
        assertFalse(state.granted)
        assertTrue(state.detail.contains("GPS arrival stays available"))
    }

    @Test
    fun backgroundProfileStatusReflectsGrantAndRevocation() {
        val allowed = AppPermissions.backgroundArrival(LocationPermissionState(true, true, true, 35))
        val revoked = AppPermissions.backgroundArrival(LocationPermissionState(true, true, false, 35))
        assertTrue(allowed.granted)
        assertEquals("Allowed all the time with precise location", allowed.detail)
        assertFalse(revoked.granted)
    }

    @Test
    fun legacyAndroidDoesNotNeedASeparateBackgroundGrant() {
        val state = AppPermissions.backgroundArrival(LocationPermissionState(true, true, false, 28))
        assertTrue(state.granted)
        assertTrue(state.detail.contains("this Android version"))
    }

    @Test
    fun everyPermissionHasATitleAndAPurpose() {
        AppPermission.entries.forEach { permission ->
            assertTrue(permission.name, permission.title.isNotBlank())
            assertTrue(permission.name, permission.purpose.endsWith("."))
        }
    }
}
