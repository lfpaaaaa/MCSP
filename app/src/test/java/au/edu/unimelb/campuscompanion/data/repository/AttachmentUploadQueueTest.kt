package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.english

import au.edu.unimelb.campuscompanion.data.*
import au.edu.unimelb.campuscompanion.data.model.CurrentUser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream

class AttachmentUploadQueueTest {
    private val remote = FakeFileRemoteDataSource()
    private val files = DefaultFileRepository(remote, { CurrentUser("student", "Student") })

    @Test fun uploadsOriginalUriForDocumentsImagesAndVideos() = runBlocking<Unit> {
        val read = mutableListOf<String>()
        val queue = AttachmentUploadQueue(files, this, { "student" }, { uri -> read += uri; ByteArray(16) }, { it.english() })
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
        val queue = AttachmentUploadQueue(files, this, { "student" }, { ByteArray(16) }, { it.english() })
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
        val queue = AttachmentUploadQueue(files, this, { "student" }, { ByteArray(1) }, { it.english() }, listOf(restored), { saved = it })
        assertEquals(AttachmentPhase.Failed, queue.uploads.value.single().phase)
        queue.retry("id")
        withTimeout(5000) { queue.uploads.first { it.single().phase == AttachmentPhase.Completed } }
        assertEquals(AttachmentPhase.Completed, saved.single().phase)
    }

    @Test fun downloadConsentSurvivesFailurePersistenceAndRestart() = runBlocking<Unit> {
        remote.uploadFailure = DataError.Offline()
        var saved: List<AttachmentUpload> = emptyList()
        val queue = AttachmentUploadQueue(files, this, { "student" }, { ByteArray(4) }, { it.english() }, persist = { saved = it })
        queue.enqueue(FILE_GROUP_ID, "uri", "photo.png", "image/png", allowDownloads = false)
        withTimeout(5000) { queue.uploads.first { it.single().phase == AttachmentPhase.Failed } }
        assertFalse(saved.single().allowDownloads)
        val json = kotlinx.serialization.json.Json
        val restored = json.decodeFromString<List<AttachmentUpload>>(json.encodeToString(saved))
        remote.uploadFailure = null
        val restarted = AttachmentUploadQueue(files, this, { "student" }, { ByteArray(4) }, { it.english() }, restored)
        restarted.retry(restored.single().id)
        withTimeout(5000) { restarted.uploads.first { it.single().phase == AttachmentPhase.Completed } }
        assertFalse(remote.stored.single().allowDownloads)
    }

    @Test fun uploadsFromOlderVersionsDefaultToAllowingDownloads() {
        val old = """{"id":"old","userId":"u","groupId":"g","uri":"uri","fileName":"x.txt","mimeType":"text/plain","createdAt":"2026-10-10T00:00:00Z"}"""
        assertTrue(kotlinx.serialization.json.Json.decodeFromString<AttachmentUpload>(old).allowDownloads)
    }

    @Test fun switchingAccountDoesNotUploadAnotherUsersPendingAttachment() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        var user: String? = "student"
        val queue = AttachmentUploadQueue(files, this, { user }, { release.await(); ByteArray(1) }, { it.english() })
        queue.enqueue(FILE_GROUP_ID, "uri", "x.txt", "text/plain")
        user = "someone-else"
        release.complete(Unit)
        withTimeout(5000) { queue.uploads.first { it.single().phase == AttachmentPhase.Failed } }
        assertTrue(remote.stored.isEmpty())
    }

    @Test fun recordingSourceIsKeptForRetryAndReleasedOnlyAfterSuccess() = runBlocking<Unit> {
        remote.uploadFailure = DataError.Offline()
        val released = mutableListOf<String>()
        val queue = AttachmentUploadQueue(files, this, { "student" }, { ByteArray(16) }, { it.english() }, releaseSource = { released += it })
        queue.enqueue(FILE_GROUP_ID, "recording-uri", "recording.m4a", "audio/mp4", allowDownloads = false)
        val failed = withTimeout(5000) { queue.uploads.first { it.single().phase == AttachmentPhase.Failed }.single() }
        assertTrue(released.isEmpty())
        remote.uploadFailure = null
        queue.retry(failed.id)
        withTimeout(5000) { queue.uploads.first { it.single().phase == AttachmentPhase.Completed } }
        assertEquals(listOf("recording-uri"), released)
        assertFalse(remote.stored.single().allowDownloads)
    }

    @Test fun dismissingFailedRecordingReleasesItsSource() = runBlocking<Unit> {
        remote.uploadFailure = DataError.Offline()
        val released = mutableListOf<String>()
        val queue = AttachmentUploadQueue(files, this, { "student" }, { ByteArray(16) }, { it.english() }, releaseSource = { released += it })
        queue.enqueue(FILE_GROUP_ID, "recording-uri", "recording.m4a", "audio/mp4")
        val failed = withTimeout(5000) { queue.uploads.first { it.single().phase == AttachmentPhase.Failed }.single() }
        queue.dismiss(failed.id)
        assertEquals(listOf("recording-uri"), released)
        assertTrue(queue.uploads.value.isEmpty())
    }

    @Test fun unknownSizeStreamsAreBoundedBeforeUpload() {
        assertArrayEquals(byteArrayOf(1, 2, 3), ByteArrayInputStream(byteArrayOf(1, 2, 3)).readAttachmentBytes(3))
        assertThrows(DataError.Validation::class.java) { ByteArrayInputStream(ByteArray(4)).readAttachmentBytes(3) }
        assertThrows(DataError.Validation::class.java) { ByteArrayInputStream(ByteArray(0)).readAttachmentBytes(3) }
    }
}
