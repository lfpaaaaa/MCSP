package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.CurrentUser
import au.edu.unimelb.campuscompanion.data.model.UploadState
import au.edu.unimelb.campuscompanion.data.remote.SharedFileRow
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultFileRepositoryTest {
    private val remote = FakeFileRemoteDataSource()
    private var objectIds = 0
    private val repository = DefaultFileRepository(
        remote = remote,
        currentUser = { CurrentUser(SELF_ID, "Cedric") },
        newObjectId = { "object-${++objectIds}" },
        firstRetryDelayMillis = 5,
        maxRetryDelayMillis = 20
    )

    @Test
    fun filesAreListedNewestFirstWithTheUploadersName() = runBlocking<Unit> {
        remote.stored += listOf(fileRow(1, uploaderName = "Alice"), fileRow(2))

        val files = withTimeout(5_000) { repository.observeFiles(FILE_GROUP_ID).first { it.size == 2 } }

        assertEquals(listOf("notes-2.pdf", "notes-1.pdf"), files.map { it.fileName })
        assertEquals(listOf("Bob", "Alice"), files.map { it.uploaderName })
    }

    @Test
    fun uploadsReportProgressAndThenTheStoredFile() = runBlocking<Unit> {
        val states = repository.uploadFile(FILE_GROUP_ID, " report.pdf ", "application/pdf", ByteArray(1_000)).toList()

        assertEquals(
            listOf(
                UploadState.InProgress(0, 1_000),
                UploadState.InProgress(500, 1_000),
                UploadState.InProgress(1_000, 1_000)
            ),
            states.dropLast(1)
        )
        val file = (states.last() as UploadState.Completed).file
        assertEquals("report.pdf", file.fileName)
        assertEquals("Cedric", file.uploaderName)
        assertEquals(listOf("$FILE_GROUP_ID/$SELF_ID/object-1"), remote.uploadedPaths)
        assertEquals("$FILE_GROUP_ID/$SELF_ID/object-1", remote.stored.single().storagePath)
    }

    @Test
    fun invalidUploadsFailBeforeReachingStorage() = runBlocking<Unit> {
        val attempts = listOf(
            repository.uploadFile(FILE_GROUP_ID, "empty.txt", "text/plain", ByteArray(0)),
            repository.uploadFile(FILE_GROUP_ID, "large.bin", "application/octet-stream", ByteArray(20 * 1024 * 1024 + 1)),
            repository.uploadFile(FILE_GROUP_ID, "   ", "text/plain", ByteArray(10))
        )

        attempts.forEach { attempt ->
            val state = attempt.toList().single()
            assertTrue((state as UploadState.Failed).error is DataError.Validation)
        }
        assertTrue(remote.uploadedPaths.isEmpty())
    }

    @Test
    fun anUnconfirmedRecordKeepsTheUploadedObjectForRecovery() = runBlocking<Unit> {
        remote.recordFailure = DataError.Offline()

        val last = repository.uploadFile(FILE_GROUP_ID, "report.pdf", "application/pdf", ByteArray(100)).toList().last()

        assertTrue((last as UploadState.Failed).error is DataError.Offline)
        assertTrue(remote.deletedPaths.isEmpty())
        assertEquals(remote.uploadedPaths.single(), remote.objects.keys.single())
    }

    @Test
    fun lostFinalizationResponseIsRecoveredByIdentityWithoutUploadingAgain() = runBlocking<Unit> {
        val identity = FileUploadIdentity("stable-upload", "$FILE_GROUP_ID/$SELF_ID/stable-upload")
        remote.recordResponseFailure = DataError.Offline()
        val first = repository.uploadFile(FILE_GROUP_ID, "report.pdf", "application/pdf", ByteArray(100),
            allowDownloads = false, identity = identity).toList().last()
        assertTrue(first is UploadState.Failed)
        assertEquals(1, remote.stored.size)
        assertTrue(remote.deletedPaths.isEmpty())

        val restarted = repositoryWith(InMemorySharedFileListCache())
        val recovered = restarted.uploadFile(FILE_GROUP_ID, "report.pdf", "application/pdf", ByteArray(100),
            allowDownloads = false, identity = identity).toList().last()
        assertEquals(identity.id, (recovered as UploadState.Completed).file.id)
        assertEquals(1, remote.uploadedPaths.size)
        assertEquals(1, remote.stored.size)
        assertTrue(remote.deletedPaths.isEmpty())
    }

    @Test
    fun retryFinalizesTheSameObjectAfterMetadataWasNeverSaved() = runBlocking<Unit> {
        val identity = FileUploadIdentity("stable-upload", "$FILE_GROUP_ID/$SELF_ID/stable-upload")
        remote.recordFailure = DataError.Offline()
        repository.uploadFile(FILE_GROUP_ID, "report.pdf", "application/pdf", ByteArray(100),
            identity = identity).toList()
        remote.recordFailure = null
        val retried = repositoryWith(InMemorySharedFileListCache()).uploadFile(FILE_GROUP_ID, "report.pdf",
            "application/pdf", ByteArray(100), identity = identity).toList().last()

        assertTrue(retried is UploadState.Completed)
        assertEquals(listOf(identity.storagePath, identity.storagePath), remote.uploadedPaths)
        assertEquals(1, remote.objects.size)
        assertEquals(identity.id, remote.stored.single().id)
        assertTrue(remote.deletedPaths.isEmpty())
    }

    @Test
    fun lostStorageResponseIsReconciledByServerFinalization() = runBlocking<Unit> {
        remote.uploadResponseFailure = DataError.Offline()
        val completed = repository.uploadFile(FILE_GROUP_ID, "report.pdf", "application/pdf", ByteArray(100))
            .toList().last()
        assertTrue(completed is UploadState.Completed)
        assertEquals(1, remote.objects.size)
        assertEquals(1, remote.stored.size)
    }

    @Test
    fun identitiesCannotBeReusedForAnotherAccountGroupOrPermission() = runBlocking<Unit> {
        val otherUser = FileUploadIdentity("stable-upload", "$FILE_GROUP_ID/someone-else/stable-upload")
        val denied = repository.uploadFile(FILE_GROUP_ID, "report.pdf", "application/pdf", ByteArray(100),
            identity = otherUser).toList().last()
        assertTrue((denied as UploadState.Failed).error is DataError.Validation)
        assertTrue(remote.uploadedPaths.isEmpty())

        val identity = FileUploadIdentity("stable-upload", "$FILE_GROUP_ID/$SELF_ID/stable-upload")
        repository.uploadFile(FILE_GROUP_ID, "report.pdf", "application/pdf", ByteArray(100),
            allowDownloads = false, identity = identity).toList()
        val changed = repository.uploadFile(FILE_GROUP_ID, "report.pdf", "application/pdf", ByteArray(100),
            allowDownloads = true, identity = identity).toList().last()
        assertTrue((changed as UploadState.Failed).error is DataError.Validation)
        assertEquals(false, remote.stored.single().allowDownloads)
        assertEquals(1, remote.uploadedPaths.size)
    }

    @Test
    fun aFailedUploadSavesNoRecord() = runBlocking<Unit> {
        remote.uploadFailure = DataError.Offline()

        val last = repository.uploadFile(FILE_GROUP_ID, "report.pdf", "application/pdf", ByteArray(100)).toList().last()

        assertTrue((last as UploadState.Failed).error is DataError.Offline)
        assertTrue(remote.stored.isEmpty())
    }

    @Test
    fun downloadLinksAreSignedForTheStoredObject() = runBlocking<Unit> {
        remote.stored += fileRow(1)
        withTimeout(5_000) { repository.observeFiles(FILE_GROUP_ID).first { it.isNotEmpty() } }

        assertEquals(
            "https://signed.example/${fileRow(1).storagePath}?expires=300",
            repository.createDownloadUrl("file-1").getOrThrow()
        )
        assertTrue(repository.createDownloadUrl("file-9").exceptionOrNull() is DataError.NotFound)
    }

    @Test
    fun restrictedFilesCanBePreviewedButOnlyTheirUploaderCanDownload() = runBlocking<Unit> {
        remote.stored += fileRow(1).copy(allowDownloads = false)
        remote.stored += fileRow(2, uploaderId = SELF_ID).copy(allowDownloads = false)
        withTimeout(5_000) { repository.observeFiles(FILE_GROUP_ID).first { it.size == 2 } }

        assertTrue(repository.createPreviewUrl("file-1").isSuccess)
        assertTrue(repository.createDownloadUrl("file-1").exceptionOrNull() is DataError.Validation)
        assertTrue(repository.createDownloadUrl("file-2").isSuccess)
        assertEquals(listOf("file-1" to false, "file-2" to true), remote.accessRequests)
    }

    @Test
    fun serverDenialIsRespectedEvenWhenTheCachedFileAllowsDownloads() = runBlocking<Unit> {
        remote.stored += fileRow(1)
        withTimeout(5_000) { repository.observeFiles(FILE_GROUP_ID).first { it.isNotEmpty() } }
        remote.accessFailure = DataError.Forbidden()
        assertTrue(repository.createDownloadUrl("file-1").exceptionOrNull() is DataError.Forbidden)
        assertTrue(repository.createPreviewUrl("file-1").exceptionOrNull() is DataError.Forbidden)
    }

    @Test
    fun onlyTheUploaderCanDeleteAFile() = runBlocking<Unit> {
        remote.stored += listOf(fileRow(1, uploaderId = SELF_ID), fileRow(2))
        withTimeout(5_000) { repository.observeFiles(FILE_GROUP_ID).first { it.size == 2 } }

        assertTrue(repository.deleteFile("file-1").isSuccess)
        assertTrue(repository.deleteFile("file-2").exceptionOrNull() is DataError.Forbidden)

        assertEquals(listOf("file-1"), remote.deletedRecords)
        assertEquals(listOf(fileRow(1, uploaderId = SELF_ID).storagePath), remote.deletedPaths)
        assertTrue(repository.deleteFile("file-1").isSuccess) // Server tombstone confirms a retry after cache removal.
    }

    @Test
    fun failedDeletionRemainsVisibleAndCanBeRetriedAfterRestart() = runBlocking<Unit> {
        val cache = InMemorySharedFileListCache()
        val first = repositoryWith(cache)
        val row = fileRow(1, uploaderId = SELF_ID)
        remote.stored += row
        withTimeout(5_000) { first.observeFiles(FILE_GROUP_ID).first { it.size == 1 } }
        remote.objectDeletionFailure = DataError.Offline()

        assertTrue(first.deleteFile(row.id).exceptionOrNull() is DataError.Offline)
        assertEquals(listOf(row.id), cache.load(SELF_ID, FILE_GROUP_ID)?.map { it.id })
        assertEquals(listOf(row), remote.stored)

        remote.objectDeletionFailure = null
        val restarted = repositoryWith(cache)
        withTimeout(5_000) { restarted.observeFiles(FILE_GROUP_ID).first { it.size == 1 } }
        assertTrue(restarted.deleteFile(row.id).isSuccess)
        assertTrue(cache.load(SELF_ID, FILE_GROUP_ID).orEmpty().isEmpty())
        assertTrue(remote.stored.isEmpty())
    }

    @Test
    fun serverRefusalDoesNotRemoveCachedAttachment() = runBlocking<Unit> {
        val row = fileRow(1, uploaderId = SELF_ID)
        remote.stored += row
        withTimeout(5_000) { repository.observeFiles(FILE_GROUP_ID).first { it.size == 1 } }
        remote.objectDeletionFailure = DataError.Forbidden()

        assertTrue(repository.deleteFile(row.id).exceptionOrNull() is DataError.Forbidden)
        assertEquals(listOf(row.id), repository.observeFiles(FILE_GROUP_ID).first().map { it.id })
    }

    @Test
    fun anotherMembersDeletionIsReconciledWithoutARealtimeInsert() = runBlocking<Unit> {
        remote.stored += fileRow(1)
        val polling = DefaultFileRepository(remote, { CurrentUser(SELF_ID, "Cedric") },
            reconciliationIntervalMillis = 5)
        val observed = async {
            var sawFile = false
            polling.observeFiles(FILE_GROUP_ID).first { files ->
                if (files.isNotEmpty()) { sawFile = true; remote.stored.clear() }
                sawFile && files.isEmpty()
            }
        }
        assertTrue(withTimeout(5_000) { observed.await() }.isEmpty())
    }

    @Test
    fun savedFilesAreShownWhileOfflineAndReplacedOnceTheServerAnswers() = runBlocking<Unit> {
        val cache = InMemorySharedFileListCache()
        cache.save(SELF_ID, FILE_GROUP_ID, listOf(fileRow(1)))
        remote.fetchFailure = DataError.Offline()
        val repository = repositoryWith(cache)

        val saved = withTimeout(5_000) { repository.observeFiles(FILE_GROUP_ID).first() }
        assertEquals(listOf("notes-1.pdf"), saved.map { it.fileName })

        remote.stored += listOf(fileRow(1), fileRow(2))
        remote.fetchFailure = null
        val fresh = withTimeout(5_000) { repository.observeFiles(FILE_GROUP_ID).first { it.size == 2 } }

        assertEquals(listOf("notes-2.pdf", "notes-1.pdf"), fresh.map { it.fileName })
        assertEquals(listOf("file-2", "file-1"), cache.load(SELF_ID, FILE_GROUP_ID)?.map { it.id })
    }

    @Test
    fun uploadsAndDeletionsUpdateTheSavedList() = runBlocking<Unit> {
        val cache = InMemorySharedFileListCache()
        val repository = repositoryWith(cache)

        val states = repository.uploadFile(FILE_GROUP_ID, "report.pdf", "application/pdf", ByteArray(100)).toList()
        val uploaded = (states.last() as UploadState.Completed).file
        assertEquals(listOf("report.pdf"), cache.load(SELF_ID, FILE_GROUP_ID)?.map { it.fileName })

        repository.deleteFile(uploaded.id).getOrThrow()
        assertEquals(emptyList<String>(), cache.load(SELF_ID, FILE_GROUP_ID)?.map { it.id })
    }

    @Test
    fun filesAddedByOthersAppearWhileTheListIsOpen() = runBlocking<Unit> {
        val listed = async {
            withTimeout(5_000) { repository.observeFiles(FILE_GROUP_ID).first { it.size == 1 } }
        }
        remote.fetchCount.first { it >= 1 }
        remote.events.subscriptionCount.first { it > 0 }

        remote.stored += fileRow(3)
        remote.events.emit(Unit)

        assertEquals("notes-3.pdf", listed.await().single().fileName)
    }

    private fun repositoryWith(cache: SharedFileListCache) = DefaultFileRepository(
        remote = remote,
        currentUser = { CurrentUser(SELF_ID, "Cedric") },
        cache = cache,
        newObjectId = { "object-${++objectIds}" },
        firstRetryDelayMillis = 5,
        maxRetryDelayMillis = 20
    )

    private companion object {
        const val SELF_ID = "user-1"
    }
}

/** [SharedFileListCache] in memory, for the tests. */
private class InMemorySharedFileListCache : SharedFileListCache {
    private val lists = mutableMapOf<Pair<String, String>, List<SharedFileRow>>()

    override fun load(userId: String, groupId: String): List<SharedFileRow>? = lists[userId to groupId]

    override fun save(userId: String, groupId: String, rows: List<SharedFileRow>) {
        lists[userId to groupId] = rows
    }
}
