package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.CurrentUser
import au.edu.unimelb.campuscompanion.data.model.UploadState
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
    fun anUnsavedRecordRemovesTheUploadedObject() = runBlocking<Unit> {
        remote.recordFailure = DataError.Offline()

        val last = repository.uploadFile(FILE_GROUP_ID, "report.pdf", "application/pdf", ByteArray(100)).toList().last()

        assertTrue((last as UploadState.Failed).error is DataError.Offline)
        assertEquals(remote.uploadedPaths, remote.deletedPaths)
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
    fun onlyTheUploaderCanDeleteAFile() = runBlocking<Unit> {
        remote.stored += listOf(fileRow(1, uploaderId = SELF_ID), fileRow(2))
        withTimeout(5_000) { repository.observeFiles(FILE_GROUP_ID).first { it.size == 2 } }

        assertTrue(repository.deleteFile("file-1").isSuccess)
        assertTrue(repository.deleteFile("file-2").exceptionOrNull() is DataError.Forbidden)

        assertEquals(listOf("file-1"), remote.deletedRecords)
        assertEquals(listOf(fileRow(1, uploaderId = SELF_ID).storagePath), remote.deletedPaths)
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

    private companion object {
        const val SELF_ID = "user-1"
    }
}
