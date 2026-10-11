package au.edu.unimelb.campuscompanion.navigation

import au.edu.unimelb.campuscompanion.data.building.BuildingLocation
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.reminderActivity
import au.edu.unimelb.campuscompanion.ui.model.trimTrailingCommas
import java.util.Locale

/** The class identity travels with its resolved building, even before GPS or a route is available. */
data class ClassDestination(
    val sessionId: String,
    val title: String,
    val address: String,
    val point: GeoPoint,
    val activity: String? = null,
    val room: String = "",
    val buildingCode: String = "",
    val buildingName: String = "",
    val buildingNumber: String = ""
) {
    val mapLabel: String get() = listOfNotNull(title, activity).joinToString(" · ")
    val buildingLabel: String get() = listOfNotNull(
        buildingNumber.trim().takeIf(String::isNotEmpty)?.let { "Building $it" },
        buildingName.trim().takeIf(String::isNotEmpty)
    ).distinct().joinToString(" · ").ifBlank { buildingCode.ifBlank { address } }
    val markerDetail: String get() = listOf(buildingLabel, locationLabel).filter(String::isNotBlank).distinct().joinToString("\n")
    val locationLabel: String get() = listOf(room, address).filter(String::isNotBlank).distinct().joinToString("\n")
}

fun CourseSession.toClassDestination(building: BuildingLocation) = ClassDestination(
    sessionId = id,
    title = listOf(code.trim(), title.trimTrailingCommas()).filter(String::isNotBlank).distinct().joinToString(" · "),
    address = building.address.trim().ifBlank { building.name },
    point = building.location,
    activity = reminderActivity()?.replaceFirstChar { it.titlecase(Locale.ROOT) },
    room = room.trim().ifBlank { location.trim() },
    buildingCode = building.locCode,
    buildingName = building.name.trim(),
    buildingNumber = building.buildingNumber.trim()
)
