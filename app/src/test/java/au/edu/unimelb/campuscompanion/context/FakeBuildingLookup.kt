package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.building.BuildingLocation
import au.edu.unimelb.campuscompanion.data.building.BuildingLookup
import au.edu.unimelb.campuscompanion.data.model.GeoPoint

/** A few Parkville buildings, keyed like the campus data ("PAR;160"). */
class FakeBuildingLookup(
    private val buildings: List<BuildingLocation> = listOf(PETER_HALL, ALAN_GILBERT, FBE)
) : BuildingLookup {

    override fun findByLocationCode(locationCode: String): BuildingLocation? {
        val code = locationCode.trim().replace("-", ";")
        return buildings.firstOrNull { it.locCode.equals(code, ignoreCase = true) }
    }

    override fun getAllBuildings(): List<BuildingLocation> = buildings

    companion object {
        val PETER_HALL = BuildingLocation(
            locCode = "PAR;160",
            buildingNumber = "160",
            campusCode = "PAR",
            name = "Peter Hall Building 160",
            address = "230 Grattan Street PARKVILLE VIC 3010",
            location = GeoPoint(-37.7994, 144.9608)
        )
        val ALAN_GILBERT = BuildingLocation(
            locCode = "PAR;104",
            buildingNumber = "104",
            campusCode = "PAR",
            name = "Alan Gilbert Building 104",
            address = "161 Barry Street CARLTON VIC 3053",
            location = GeoPoint(-37.8013, 144.9587)
        )
        val FBE = BuildingLocation(
            locCode = "PAR;105",
            buildingNumber = "105",
            campusCode = "PAR",
            name = "Melbourne University FBE Building 105",
            address = "95-129 Barry Street CARLTON VIC 3053",
            location = GeoPoint(-37.8017, 144.9583)
        )
    }
}
