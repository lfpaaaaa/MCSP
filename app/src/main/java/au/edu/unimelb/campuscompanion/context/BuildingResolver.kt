package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.building.BuildingLocation
import au.edu.unimelb.campuscompanion.data.building.BuildingLookup
import au.edu.unimelb.campuscompanion.ui.model.CourseSession

/**
 * Finds the building of a class from the location text of the timetable. The calendar feed gives
 * locations such as "Parkville Campus, PAR-160" or "PAR-160-B117", which the importer splits into
 * [CourseSession.location] and [CourseSession.room]; the campus code and building number in them
 * identify the building. Buildings named in words ("PAR-Alan Gilbert-104") are found by name when
 * exactly one building matches.
 */
object BuildingResolver {
    private val SEPARATORS = Regex("[-;,/]")
    private val CAMPUS_CODE = Regex("[A-Z]{3}")
    private val NUMBER = Regex("\\d{1,4}")
    private const val MIN_NAME_LENGTH = 4

    fun resolve(session: CourseSession, buildings: BuildingLookup): BuildingLocation? =
        listOf(session.room, session.location)
            .filter(String::isNotBlank)
            .firstNotNullOfOrNull { text -> resolve(text, buildings) }

    fun resolve(locationText: String, buildings: BuildingLookup): BuildingLocation? {
        val parts = locationText.split(SEPARATORS).map(String::trim).filter(String::isNotBlank)
        val campus = parts.firstOrNull { CAMPUS_CODE.matches(it) }

        if (campus != null) {
            parts.filter { NUMBER.matches(it) }.forEach { number ->
                buildings.findByLocationCode("$campus-$number")?.let { return it }
            }
        }

        val names = parts.filter { part ->
            part != campus && part.length >= MIN_NAME_LENGTH && part.any(Char::isLetter)
        }
        for (name in names) {
            val matches = buildings.getAllBuildings().filter { building ->
                building.name.contains(name, ignoreCase = true) &&
                    (campus == null || building.campusCode.equals(campus, ignoreCase = true))
            }
            if (matches.size == 1) {
                return matches.single()
            }
        }
        return null
    }
}
