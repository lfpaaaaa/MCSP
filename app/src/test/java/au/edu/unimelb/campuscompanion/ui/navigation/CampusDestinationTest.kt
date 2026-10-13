package au.edu.unimelb.campuscompanion.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CampusDestinationTest {
    @Test fun accessingHomeBeforeTheMenuDoesNotLeaveAnUninitializedDestination() {
        val start = CampusDestination.Home
        val menu = CampusDestination.topLevelDestinations
        assertSame(start, menu.first())
        assertEquals(listOf("home", "schedule", "groups", "profile"), menu.map { it.route })
    }
}
