package au.edu.unimelb.campuscompanion.data

import android.util.Log
import androidx.room.withTransaction
import au.edu.unimelb.campuscompanion.data.local.ImportedClassEntity
import au.edu.unimelb.campuscompanion.data.local.LocalClassEntity
import au.edu.unimelb.campuscompanion.data.local.TimetableDatabase
import au.edu.unimelb.campuscompanion.data.local.TimetableSourceEntity
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.Instant

class RoomTimetableRepository(
    private val database: TimetableDatabase,
    private val legacy: TimetableLegacyStorage? = null
) : TimetableRepository {
    private val dao = database.timetableDao()

    override suspend fun load(ownerId: String): TimetableSnapshot {
        require(ownerId.isNotBlank())
        val existing = database.withTransaction {
            if (dao.source(ownerId) != null) readSnapshot(ownerId) else null
        }
        val snapshot = existing ?: run {
            val previous = legacy?.readLegacy(ownerId)
            // Prepare the copy before writes; failures leave both stores unchanged.
            val legacySource = previous?.takeIf { it.url.isNotBlank() }?.let {
                TimetableSourceEntity.from(ownerId, TimetableSource(it.url.trim(), it.savedAt,
                    it.timetable?.sourceEventCount ?: 0, it.timetable?.groups.orEmpty().mapNotNull { group -> group.timetableSpec }))
            }
            val legacyImported = previous?.timetable?.sessions.orEmpty().map { ImportedClassEntity.from(ownerId, it) }
            database.withTransaction {
                // A committed Room source always wins, including if another load copied it first.
                if (dao.source(ownerId) == null && legacySource != null) {
                    dao.putSource(legacySource)
                    dao.deleteImported(ownerId)
                    dao.putImported(legacyImported)
                }
                readSnapshot(ownerId)
            }
        }
        // Room is authoritative after commit, including when cleanup must be retried next load.
        if (snapshot.source != null) try {
            if (legacy?.clearLegacy(ownerId) == false) {
                Log.w("TimetableStorage", "Legacy timetable cleanup failed; the next load will retry it.")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A committed copy remains usable; the next initialization retries cleanup.
            Log.w("TimetableStorage", "Legacy timetable cleanup failed; the next load will retry it.")
        }
        return snapshot
    }

    override suspend fun replaceImport(ownerId: String, url: String, imported: TimetableImport,
        syncedAt: Instant): TimetableSnapshot {
        require(ownerId.isNotBlank())
        val acceptedUrl = url.trim()
        require(acceptedUrl.isNotBlank())
        val source = TimetableSourceEntity.from(ownerId, TimetableSource(acceptedUrl, syncedAt,
            imported.sourceEventCount, imported.groups.mapNotNull { it.timetableSpec }))
        val rows = imported.sessions.map { ImportedClassEntity.from(ownerId, it) }
        return database.withTransaction {
            val oldUrl = dao.source(ownerId)?.url
            if (oldUrl != acceptedUrl) dao.deleteAdjustments(ownerId)
            dao.deleteImported(ownerId)
            dao.putSource(source)
            dao.putImported(rows)
            readSnapshot(ownerId)
        }
    }

    override suspend fun disconnect(ownerId: String): TimetableSnapshot {
        require(ownerId.isNotBlank())
        if (legacy?.clearLegacy(ownerId) == false) throw IOException("The saved timetable could not be removed. Try again.")
        return database.withTransaction {
            dao.deleteSource(ownerId)
            dao.deleteImported(ownerId)
            dao.deleteAdjustments(ownerId)
            readSnapshot(ownerId)
        }
    }

    override suspend fun upsertLocal(ownerId: String, row: LocalClass): TimetableSnapshot {
        require(ownerId.isNotBlank())
        return database.withTransaction {
            val existing = dao.local(ownerId).firstOrNull { it.id == row.id }?.toDomain()
            require(existing == null || (existing.kind == LocalClassKind.MANUAL) == (row.kind == LocalClassKind.MANUAL)) {
                "A class cannot change between manual and imported identity."
            }
            val validated = when (row.kind) {
                LocalClassKind.EDITED -> {
                    val anchor = existing?.originalStart
                        ?: dao.imported(ownerId).firstOrNull { it.id == row.id }?.toDomain()?.start
                        ?: throw IllegalArgumentException("The original imported class is unavailable.")
                    row.copy(originalStart = anchor)
                }
                // Hide/Undo must keep an edit's anchor even if its original disappears meanwhile.
                LocalClassKind.HIDDEN -> row.copy(originalStart = existing?.originalStart ?: row.originalStart)
                LocalClassKind.MANUAL -> row
            }
            val entity = LocalClassEntity.from(ownerId, validated)
            dao.putLocal(entity)
            readSnapshot(ownerId)
        }
    }

    override suspend fun deleteLocal(ownerId: String, id: String): TimetableSnapshot {
        require(ownerId.isNotBlank())
        require(id.isNotBlank())
        return database.withTransaction {
            dao.deleteLocal(ownerId, id)
            readSnapshot(ownerId)
        }
    }

    private suspend fun readSnapshot(ownerId: String): TimetableSnapshot = TimetableSnapshot(
        source = dao.source(ownerId)?.toDomain(),
        imported = dao.imported(ownerId).map { it.toDomain() }
            .sortedWith(compareBy({ it.start.toInstant() }, { it.id })),
        local = dao.local(ownerId).map { it.toDomain() }.sortedBy { it.id }
    )
}
