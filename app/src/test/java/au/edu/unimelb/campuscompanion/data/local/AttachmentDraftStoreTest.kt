package au.edu.unimelb.campuscompanion.data.local

import au.edu.unimelb.campuscompanion.data.DataError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentDraftStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun copiedBytesSurviveRestartAndAreNeverReplacedByChangedProviderContent() = runBlocking<Unit> {
        val directory = temporary.newFolder("drafts")
        val original = byteArrayOf(1, 2, 3)
        AttachmentDraftStore(directory).preserve("upload-id") { original }
        original.fill(9)
        val restarted = AttachmentDraftStore(directory)
        restarted.preserve("upload-id") { error("Original provider is unavailable.") }
        assertArrayEquals(byteArrayOf(1, 2, 3), restarted.read("upload-id"))
        restarted.discard("upload-id")
        restarted.discard("upload-id")
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test fun failedCopyLeavesNoIncompleteDraft() = runBlocking<Unit> {
        val directory = temporary.newFolder("drafts")
        val store = AttachmentDraftStore(directory)
        try {
            store.preserve("upload-id") { throw DataError.Offline() }
            fail("The provider failure should propagate.")
        } catch (_: DataError.Offline) { }
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test fun emptyContentCannotBecomeARetryableDraft() = runBlocking<Unit> {
        val directory = temporary.newFolder("drafts")
        try {
            AttachmentDraftStore(directory).preserve("upload-id") { ByteArray(0) }
            fail("An empty file should be rejected.")
        } catch (_: DataError.Validation) { }
        assertTrue(directory.listFiles().isNullOrEmpty())
    }
}
