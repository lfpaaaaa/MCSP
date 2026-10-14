package au.edu.unimelb.campuscompanion.data

import java.time.Instant

interface TimetableRepository {
    suspend fun load(ownerId: String): TimetableSnapshot
    suspend fun replaceImport(ownerId: String, url: String, imported: TimetableImport, syncedAt: Instant): TimetableSnapshot
    suspend fun disconnect(ownerId: String): TimetableSnapshot
    suspend fun upsertLocal(ownerId: String, row: LocalClass): TimetableSnapshot
    suspend fun deleteLocal(ownerId: String, id: String): TimetableSnapshot
}

/** The preference store is only read during upgrade and removed with a confirmed disk write. */
interface TimetableLegacyStorage {
    fun readLegacy(ownerId: String): SavedTimetable
    suspend fun clearLegacy(ownerId: String): Boolean
}
