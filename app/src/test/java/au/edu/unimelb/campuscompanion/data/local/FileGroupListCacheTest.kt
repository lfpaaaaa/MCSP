package au.edu.unimelb.campuscompanion.data.local

import au.edu.unimelb.campuscompanion.data.repository.summaryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileGroupListCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aSavedListComesBackAsItWasAndIsReplacedByTheNextSave() {
        val cache = FileGroupListCache(File(folder.root, "groups"))
        val rows = listOf(
            summaryRow("a", latestActivityAt = "2026-10-09T11:00:00+00:00", role = "owner"),
            summaryRow("b")
        )

        cache.save("user-1", rows)
        assertEquals(rows, cache.load("user-1"))

        cache.save("user-1", listOf(summaryRow("c")))
        assertEquals(listOf("c"), cache.load("user-1")?.map { it.id })
    }

    @Test
    fun legacyPrivacyFlagInSavedDataDoesNotHideExistingRecords() {
        val directory = File(folder.root, "groups")
        val cache = FileGroupListCache(directory)
        val rows = listOf(summaryRow("a"))
        cache.save("user-1", rows)
        val saved = directory.listFiles { file -> file.name.endsWith(".json") }!!.single()
        saved.writeText(saved.readText().replaceFirst("[{", "[{\"private_content_enabled\":true,"))

        assertEquals(rows, cache.load("user-1"))
    }

    @Test
    fun nothingSavedOrAnUnreadableFileGivesNull() {
        val directory = File(folder.root, "groups")
        val cache = FileGroupListCache(directory)
        assertNull(cache.load("user-1"))

        cache.save("user-1", listOf(summaryRow("a")))
        directory.listFiles { file -> file.name.endsWith(".json") }!!.single().writeText("{ not json")

        assertNull(cache.load("user-1"))
    }
}
