package au.edu.unimelb.campuscompanion.data.local

import androidx.room.Entity
import au.edu.unimelb.campuscompanion.data.LocalClass
import au.edu.unimelb.campuscompanion.data.LocalClassKind
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import java.time.ZonedDateTime

@Entity(tableName = "local_classes", primaryKeys = ["ownerId", "id"])
data class LocalClassEntity(
    val ownerId: String,
    val id: String,
    val kind: String,
    val code: String?,
    val title: String?,
    val activity: String?,
    val location: String?,
    val room: String?,
    val startIso: String?,
    val endIso: String?,
    val originalStartIso: String?
) {
    fun toDomain(): LocalClass {
        val decodedKind = LocalClassKind.valueOf(kind)
        val payload = if (decodedKind == LocalClassKind.HIDDEN) {
            require(listOf(code, title, activity, location, room, startIso, endIso).all { it == null }) {
                "A hidden class must not have a corrected payload."
            }
            null
        } else CourseSession(id, requireNotNull(code), requireNotNull(title), requireNotNull(location),
            requireNotNull(room), ZonedDateTime.parse(requireNotNull(startIso)),
            ZonedDateTime.parse(requireNotNull(endIso)), activity = activity)
        return LocalClass(id, decodedKind, payload, originalStartIso?.let(ZonedDateTime::parse))
            .also { it.validate() }
    }

    companion object {
        fun from(ownerId: String, row: LocalClass): LocalClassEntity {
            row.validate()
            val payload = row.session
            return LocalClassEntity(ownerId, row.id, row.kind.name, payload?.code, payload?.title,
                payload?.activity, payload?.location, payload?.room, payload?.start?.toString(),
                payload?.end?.toString(), row.originalStart?.toString())
        }
    }
}
