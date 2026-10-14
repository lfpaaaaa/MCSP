package au.edu.unimelb.campuscompanion.data.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RecordingDraftStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store(directory: File) = RecordingDraftStore(directory, ::testAudioDuration)
    private fun record(store: RecordingDraftStore, id: String, user: String = "account-a", group: String = "group-a",
        duration: Long = 2_000): RecordingDraft {
        val capture = store.beginCapture(user, group, id, "Voice message.m4a")
        capture.file.writeText("audio:$duration")
        return store.finishCapture(capture)
    }

    @Test
    fun stagedConsentAndImmutableBytesSurviveAStoreRestart() {
        val directory = temporary.newFolder("recordings")
        val original = store(directory)
        val draft = record(original, "clip-1")
        val restricted = original.setDownloadAllowed(original.stage(draft), false)
        val restored = store(directory).load("account-a", "group-a").draft!!
        assertEquals(restricted, restored)
        assertFalse(restored.allowDownloads)
        assertEquals(RecordingDraftPhase.Staged, restored.phase)
        assertEquals("audio:2000", File(restored.localPath).readText())
    }

    @Test
    fun eachAccountAndGroupRestoresOnlyItsOwnRecording() {
        val original = store(temporary.newFolder("recordings"))
        val first = record(original, "clip-a")
        val otherAccount = record(original, "clip-b", user = "account-b")
        val otherGroup = record(original, "clip-c", group = "group-b")
        assertEquals(first, original.load("account-a", "group-a").draft)
        assertEquals(otherAccount, original.load("account-b", "group-a").draft)
        assertEquals(otherGroup, original.load("account-a", "group-b").draft)
        assertNull(original.load("outsider", "group-a").draft)
        assertFalse(original.discard(first.copy(userId = "account-b")))
        assertTrue(File(first.localPath).isFile)
        assertTrue(File(otherAccount.localPath).isFile)
    }

    @Test
    fun interruptedUnfinalizedCaptureIsDiscardedWithAWarning() {
        val directory = temporary.newFolder("recordings")
        val original = store(directory)
        val capture = original.beginCapture("account-a", "group-a", "clip-1", "Voice message.m4a")
        capture.file.writeText("unfinished")
        val restored = store(directory).load("account-a", "group-a")
        assertNull(restored.draft)
        assertNotNull(restored.warning)
        assertFalse(capture.file.exists())
        assertNull(store(directory).load("account-a", "group-a").draft)
    }

    @Test
    fun aPlayableInterruptedCaptureRequiresReviewBeforeItCanBeStaged() {
        val directory = temporary.newFolder("recordings")
        val original = store(directory)
        val capture = original.beginCapture("account-a", "group-a", "clip-1", "Voice message.m4a")
        capture.file.writeText("audio:2500")
        val restored = store(directory).load("account-a", "group-a")
        assertEquals(RecordingDraftPhase.Review, restored.draft!!.phase)
        assertEquals(2_500L, restored.draft.durationMillis)
        assertNotNull(restored.warning)
        assertTrue(File(restored.draft.localPath).isFile)
        assertFalse(capture.file.exists())
    }

    @Test
    fun queuedBytesRemainWhileNewerDraftsCanBeCreatedAndCancelledIndependently() {
        val directory = temporary.newFolder("recordings")
        val original = store(directory)
        val queued = original.markQueued(original.stage(record(original, "clip-queued")))
        assertNull(store(directory).load(queued.userId, queued.groupId).draft)
        val newer = record(original, "clip-new")
        assertFalse(original.discard(queued))
        assertThrows(IllegalArgumentException::class.java) { original.setDownloadAllowed(queued, false) }
        assertTrue(File(queued.localPath).isFile)
        assertTrue(original.discardQueued(queued.userId, queued.groupId, queued.id))
        assertEquals(newer, original.load(newer.userId, newer.groupId).draft)
        assertTrue(File(newer.localPath).isFile)
        assertFalse(File(queued.localPath).exists())
    }

    @Test
    fun queueAcknowledgementCanCompleteAHandoffBeforeItsQueuedMarkerWasSaved() {
        val original = store(temporary.newFolder("recordings"))
        val staged = original.stage(record(original, "clip-1"))
        // enqueue has committed the UUID, but the app stopped before markQueued wrote metadata.
        assertTrue(original.discardQueued(staged.userId, staged.groupId, staged.id))
        assertNull(original.load(staged.userId, staged.groupId).draft)
        assertFalse(File(staged.localPath).exists())
    }

    @Test
    fun aTamperedFinalizedFileCannotBeRestoredOrSentSilently() {
        val directory = temporary.newFolder("recordings")
        val original = store(directory)
        val staged = original.stage(record(original, "clip-1"))
        File(staged.localPath).writeText("damaged")
        val restored = store(directory).load(staged.userId, staged.groupId)
        assertNull(restored.draft)
        assertNotNull(restored.warning)
        assertFalse(File(staged.localPath).exists())
    }

    @Test
    fun aDraftCannotPointOutsideItsOwnedCanonicalRecordingPath() {
        val directory = temporary.newFolder("recordings")
        val original = store(directory)
        val draft = record(original, "clip-1")
        val external = temporary.newFile("external.m4a").apply { writeText("audio:2000") }
        assertThrows(IllegalArgumentException::class.java) {
            original.stage(draft.copy(localPath = external.canonicalPath))
        }
        assertFalse(original.discard(draft.copy(localPath = external.canonicalPath)))
        assertEquals("audio:2000", external.readText())
        assertTrue(File(draft.localPath).isFile)
    }

    @Test
    fun aSymbolicLinkCannotTurnAnOwnedFileNameIntoAnExternalSource() {
        val directory = temporary.newFolder("recordings")
        val original = store(directory)
        val draft = record(original, "clip-1")
        val external = temporary.newFile("external.m4a").apply { writeText("audio:2000") }
        File(draft.localPath).delete()
        Files.createSymbolicLink(File(draft.localPath).toPath(), external.toPath())
        val restored = store(directory).load(draft.userId, draft.groupId)
        assertNull(restored.draft)
        assertNotNull(restored.warning)
        assertEquals("audio:2000", external.readText())
        assertTrue(external.isFile)
    }

    @Test
    fun smallAacFrameRoundingAtTheNativeCapIsClampedButLongerClipsAreRejected() {
        val original = store(temporary.newFolder("recordings"))
        val rounded = record(original, "clip-rounded", duration = MAX_RECORDING_DURATION_MILLIS + 23)
        assertEquals(MAX_RECORDING_DURATION_MILLIS, rounded.durationMillis)
        assertEquals(rounded, original.load(rounded.userId, rounded.groupId).draft)
        original.discard(rounded)
        assertThrows(IllegalStateException::class.java) {
            record(original, "clip-too-long", duration = MAX_RECORDING_DURATION_MILLIS + 2_000)
        }
    }

    @Test
    fun failedQueuedMarkerStillClaimsOwnershipBeforeAnyValidationOrDiskWrite() {
        val directory = temporary.newFolder("recordings")
        var readable = true
        val original = RecordingDraftStore(directory) { file -> if (readable) testAudioDuration(file) else null }
        val staged = original.stage(record(original, "clip-claimed"))
        readable = false
        assertThrows(IllegalStateException::class.java) { original.markQueued(staged) }
        assertFalse(original.discard(staged))
        assertThrows(IllegalArgumentException::class.java) { original.setDownloadAllowed(staged, false) }
        assertThrows(IllegalArgumentException::class.java) { store(directory).stage(staged) }
        assertNull(store(directory).load(staged.userId, staged.groupId).draft)
        assertTrue(File(staged.localPath).isFile)
        val newer = record(store(directory), "clip-new")
        assertTrue(File(newer.localPath).isFile)
        assertTrue(File(staged.localPath).isFile)
    }

    @Test
    fun startupQueueReconciliationRepairsInterruptedHandoffAndCleansOnlyOrphanQueuedSources() {
        val directory = temporary.newFolder("recordings")
        val original = store(directory)
        val pending = original.stage(record(original, "clip-pending"))
        // Queue enqueue persisted, but markQueued was never called before process exit.
        val restarted = store(directory)
        restarted.reconcileQueued(listOf(QueuedRecordingReference(pending.userId, pending.groupId, pending.id)))
        assertNull(restarted.load(pending.userId, pending.groupId).draft)
        assertFalse(restarted.discard(pending))
        assertTrue(File(pending.localPath).isFile)
        val other = restarted.stage(record(restarted, "clip-other", user = "account-b"))
        // Acknowledgement removed queue metadata before recording source cleanup could finish.
        restarted.reconcileQueued(emptyList())
        assertFalse(File(pending.localPath).exists())
        assertEquals(other, restarted.load(other.userId, other.groupId).draft)
        assertTrue(File(other.localPath).isFile)
    }
}
