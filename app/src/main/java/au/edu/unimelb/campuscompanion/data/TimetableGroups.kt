package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.data.model.TimetableGroupSpec
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import java.text.Normalizer
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val campusZone = ZoneId.of("Australia/Melbourne")
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/** Weekly wall-clock slots survive daylight saving and the viewer's device time zone. */
fun CourseSession.timetableGroupSpec(): TimetableGroupSpec? {
    val kind = activity?.takeIf { it == "tutorial" || it == "workshop" } ?: return null
    if (code == "EVENT" || location == "Location not provided") return null
    val place = Normalizer.normalize(listOf(location, room).filter(String::isNotBlank).joinToString(", "), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").replace(Regex("\\s*,\\s*"), ",").trim()
    if (place.isBlank()) return null
    val campusStart = start.withZoneSameInstant(campusZone)
    val campusEnd = end.withZoneSameInstant(campusZone)
    return TimetableGroupSpec(
        courseCode = code, name = "$code-$kind",
        activity = kind, day = campusStart.dayOfWeek.value,
        start = campusStart.format(timeFormat), end = campusEnd.format(timeFormat), location = place
    )
}

/** The slot shown under a timetable group ("Tue 10:00–11:00 · location"), or null when it has no day. */
fun TimetableGroupSpec.slotLabel(): String? = day?.let {
    "${listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[it - 1]} $start–$end · $location"
}
