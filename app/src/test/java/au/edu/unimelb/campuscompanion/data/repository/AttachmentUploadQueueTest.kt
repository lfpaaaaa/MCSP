package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.*
import au.edu.unimelb.campuscompanion.data.model.CurrentUser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class AttachmentUploadQueueTest {
    private val remote = FakeFileRemoteDataSource()
    private val files = DefaultFileRepository(remote, { CurrentUser("student", "Student") })

    @Test fun uploadsOriginalUriForDocumentsImagesAndVideos() = runBlocking<Unit> {
        val read = mutableListOf<String>()
        val queue = AttachmentUploadQueue(files, this, { "student" }, { uri -> read += uri; ByteArray(16) })
        listOf("application/pdf", "image/png", "video/mp4").forEachIndexed { i, type ->
            queue.enqueue(FILE_GROUP_ID, "content://original/$i", "file-$i", type)
        }
        withTimeout(5000) { queue.uploads.first { it.size == 3 && it.all { task -> task.phase == AttachmentPhase.Completed } } }
        assertEquals(listOf("content://original/0", "content://original/1", "content://original/2"), read)
        assertEquals(listOf("application/pdf", "image/png", "video/mp4"), remote.stored.map { it.mimeType })
        assertTrue(remote.stored.all { it.sizeBytes == 16L })
    }

    @Test fun failedUploadRetriesButCompletedUploadCannotResend() = runBlocking<Unit> {
        remote.uploadFailure = DataError.Offline()
        val queue = AttachmentUploadQueue(files, this, { "student" }, { ByteArray(16) })
        queue.enqueue(FILE_GROUP_ID, "uri", "photo.png", "image/png")
        val failed = withTimeout(5000) { queue.uploads.first { it.single().phase == AttachmentPhase.Failed }.single() }
        remote.uploadFailure = null
        queue.retry(failed.id)
        queue.retry(failed.id) // Double tapping Retry must not launch a duplicate job.
        val completed = withTimeout(5000) { queue.uploads.first { it.single().phase == AttachmentPhase.Completed }.single() }
        queue.retry(completed.id)
        assertEquals(1, remote.stored.size)
        queue.acknowledge(setOf(completed.fileId!!))
        assertTrue(queue.uploads.value.isEmpty())
    }

    @Test fun restartRestoresInterruptedUploadAsRetryable() = runBlocking<Unit> {
        val restored = AttachmentUpload("id", "student", FILE_GROUP_ID, "uri", "x.txt", "text/plain", "2026-10-10T00:00:00Z")
        var saved: List<AttachmentUpload> = emptyList()
        val queue = AttachmentUploadQueue(files, this, { "student" }, { ByteArray(1) }, listOf(restored), { saved = it })
        assertEquals(AttachmentPhase.Failed, queue.uploads.value.single().phase)
        queue.retry("id")
        withTimeout(5000) { queue.uploads.first { it.single().phase == AttachmentPhase.Completed } }
        assertEquals(AttachmentPhase.Completed, saved.single().phase)
    }

    @Test fun switchingAccountDoesNotUploadAnotherUsersPendingAttachment() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        var user: String? = "student"
        val queue = AttachmentUploadQueue(files, this, { user }, { release.await(); ByteArray(1) })
        queue.enqueue(FILE_GROUP_ID, "uri", "x.txt", "text/plain")
        user = "someone-else"
        release.complete(Unit)
        withTimeout(5000) { queue.uploads.first { it.single().phase == AttachmentPhase.Failed } }
        assertTrue(remote.stored.isEmpty())
    }

    @Test fun unknownSizeStreamsAreBoundedBeforeUpload() {
        assertArrayEquals(byteArrayOf(1, 2, 3), ByteArrayInputStream(byteArrayOf(1, 2, 3)).readAttachmentBytes(3))
        assertThrows(DataError.Validation::class.java) { ByteArrayInputStream(ByteArray(4)).readAttachmentBytes(3) }
        assertThrows(DataError.Validation::class.java) { ByteArrayInputStream(ByteArray(0)).readAttachmentBytes(3) }
    }
}
