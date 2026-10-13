package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.data.model.SharedFile
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class GroupFilesPresentationTest {
    private fun file(id: String, name: String, mime: String, date: String = "2026-10-13T09:00:00Z") =
        SharedFile(id, "group", "uploader", "Dani", name, mime, 100, Instant.parse(date), false)
    @Test fun audioIsSeparateFromDocumentsAndVideo() {
        val files = listOf(file("1", "Class.m4a", "audio/mp4"), file("2", "Notes.pdf", "application/pdf"), file("3", "Clip.mp4", "video/mp4"))
        assertEquals(listOf("1"), filterGroupFiles(files, "", GroupFileFilter.Audio).map { it.id })
        assertEquals(listOf("2"), filterGroupFiles(files, "", GroupFileFilter.Documents).map { it.id })
    }
    @Test fun trimmedSearchMatchesUploaderAndFileNameCaseInsensitively() {
        val files = listOf(file("1", "Lecture.m4a", "audio/mp4"))
        assertEquals(files, filterGroupFiles(files, " LECTURE ", GroupFileFilter.All))
        assertEquals(files, filterGroupFiles(files, "dani", GroupFileFilter.Audio))
        assertTrue(filterGroupFiles(files, "notes", GroupFileFilter.All).isEmpty())
    }
    @Test fun newestFilesAppearFirstAndPreviewOnlyFilesRemainVisible() {
        val older = file("1", "Older.jpg", "image/jpeg", "2026-10-12T09:00:00Z")
        val newer = file("2", "Newer.jpg", "image/jpeg")
        assertEquals(listOf(newer, older), filterGroupFiles(listOf(older, newer), "", GroupFileFilter.Photos))
        assertFalse(newer.canDownloadBy("member"))
        assertTrue(newer.canDownloadBy("uploader"))
    }
}
