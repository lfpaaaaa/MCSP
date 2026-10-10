package au.edu.unimelb.campuscompanion.ui.profile

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
        assertEquals("Precise location", precise.detail)
        assertTrue(approximate.granted)
        assertTrue(approximate.detail.startsWith("Approximate location only"))
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
        assertTrue(AppPermissions.physicalActivity(granted = false).detail.contains("walking"))
    }

    @Test
    fun everyPermissionHasATitleAndAPurpose() {
        AppPermission.entries.forEach { permission ->
            assertTrue(permission.name, permission.title.isNotBlank())
            assertTrue(permission.name, permission.purpose.endsWith("."))
        }
    }
}
