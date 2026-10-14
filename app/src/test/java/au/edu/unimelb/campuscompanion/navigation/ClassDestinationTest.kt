package au.edu.unimelb.campuscompanion.navigation

import au.edu.unimelb.campuscompanion.data.building.BuildingLocation
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class ClassDestinationTest {
    private val building = BuildingLocation("PAR;379", "379", "PAR", "Teaching building", "123 Campus Street", GeoPoint(-37.799, 144.963))
    private fun session(activity: String?) = CourseSession("class-1", "COMP90018", "Mobile Computing Systems Programming,",
        "Parkville", "PAR-379-B1-B116", ZonedDateTime.parse("2026-10-12T11:00:00+11:00"),
        ZonedDateTime.parse("2026-10-12T12:00:00+11:00"), activity = activity)

    @Test fun `lecture tutorial and workshop keep their imported labels`() {
        listOf("lecture" to "Lecture", "tutorial" to "Tutorial", "workshop" to "Workshop").forEach { (input, label) ->
            val destination = session(input).toClassDestination(building)
            assertEquals(label, destination.activity)
            assertEquals("COMP90018 · Mobile Computing Systems Programming · $label", destination.mapLabel)
            assertEquals(building.location, destination.point)
            assertEquals("PAR;379", destination.buildingCode)
            assertTrue(destination.locationLabel.contains("B116"))
            assertEquals("Building 379 · Teaching building", destination.buildingLabel)
            assertTrue(destination.markerDetail.contains("123 Campus Street"))
        }
    }

    @Test fun `unknown activity is not incorrectly labelled lecture`() {
        assertNull(session(null).toClassDestination(building).activity)
    }

    @Test fun `legacy cached activity can be read from the course title`() {
        val destination = session(null).copy(title = "Computer Vision - Workshop").toClassDestination(building)
        assertEquals("Workshop", destination.activity)
    }

    @Test fun `switching to another room uses that buildings coordinates`() {
        val elsewhere = building.copy(locCode = "PAR;160", location = GeoPoint(-37.800, 144.966))
        val destination = session("tutorial").copy(room = "PAR-160-100").toClassDestination(elsewhere)
        assertEquals(elsewhere.location, destination.point)
        assertEquals("PAR;160", destination.buildingCode)
    }
}
