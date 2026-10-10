package au.edu.unimelb.campuscompanion.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Only the class matching information is sent, never the private calendar URL or event UID. */
@Serializable
data class TimetableGroupSpec(
    @SerialName("course_code") val courseCode: String,
    val name: String,
    val activity: String,
    val day: Int? = null,
    val start: String? = null,
    val end: String? = null,
    val location: String? = null
) {
    val key: String get() = if (activity == "course") "$courseCode|course"
        else "$courseCode|$activity|$day|$start|$end|$location"
}
