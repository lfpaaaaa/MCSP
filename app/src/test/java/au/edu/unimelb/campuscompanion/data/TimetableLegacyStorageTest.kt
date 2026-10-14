package au.edu.unimelb.campuscompanion.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TimetableLegacyStorageTest {
    @Test fun confirmedCleanupOnlyRemovesTheSelectedAccountKeys() = runBlocking<Unit> {
        val disk = mutableMapOf("subscription_url_alice" to "alice-url", "snapshot_alice" to "alice-data",
            "subscription_url_bob" to "bob-url", "snapshot_bob" to "bob-data", "other_setting" to "kept")
        val store = TimetableSubscriptionStore(read = disk::get, confirmedCleanup = { keys ->
            keys.forEach(disk::remove)
            true
        })
        assertTrue(store.clearLegacy("alice"))
        assertEquals(mapOf("subscription_url_bob" to "bob-url", "snapshot_bob" to "bob-data", "other_setting" to "kept"), disk)
    }

    @Test fun cleanupReportsPersistentWriteFailure() = runBlocking<Unit> {
        val store = TimetableSubscriptionStore(read = { "url" }, confirmedCleanup = { false })
        assertFalse(store.clearLegacy("alice"))
        assertEquals("url", store.readLegacy("alice").url)
    }
}
