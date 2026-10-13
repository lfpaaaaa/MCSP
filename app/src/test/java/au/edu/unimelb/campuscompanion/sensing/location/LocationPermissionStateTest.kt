package au.edu.unimelb.campuscompanion.sensing.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPermissionStateTest {
    @Test
    fun approximateOnlyColdStartNeverStartsThePreciseSensingService() {
        for (sdk in listOf(26, 28, 29, 30, 31, 35, 37)) {
            val permission = LocationPermissionState(false, true, false, sdk)
            assertTrue(permission.foregroundGranted)
            assertFalse("SDK $sdk", permission.canStartSensing)
            assertFalse("SDK $sdk", permission.canMonitorBackgroundArrival)
        }
    }

    @Test
    fun deniedLocationNeverStartsSensingOrGeofences() {
        val permission = LocationPermissionState(false, false, false, 35)
        assertFalse(permission.foregroundGranted)
        assertFalse(permission.canStartSensing)
        assertFalse(permission.canMonitorBackgroundArrival)
        assertEquals(BackgroundLocationRequest.PreciseForegroundRequired, permission.backgroundRequest)
    }

    @Test
    fun preciseForegroundSensingDoesNotRequireBackgroundOptIn() {
        val permission = LocationPermissionState(true, true, false, 35)
        assertTrue(permission.canStartSensing)
        assertFalse(permission.canMonitorBackgroundArrival)
    }

    @Test
    fun android10RequestsBackgroundSeparatelyThroughRuntimePermission() {
        val permission = LocationPermissionState(true, true, false, 29)
        assertEquals(BackgroundLocationRequest.RuntimePermission, permission.backgroundRequest)
    }

    @Test
    fun android11AndLaterUseSettingsForBackgroundAccess() {
        for (sdk in listOf(30, 31, 35, 37)) {
            assertEquals(BackgroundLocationRequest.Settings,
                LocationPermissionState(true, true, false, sdk).backgroundRequest)
        }
    }

    @Test
    fun approximateAccessCannotSkipForegroundRequirementEvenWithBackgroundGrant() {
        val permission = LocationPermissionState(false, true, true, 35)
        assertEquals(BackgroundLocationRequest.PreciseForegroundRequired, permission.backgroundRequest)
        assertFalse(permission.canMonitorBackgroundArrival)
    }

    @Test
    fun legacyAndroidNeedsOnlyPreciseLocationForGeofences() {
        val permission = LocationPermissionState(true, true, false, 28)
        assertTrue(permission.canMonitorBackgroundArrival)
        assertEquals(BackgroundLocationRequest.AlreadyAllowed, permission.backgroundRequest)
    }

    @Test
    fun backgroundGrantAndRevocationChangeGeofenceEligibility() {
        val granted = LocationPermissionState(true, true, true, 35)
        assertTrue(granted.canMonitorBackgroundArrival)
        assertEquals(BackgroundLocationRequest.AlreadyAllowed, granted.backgroundRequest)
        val revoked = granted.copy(background = false)
        assertTrue(revoked.canStartSensing)
        assertFalse(revoked.canMonitorBackgroundArrival)
        assertEquals(BackgroundLocationRequest.Settings, revoked.backgroundRequest)
    }
}
