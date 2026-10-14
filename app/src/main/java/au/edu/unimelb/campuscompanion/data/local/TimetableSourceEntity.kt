package au.edu.unimelb.campuscompanion.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import au.edu.unimelb.campuscompanion.data.TimetableSource
import au.edu.unimelb.campuscompanion.data.model.TimetableGroupSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

@Entity(tableName = "timetable_source")
data class TimetableSourceEntity(
    @PrimaryKey val ownerId: String,
    val url: String,
    val lastSyncedAt: String?,
    val sourceEventCount: Int,
    val groupsJson: String
) {
    fun toDomain(): TimetableSource = TimetableSource(url, lastSyncedAt?.let(Instant::parse),
        sourceEventCount, json.decodeFromString<List<TimetableGroupSpec>>(groupsJson))

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun from(ownerId: String, source: TimetableSource): TimetableSourceEntity = TimetableSourceEntity(
            ownerId, source.url, source.lastSyncedAt?.toString(), source.sourceEventCount,
            json.encodeToString(source.groups)
        )
    }
}
