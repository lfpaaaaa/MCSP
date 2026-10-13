package au.edu.unimelb.campuscompanion.data.local

import androidx.room.Entity
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import java.time.ZonedDateTime

@Entity(tableName = "imported_classes", primaryKeys = ["ownerId", "id"])
data class ImportedClassEntity(
    val ownerId: String,
    val id: String,
    val code: String,
    val title: String,
    val activity: String?,
    val location: String,
    val room: String,
    val startIso: String,
    val endIso: String
) {
    fun toDomain(): CourseSession = CourseSession(id, code, title, location, room,
        ZonedDateTime.parse(startIso), ZonedDateTime.parse(endIso), activity = activity)

    companion object {
        fun from(ownerId: String, session: CourseSession): ImportedClassEntity = ImportedClassEntity(
            ownerId, session.id, session.code, session.title, session.activity, session.location,
            session.room, session.start.toString(), session.end.toString()
        )
    }
}
