package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BuildingResolverTest {
    private val buildings = FakeBuildingLookup()

    @Test
    fun theBuildingCodeInTheRoomIsFound() {
        val session = session(location = "Parkville Campus", room = "PAR-160")

        assertEquals(FakeBuildingLookup.PETER_HALL, BuildingResolver.resolve(session, buildings))
    }

    @Test
    fun roomNumbersAfterTheBuildingAreIgnored() {
        assertEquals(FakeBuildingLookup.PETER_HALL, BuildingResolver.resolve("PAR-160-B117", buildings))
        assertEquals(FakeBuildingLookup.PETER_HALL, BuildingResolver.resolve("PAR;160;Theatre 1", buildings))
    }

    @Test
    fun buildingsNamedInWordsAreFoundByName() {
        assertEquals(FakeBuildingLookup.ALAN_GILBERT, BuildingResolver.resolve("PAR-Alan Gilbert-104", buildings))
        assertEquals(FakeBuildingLookup.ALAN_GILBERT, BuildingResolver.resolve("Alan Gilbert Building", buildings))
    }

    @Test
    fun ambiguousOrUnknownLocationsGiveNoBuilding() {
        assertNull(BuildingResolver.resolve("Building", buildings))
        assertNull(BuildingResolver.resolve("Parkville Campus", buildings))
        assertNull(BuildingResolver.resolve("SHS-160", buildings))
        assertNull(BuildingResolver.resolve("", buildings))
    }

    @Test
    fun theLocationIsUsedWhenTheRoomSaysNothing() {
        val session = session(location = "PAR-105", room = "")

        assertEquals(FakeBuildingLookup.FBE, BuildingResolver.resolve(session, buildings))
    }

    private fun session(location: String, room: String): CourseSession {
        val start = ZonedDateTime.of(2026, 10, 5, 10, 0, 0, 0, ZoneId.of("Australia/Melbourne"))
        return CourseSession(
            id = "s1",
            code = "COMP90018",
            title = "Lecture",
            location = location,
            room = room,
            start = start,
            end = start.plusHours(1)
        )
    }
}
