package au.edu.unimelb.campuscompanion.data.local

import au.edu.unimelb.campuscompanion.data.repository.FILE_GROUP_ID
import au.edu.unimelb.campuscompanion.data.repository.fileRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileSharedFileListCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aSavedListComesBackAsItWasAndIsReplacedByTheNextSave() {
        val cache = FileSharedFileListCache(File(folder.root, "files"))
        val rows = listOf(fileRow(2), fileRow(1))

        cache.save("user-1", FILE_GROUP_ID, rows)
        assertEquals(rows, cache.load("user-1", FILE_GROUP_ID))

        cache.save("user-1", FILE_GROUP_ID, listOf(fileRow(3)))
        assertEquals(listOf("file-3"), cache.load("user-1", FILE_GROUP_ID)?.map { it.id })
    }

    @Test
    fun listsAreKeptPerUserAndGroup() {
        val cache = FileSharedFileListCache(File(folder.root, "files"))
        cache.save("user-1", FILE_GROUP_ID, listOf(fileRow(1)))

        assertNull(cache.load("user-2", FILE_GROUP_ID))
        assertNull(cache.load("user-1", "another-group"))
    }

    @Test
    fun nothingSavedOrAnUnreadableFileGivesNull() {
        val directory = File(folder.root, "files")
        val cache = FileSharedFileListCache(directory)
        assertNull(cache.load("user-1", FILE_GROUP_ID))

        cache.save("user-1", FILE_GROUP_ID, listOf(fileRow(1)))
        directory.listFiles { file -> file.name.endsWith(".json") }!!.single().writeText("{ not json")

        assertNull(cache.load("user-1", FILE_GROUP_ID))
    }
}
