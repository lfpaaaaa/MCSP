package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.*
import au.edu.unimelb.campuscompanion.data.model.CurrentUser
import au.edu.unimelb.campuscompanion.data.local.AttachmentDraftStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class AttachmentUploadQueueTest {
    @get:Rule val temporary = TemporaryFolder()
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

    @Test fun downloadConsentSurvivesFailurePersistenceAndRestart() = runBlocking<Unit> {
        remote.uploadFailure = DataError.Offline()
        var saved: List<AttachmentUpload> = emptyList()
        val queue = AttachmentUploadQueue(files, this, { "student" }, { ByteArray(4) }, persist = { saved = it })
        queue.enqueue(FILE_GROUP_ID, "uri", "photo.png", "image/png", allowDownloads = false)
        withTimeout(5000) { queue.uploads.first { it.single().phase == AttachmentPhase.Failed } }
        assertFalse(saved.single().allowDownloads)
        val json = kotlinx.serialization.json.Json
        val restored = json.decodeFromString<List<AttachmentUpload>>(json.encodeToString(saved))
        remote.uploadFailure = null
        val restarted = AttachmentUploadQueue(files, this, { "student" }, { ByteArray(4) }, restored)
        restarted.retry(restored.single().id)
        withTimeout(5000) { restarted.uploads.first { it.single().phase == AttachmentPhase.Completed } }
        assertFalse(remote.stored.single().allowDownloads)
    }

    @Test fun uploadsFromOlderVersionsDefaultToAllowingDownloads() {
        val old = """{"id":"old","userId":"u","groupId":"g","uri":"uri","fileName":"x.txt","mimeType":"text/plain","createdAt":"2026-10-10T00:00:00Z"}"""
        val decoded = kotlinx.serialization.json.Json.decodeFromString<AttachmentUpload>(old)
        assertTrue(decoded.allowDownloads)
        assertEquals("old", decoded.uploadId)
        assertEquals("g/u/old", decoded.storagePath)
    }

    @Test fun restartAfterLostMetadataReplyReusesIdentityAndPreservedBytes() = runBlocking<Unit> {
        val drafts = AttachmentDraftStore(temporary.newFolder("drafts"))
        val original = byteArrayOf(1, 2, 3, 4)
        var sourceReads = 0
        var saved: List<AttachmentUpload> = emptyList()
        val preserve: suspend (AttachmentUpload) -> String = { task ->
            drafts.preserve(task.uploadId) { sourceReads++; original }.toURI().toString()
        }
        remote.recordResponseFailure = DataError.Offline()
        val queue = AttachmentUploadQueue(files, this, { "student" },
            { File(URI(it)).inputStream().use { input -> input.readAttachmentBytes() } },
            persist = { saved = it }, preserveLocally = preserve)
        queue.enqueue(FILE_GROUP_ID, "content://temporary-provider", "audio.m4a", "audio/mp4", allowDownloads = false)
        val failed = withTimeout(5000) { queue.uploads.first { it.single().phase == AttachmentPhase.Failed }.single() }
        assertEquals(failed.id, failed.uploadId)
        assertEquals("$FILE_GROUP_ID/student/${failed.uploadId}", failed.storagePath)
        assertArrayEquals(original, drafts.read(failed.uploadId))
        assertEquals(failed.uploadId, remote.stored.single().id)
        assertTrue(remote.deletedPaths.isEmpty())

        original.fill(9) // The provider is no longer a reliable source after the first attempt.
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val restored = json.decodeFromString<List<AttachmentUpload>>(json.encodeToString(saved))
        val restarted = AttachmentUploadQueue(DefaultFileRepository(remote, { CurrentUser("student", "Student") }),
            this, { "student" }, { File(URI(it)).readBytes() }, restored,
            preserveLocally = { error("The provider must not be opened after restart.") },
            discardLocal = { drafts.discard(it.uploadId) })
        restarted.retry(failed.id)
        val completed = withTimeout(5000) { restarted.uploads.first { it.single().phase == AttachmentPhase.Completed }.single() }
        assertEquals(1, sourceReads)
        assertEquals(1, remote.uploadedPaths.size)
        assertEquals(1, remote.stored.size)
        assertFalse(remote.stored.single().allowDownloads)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), drafts.read(completed.uploadId))
        restarted.acknowledge(setOf(completed.fileId!!))
        assertTrue(restarted.uploads.value.isEmpty())
        assertFalse(File(URI(completed.localUri!!)).exists())
    }

    @Test fun failedUploadKeepsLocalBytesUntilExplicitDiscard() = runBlocking<Unit> {
        val drafts = AttachmentDraftStore(temporary.newFolder("drafts"))
        remote.uploadFailure = DataError.Offline()
        val queue = AttachmentUploadQueue(files, this, { "student" }, { File(URI(it)).readBytes() },
            preserveLocally = { task -> drafts.preserve(task.uploadId) { byteArrayOf(1) }.toURI().toString() },
            discardLocal = { drafts.discard(it.uploadId) })
        queue.enqueue(FILE_GROUP_ID, "content://source", "x.txt", "text/plain")
        val failed = withTimeout(5000) { queue.uploads.first { it.single().phase == AttachmentPhase.Failed }.single() }
        assertArrayEquals(byteArrayOf(1), drafts.read(failed.uploadId))
        queue.dismiss(failed.id)
        assertTrue(queue.uploads.value.isEmpty())
        assertFalse(File(URI(failed.localUri!!)).exists())
    }

    @Test fun summaryRefreshFailureCannotChangeCompletedUploadToFailed() = runBlocking<Unit> {
        val queue = AttachmentUploadQueue(files, this, { "student" }, { ByteArray(1) },
            onCompleted = { throw DataError.Offline() })
        queue.enqueue(FILE_GROUP_ID, "uri", "x.txt", "text/plain")
        val completed = withTimeout(5000) { queue.uploads.first { it.single().phase == AttachmentPhase.Completed }.single() }
        queue.retry(completed.id)
        assertEquals(AttachmentPhase.Completed, queue.uploads.value.single().phase)
        assertEquals(1, remote.stored.size)
    }

    @Test fun observingTheDurableFileAcknowledgesALostFinalizationReply() = runBlocking<Unit> {
        remote.recordResponseFailure = DataError.Offline()
        val discarded = mutableListOf<String>()
        val queue = AttachmentUploadQueue(files, this, { "student" }, { ByteArray(1) },
            discardLocal = { discarded += it.uploadId })
        queue.enqueue(FILE_GROUP_ID, "uri", "x.txt", "text/plain")
        val failed = withTimeout(5000) { queue.uploads.first { it.single().phase == AttachmentPhase.Failed }.single() }
        queue.acknowledge(setOf(remote.stored.single().id))
        assertTrue(queue.uploads.value.isEmpty())
        assertEquals(listOf(failed.uploadId), discarded)
        assertTrue(remote.deletedPaths.isEmpty())
    }

    @Test fun failedEnqueuePersistenceDoesNotPublishOrLaunchATransfer() = runBlocking<Unit> {
        var reads = 0
        val queue = AttachmentUploadQueue(files, this, { "student" }, { reads++; ByteArray(1) },
            persist = { throw DataError.Unexpected() })
        assertThrows(DataError.Unexpected::class.java) {
            queue.enqueue(FILE_GROUP_ID, "uri", "x.txt", "text/plain")
        }
        yield()
        assertTrue(queue.uploads.value.isEmpty())
        assertEquals(0, reads)
        assertTrue(remote.uploadedPaths.isEmpty())
    }

    @Test fun failedRetryPersistenceKeepsThePreviousRetryableItemAndDoesNotLaunch() = runBlocking<Unit> {
        val failed = AttachmentUpload("retry-id", "student", FILE_GROUP_ID, "uri", "x.txt", "text/plain",
            "2026-10-10T00:00:00Z", phase = AttachmentPhase.Failed, localUri = "file://draft")
        var reads = 0
        val queue = AttachmentUploadQueue(files, this, { "student" }, { reads++; ByteArray(1) }, listOf(failed),
            persist = { throw DataError.Unexpected() })
        assertThrows(DataError.Unexpected::class.java) { queue.retry(failed.id) }
        yield()
        assertEquals(listOf(failed), queue.uploads.value)
        assertEquals(0, reads)
        assertTrue(remote.uploadedPaths.isEmpty())
    }

    @Test fun failedDismissAndAcknowledgementPersistenceKeepQueueItemsAndDraftBytes() = runBlocking<Unit> {
        val drafts = AttachmentDraftStore(temporary.newFolder("drafts"))
        val failed = AttachmentUpload("failed-id", "student", FILE_GROUP_ID, "uri", "x.txt", "text/plain",
            "2026-10-10T00:00:00Z", phase = AttachmentPhase.Failed,
            localUri = drafts.preserve("failed-id") { byteArrayOf(1) }.toURI().toString())
        val completed = failed.copy(id = "completed-id", uploadId = "completed-id",
            phase = AttachmentPhase.Completed, fileId = "completed-id",
            localUri = drafts.preserve("completed-id") { byteArrayOf(2) }.toURI().toString())
        var discarded = 0
        val queue = AttachmentUploadQueue(files, this, { "student" }, { ByteArray(1) }, listOf(failed, completed),
            persist = { throw DataError.Unexpected() },
            discardLocal = { discarded++; drafts.discard(it.uploadId) })
        assertThrows(DataError.Unexpected::class.java) { queue.dismiss(failed.id) }
        assertThrows(DataError.Unexpected::class.java) { queue.acknowledge(setOf(completed.fileId!!)) }
        assertEquals(listOf(failed, completed), queue.uploads.value)
        assertEquals(0, discarded)
        assertArrayEquals(byteArrayOf(1), drafts.read(failed.uploadId))
        assertArrayEquals(byteArrayOf(2), drafts.read(completed.uploadId))
    }

    @Test fun failureToPersistPreparedDraftStopsTransferAndRecoversOriginalBytesOnRestart() = runBlocking<Unit> {
        val drafts = AttachmentDraftStore(temporary.newFolder("drafts"))
        val original = byteArrayOf(1, 2, 3)
        var saved: List<AttachmentUpload> = emptyList()
        val queue = AttachmentUploadQueue(files, this, { "student" }, { File(URI(it)).readBytes() },
            persist = { next ->
                if (next.any { it.localUri != null }) throw DataError.Unexpected()
                saved = next
            }, preserveLocally = { task -> drafts.preserve(task.uploadId) { original }.toURI().toString() })
        queue.enqueue(FILE_GROUP_ID, "content://source", "x.txt", "text/plain")
        val failed = withTimeout(5000) { queue.uploads.first { it.single().phase == AttachmentPhase.Failed }.single() }
        assertEquals(AttachmentPhase.Uploading, saved.single().phase)
        assertNull(saved.single().localUri)
        assertArrayEquals(original, drafts.read(failed.uploadId))
        assertTrue(remote.uploadedPaths.isEmpty())

        original.fill(9)
        val restarted = AttachmentUploadQueue(files, this, { "student" }, { File(URI(it)).readBytes() }, saved,
            preserveLocally = { task ->
                drafts.preserve(task.uploadId) { error("The original provider must not be read again.") }.toURI().toString()
            })
        restarted.retry(failed.id)
        withTimeout(5000) { restarted.uploads.first { it.single().phase == AttachmentPhase.Completed } }
        assertArrayEquals(byteArrayOf(1, 2, 3), remote.objects.values.single())
    }

    @Test fun asynchronousResultPersistenceFailureNeverLeavesAnUploadingItemOrLosesItsDraft() = runBlocking<Unit> {
        listOf(AttachmentPhase.Completed, AttachmentPhase.Failed).forEach { expected ->
            val drafts = AttachmentDraftStore(temporary.newFolder("drafts-$expected"))
            val scenarioRemote = FakeFileRemoteDataSource().apply {
                if (expected == AttachmentPhase.Failed) uploadFailure = DataError.Offline()
            }
            val scenarioFiles = DefaultFileRepository(scenarioRemote, { CurrentUser("student", "Student") })
            var saved: List<AttachmentUpload> = emptyList()
            val queue = AttachmentUploadQueue(scenarioFiles, this, { "student" }, { File(URI(it)).readBytes() },
                persist = { next ->
                    if (next.any { it.phase != AttachmentPhase.Uploading }) throw DataError.Unexpected()
                    saved = next
                }, preserveLocally = { task -> drafts.preserve(task.uploadId) { byteArrayOf(1) }.toURI().toString() })
            queue.enqueue(FILE_GROUP_ID, "uri", "x.txt", "text/plain")
            val result = withTimeout(5000) { queue.uploads.first { it.single().phase == expected }.single() }
            assertEquals(AttachmentPhase.Uploading, saved.single().phase)
            assertEquals(result.uploadId, saved.single().uploadId)
            assertEquals(result.localUri, saved.single().localUri)
            assertArrayEquals(byteArrayOf(1), drafts.read(result.uploadId))
            val restarted = AttachmentUploadQueue(scenarioFiles, this, { "student" }, { ByteArray(1) }, saved)
            assertEquals(AttachmentPhase.Failed, restarted.uploads.value.single().phase)
        }
    }

    @Test fun acknowledgementWhileCopyingDiscardsTheReturnedDraftAndNeverUploadsTheRemovedTask() = runBlocking<Unit> {
        val drafts = AttachmentDraftStore(temporary.newFolder("drafts"))
        val copyStarted = CompletableDeferred<Unit>()
        val finishCopy = CompletableDeferred<Unit>()
        val removedNewDraft = CompletableDeferred<Unit>()
        var discards = 0
        val queue = AttachmentUploadQueue(files, this, { "student" }, { error("A removed task must not read its bytes.") },
            preserveLocally = { task ->
                copyStarted.complete(Unit)
                finishCopy.await()
                drafts.preserve(task.uploadId) { byteArrayOf(1) }.toURI().toString()
            }, discardLocal = { task ->
                drafts.discard(task.uploadId)
                if (++discards == 2) removedNewDraft.complete(Unit)
            })
        queue.enqueue(FILE_GROUP_ID, "uri", "x.txt", "text/plain")
        withTimeout(5000) { copyStarted.await() }
        queue.acknowledge(setOf(queue.uploads.value.single().uploadId))
        assertTrue(queue.uploads.value.isEmpty())
        finishCopy.complete(Unit)
        withTimeout(5000) { removedNewDraft.await() }
        assertTrue(remote.uploadedPaths.isEmpty())
        assertTrue(temporary.root.resolve("drafts").listFiles().isNullOrEmpty())
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
