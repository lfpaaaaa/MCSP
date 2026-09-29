package au.edu.unimelb.campuscompanion.data.building

/** Campus buildings by location code, as used in timetable locations such as "PAR-160". */
interface BuildingLookup {
    /** The building with this code ("PAR-160" or "PAR;160"), or null when it is unknown. */
    fun findByLocationCode(locationCode: String): BuildingLocation?

    fun getAllBuildings(): List<BuildingLocation>
}
