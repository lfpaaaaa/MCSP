package au.edu.unimelb.campuscompanion.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.edu.unimelb.campuscompanion.data.local.ImportedClassEntity
import au.edu.unimelb.campuscompanion.data.local.LocalClassEntity
import au.edu.unimelb.campuscompanion.data.local.TimetableDatabase
import au.edu.unimelb.campuscompanion.data.model.TimetableGroupSpec
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomTimetableRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: TimetableDatabase
    private lateinit var preferences: SharedPreferences
    private lateinit var repository: RoomTimetableRepository
    private lateinit var legacy: TimetableSubscriptionStore
    private lateinit var fileName: String
    private val url = "https://calendar.example/alice"
    private val syncedAt = Instant.parse("2026-10-10T01:00:00.123456789Z")
    private val start = ZonedDateTime.parse("2026-10-12T09:00:00.123456789+11:00[Australia/Melbourne]")
    private val original = CourseSession("legacy-id-kept-verbatim", "COMP90018", "Mobile Computing",
        "Parkville", "PAR-160", start, start.plusHours(1), activity = "tutorial")
    private val manual = original.copy(id = "manual:60c1b456-f69d-4594-877b-60d03c2a68b7", title = "Study group")
    private val corrected = original.copy(title = "Corrected class", start = start.plusDays(4), end = start.plusDays(4).plusHours(1))

    @Before fun createDatabase() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        fileName = "timetable-test-${UUID.randomUUID()}.db"
        database = Room.inMemoryDatabaseBuilder(context, TimetableDatabase::class.java).build()
        preferences = context.getSharedPreferences("timetable-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        legacy = TimetableSubscriptionStore(preferences)
        repository = RoomTimetableRepository(database, legacy)
    }

    @After fun closeDatabase() {
        database.close()
        preferences.edit().clear().commit()
        context.deleteDatabase(fileName)
    }

    private fun imported(session: CourseSession = original, count: Int = 7): TimetableImport {
        val specs = listOf(TimetableGroupSpec(session.code, session.title, "course"), session.timetableGroupSpec()!!)
        return TimetableImport(listOf(session), specs.map { it.toTimetableGroup() }, count)
    }

    private suspend fun seedRows() {
        repository.replaceImport("alice", url, imported(), syncedAt)
        repository.upsertLocal("alice", LocalClass(manual.id, LocalClassKind.MANUAL, manual))
        repository.upsertLocal("alice", LocalClass(original.id, LocalClassKind.EDITED, corrected, start))
    }

    private fun seedLegacy(ownerId: String, sourceUrl: String, imported: TimetableImport, savedAt: Instant) {
        assertTrue(preferences.edit().putString("subscription_url_$ownerId", sourceUrl)
            .putString("snapshot_$ownerId", TimetableCache.encode(sourceUrl, imported, savedAt)).commit())
    }

    @Test fun sameUrlRefreshPreservesManualAndEditedRowsAndOriginalAnchor() = runBlocking<Unit> {
        seedRows()
        val refreshed = original.copy(title = "Changed source title", start = start.plusHours(1), end = start.plusHours(2))
        val snapshot = repository.replaceImport("alice", url, imported(refreshed), syncedAt.plusSeconds(10))
        assertEquals(2, snapshot.local.size)
        assertEquals(listOf(manual, corrected), snapshot.mergedSessions())
        val revised = corrected.copy(start = corrected.start.plusDays(1), end = corrected.end.plusDays(1))
        val updated = repository.upsertLocal("alice", LocalClass(original.id, LocalClassKind.EDITED, revised, refreshed.start))
        assertEquals(start, updated.local.single { it.kind == LocalClassKind.EDITED }.originalStart)
        assertEquals(syncedAt.plusSeconds(10), updated.source?.lastSyncedAt)
    }

    @Test fun newUrlAndDisconnectDropAdjustmentsWhileRetainingManualClasses() = runBlocking<Unit> {
        seedRows()
        repository.upsertLocal("alice", LocalClass("hidden-original", LocalClassKind.HIDDEN))
        val replaced = repository.replaceImport("alice", "https://calendar.example/new", imported(), syncedAt)
        assertEquals(listOf(LocalClass(manual.id, LocalClassKind.MANUAL, manual)), replaced.local)
        repository.upsertLocal("alice", LocalClass(original.id, LocalClassKind.HIDDEN))
        val disconnected = repository.disconnect("alice")
        assertNull(disconnected.source)
        assertTrue(disconnected.imported.isEmpty())
        assertEquals(listOf(manual), disconnected.mergedSessions())
        assertEquals(disconnected, repository.load("alice"))
    }

    @Test fun importedRestoreDeletesOnlyTheAdjustmentAndDormantEditRemainsStored() = runBlocking<Unit> {
        seedRows()
        val empty = repository.replaceImport("alice", url, TimetableImport(emptyList(), emptyList(), 0), syncedAt)
        assertEquals(listOf(manual), empty.mergedSessions())
        assertEquals(2, empty.local.size)
        val returned = repository.replaceImport("alice", url, imported(), syncedAt)
        assertTrue(returned.mergedSessions().contains(corrected))
        val restored = repository.deleteLocal("alice", original.id)
        assertTrue(restored.mergedSessions().contains(original))
        assertTrue(restored.mergedSessions().contains(manual))
    }

    @Test fun insertionFailureRollsBackSourceDeletionAndAdjustmentDeletion() = runBlocking<Unit> {
        seedRows()
        val before = repository.load("alice")
        // The second insertion conflicts after the old rows/source have already been replaced.
        val duplicate = imported().copy(sessions = listOf(original, original))
        assertTrue(runCatching { repository.replaceImport("alice", "https://calendar.example/new", duplicate, syncedAt.plusSeconds(10)) }.isFailure)
        assertEquals(before, repository.load("alice"))
    }

    @Test fun accountScopedRowsAndCleanupDoNotAffectAnotherOwner() = runBlocking<Unit> {
        seedRows()
        val bob = original.copy(title = "Bob's class")
        repository.replaceImport("bob", "https://calendar.example/bob", imported(bob), syncedAt)
        repository.upsertLocal("bob", LocalClass(manual.id, LocalClassKind.MANUAL, manual.copy(title = "Bob's manual")))
        val bobBefore = repository.load("bob")
        repository.disconnect("alice")
        assertEquals(bobBefore, repository.load("bob"))
        assertTrue(repository.load("charlie").mergedSessions().isEmpty())
    }

    @Test fun aggregateReadsNeverMixImportMetadataAndClassRows() = runBlocking<Unit> {
        repository.replaceImport("alice", url, imported(), syncedAt)
        coroutineScope {
            val writer = async {
                repeat(12) { index ->
                    val session = original.copy(title = "revision-$index")
                    repository.replaceImport("alice", url, imported(session, index), syncedAt.plusSeconds(index.toLong()))
                }
            }
            val reader = async {
                repeat(12) {
                    val snapshot = repository.load("alice")
                    assertEquals(snapshot.imported.single().title, snapshot.source?.groups?.first()?.name)
                }
            }
            writer.await()
            reader.await()
        }
    }

    @Test fun legacyCopyPreservesUnfilteredIdsFieldsTimestampAndSpecsThenRemovesOnlyOwnerKeys() = runBlocking<Unit> {
        seedLegacy("alice", url, imported(), syncedAt)
        seedLegacy("bob", "https://calendar.example/bob", imported(), syncedAt)
        preferences.edit().putString("other_setting", "retained").commit()
        val loaded = repository.load("alice")
        assertEquals(listOf(original), loaded.imported)
        assertEquals(syncedAt, loaded.source?.lastSyncedAt)
        assertEquals(7, loaded.source?.sourceEventCount)
        assertEquals(imported().groups.map { it.timetableSpec }, loaded.source?.groups)
        assertFalse(preferences.contains("subscription_url_alice"))
        assertFalse(preferences.contains("snapshot_alice"))
        assertTrue(preferences.contains("subscription_url_bob"))
        assertEquals("retained", preferences.getString("other_setting", null))
        assertEquals(loaded, repository.load("alice"))
    }

    @Test fun legacyUrlOnlyAndCorruptCacheRemainUrlOnlyAndNoUrlInventsNoSource() = runBlocking<Unit> {
        preferences.edit().putString("subscription_url_alice", url).putString("snapshot_alice", "corrupt").commit()
        val alice = repository.load("alice")
        assertEquals(TimetableSource(url), alice.source)
        assertTrue(alice.imported.isEmpty())
        preferences.edit().putString("subscription_url_bob", "https://calendar.example/bob").commit()
        assertEquals(TimetableSource("https://calendar.example/bob"), repository.load("bob").source)
        preferences.edit().putString("snapshot_charlie", TimetableCache.encode(url, imported(), syncedAt)).commit()
        assertNull(repository.load("charlie").source)
    }

    @Test fun interruptedCleanupLoadsAuthoritativeRoomThenRetriesCleanupWithoutRecopying() = runBlocking<Unit> {
        seedLegacy("alice", url, imported(), syncedAt)
        var cleanupSucceeds = false
        val interrupted = object : TimetableLegacyStorage {
            override fun readLegacy(ownerId: String) = legacy.readLegacy(ownerId)
            override suspend fun clearLegacy(ownerId: String) = if (cleanupSucceeds) legacy.clearLegacy(ownerId) else false
        }
        val repo = RoomTimetableRepository(database, interrupted)
        assertEquals(listOf(original), repo.load("alice").imported)
        assertTrue(preferences.contains("snapshot_alice"))
        val changed = original.copy(title = "Authoritative Room value")
        repo.replaceImport("alice", url, imported(changed), syncedAt.plusSeconds(10))
        cleanupSucceeds = true
        assertEquals(listOf(changed), repo.load("alice").imported)
        assertFalse(preferences.contains("snapshot_alice"))
    }

    @Test fun failedCopyLeavesPreferencesAndDatabaseUntouched() = runBlocking<Unit> {
        seedLegacy("alice", url, imported().copy(sessions = listOf(original, original)), syncedAt)
        val keysBefore = preferences.all
        assertTrue(runCatching { repository.load("alice") }.isFailure)
        assertEquals(keysBefore, preferences.all)
        assertNull(database.timetableDao().source("alice"))
        assertTrue(database.timetableDao().imported("alice").isEmpty())
    }

    @Test fun failedCleanupBlocksDisconnectAndKeepsCommittedRows() = runBlocking<Unit> {
        seedRows()
        val before = repository.load("alice")
        val refusing = object : TimetableLegacyStorage {
            override fun readLegacy(ownerId: String) = legacy.readLegacy(ownerId)
            override suspend fun clearLegacy(ownerId: String) = false
        }
        val repo = RoomTimetableRepository(database, refusing)
        assertTrue(runCatching { repo.disconnect("alice") }.isFailure)
        assertEquals(before, repo.load("alice"))
    }

    @Test fun corruptStoredManualPayloadReportsLoadFailureAndLeavesRowIntact() = runBlocking<Unit> {
        val corrupt = LocalClassEntity.from("alice", LocalClass(manual.id, LocalClassKind.MANUAL, manual)).copy(startIso = "invalid")
        database.timetableDao().putLocal(corrupt)
        assertTrue(runCatching { repository.load("alice") }.isFailure)
        assertEquals(listOf(corrupt), database.timetableDao().local("alice"))
    }

    @Test fun fileBackedReopenPreservesAllKindsAndMetadata() = runBlocking<Unit> {
        database.close()
        database = Room.databaseBuilder(context, TimetableDatabase::class.java, fileName).build()
        repository = RoomTimetableRepository(database, legacy)
        seedRows()
        repository.upsertLocal("alice", LocalClass("hidden-original", LocalClassKind.HIDDEN))
        val before = repository.load("alice")
        database.close()
        database = Room.databaseBuilder(context, TimetableDatabase::class.java, fileName).build()
        repository = RoomTimetableRepository(database, legacy)
        assertEquals(before, repository.load("alice"))
    }

    @Test fun existingRowsCannotSwitchBetweenManualAndImportedIdentity() = runBlocking<Unit> {
        repository.upsertLocal("alice", LocalClass(manual.id, LocalClassKind.MANUAL, manual))
        val manualBefore = repository.load("alice")
        assertTrue(runCatching { repository.upsertLocal("alice", LocalClass(manual.id, LocalClassKind.HIDDEN)) }.isFailure)
        assertEquals(manualBefore, repository.load("alice"))
        // Imported identities remain opaque, even if a legacy ID has a manual UUID's exact shape.
        val opaqueOriginal = original.copy(id = manual.id)
        repository.replaceImport("bob", url, imported(opaqueOriginal), syncedAt)
        repository.upsertLocal("bob", LocalClass(manual.id, LocalClassKind.EDITED, opaqueOriginal, start))
        val editedBefore = repository.load("bob")
        assertTrue(runCatching { repository.upsertLocal("bob", LocalClass(manual.id, LocalClassKind.MANUAL, manual)) }.isFailure)
        assertEquals(editedBefore, repository.load("bob"))
        // Editing/hiding are both adjustments and can replace each other for Undo.
        assertEquals(LocalClassKind.HIDDEN,
            repository.upsertLocal("bob", LocalClass(manual.id, LocalClassKind.HIDDEN)).local.single().kind)
    }

    @Test fun hideThenUndoKeepsAnEditedAnchorWhileItsOriginalIsAbsent() = runBlocking<Unit> {
        seedRows()
        val previousEdit = repository.load("alice").local.single { it.kind == LocalClassKind.EDITED }
        val hidden = repository.upsertLocal("alice", LocalClass(original.id, LocalClassKind.HIDDEN))
        assertEquals(start, hidden.local.single { it.kind == LocalClassKind.HIDDEN }.originalStart)
        repository.replaceImport("alice", url, TimetableImport(emptyList(), emptyList(), 0), syncedAt.plusSeconds(10))
        val undone = repository.upsertLocal("alice", previousEdit)
        assertEquals(start, undone.local.single { it.kind == LocalClassKind.EDITED }.originalStart)
        assertEquals(listOf(manual), undone.mergedSessions())
        val returned = repository.replaceImport("alice", url, imported(), syncedAt.plusSeconds(20))
        assertEquals(listOf(manual, corrected), returned.mergedSessions())
    }
}
