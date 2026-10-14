package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.FileCatalogState
import au.edu.unimelb.campuscompanion.data.model.SharedFile
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class GroupFilesPresentationTest {
    private fun file(id: String, name: String, mime: String, date: String = "2026-10-13T09:00:00Z") =
        SharedFile(id, "group", "uploader", "Dani", name, mime, 100, Instant.parse(date), false)

    @Test fun filtersSeparatePhotosVideoAudioAndDocuments() {
        val files = listOf(file("1", "Class.m4a", "audio/mp4"), file("2", "Notes.pdf", "application/pdf"),
            file("3", "Clip.mp4", "video/mp4"), file("4", "Photo.jpg", "image/jpeg"))
        assertEquals(listOf("1"), filterGroupFiles(files, "", GroupFileFilter.Audio).map { it.id })
        assertEquals(listOf("2"), filterGroupFiles(files, "", GroupFileFilter.Documents).map { it.id })
        assertEquals(listOf("3"), filterGroupFiles(files, "", GroupFileFilter.Videos).map { it.id })
        assertEquals(listOf("4"), filterGroupFiles(files, "", GroupFileFilter.Photos).map { it.id })
        assertEquals(4, filterGroupFiles(files, "", GroupFileFilter.All).size)
    }

    @Test fun trimmedSearchMatchesUploaderAndFileNameCaseInsensitively() {
        val files = listOf(file("1", "Lecture.m4a", "audio/mp4"))
        assertEquals(files, filterGroupFiles(files, " LECTURE ", GroupFileFilter.All))
        assertEquals(files, filterGroupFiles(files, "dani", GroupFileFilter.Audio))
        assertTrue(filterGroupFiles(files, "notes", GroupFileFilter.All).isEmpty())
        assertTrue(filterGroupFiles(files, "dani", GroupFileFilter.Documents).isEmpty())
    }

    @Test fun newestFilesAppearFirstAndPreviewOnlyFilesRemainVisible() {
        val older = file("1", "Older.jpg", "image/jpeg", "2026-10-12T09:00:00Z")
        val newer = file("2", "Newer.jpg", "image/jpeg")
        assertEquals(listOf(newer, older), filterGroupFiles(listOf(older, newer), "", GroupFileFilter.Photos))
        assertFalse(newer.canDownloadBy("member"))
        assertTrue(newer.canDownloadBy("uploader"))
    }

    @Test fun equalTimestampsHaveStableOrderingAndMimeTypesTolerateCase() {
        val first = file("1", "one.jpg", "IMAGE/JPEG")
        val second = file("2", "two.jpg", " image/jpeg ")
        assertEquals(listOf(second, first), filterGroupFiles(listOf(first, second), "", GroupFileFilter.Photos))
    }

    @Test fun firstLoadIsLoadingWithoutClaimingTheGroupIsEmpty() {
        val state = presentGroupFiles(FileCatalogState(), "", GroupFileFilter.All)
        assertTrue(state.isLoading)
        assertNull(state.emptyMessage)
        assertNull(state.errorMessage)
    }

    @Test fun loadedEmptyAndFilteredEmptyListsHaveDifferentMessages() {
        val empty = presentGroupFiles(FileCatalogState(isLoading = false, hasLoaded = true), "", GroupFileFilter.All)
        assertEquals("No files have been shared in this group yet.", empty.emptyMessage)
        val filtered = presentGroupFiles(FileCatalogState(files = listOf(file("1", "one.jpg", "image/jpeg")),
            isLoading = false, hasLoaded = true), "notes", GroupFileFilter.All)
        assertEquals("No files match your search or filter.", filtered.emptyMessage)
    }

    @Test fun offlineCachedFilesRemainSearchableWithAnObservableError() {
        val attachment = file("1", "Lecture.m4a", "audio/mp4")
        val state = presentGroupFiles(FileCatalogState(files = listOf(attachment), isLoading = false,
            isCached = true, error = DataError.Offline()), "dani", GroupFileFilter.Audio)
        assertEquals(listOf(attachment), state.files)
        assertTrue(state.canBrowseFiles)
        assertNotNull(state.statusMessage)
        assertNotNull(state.errorMessage)
    }

    @Test fun deniedSignedOutAndMissingGroupStatesHideCachedFiles() {
        listOf(DataError.Forbidden(), DataError.Unauthenticated(), DataError.NotFound()).forEach { error ->
            val state = presentGroupFiles(FileCatalogState(files = listOf(file("1", "one.jpg", "image/jpeg")),
                isLoading = false, hasLoaded = true, isCached = true, error = error), "", GroupFileFilter.All)
            assertFalse(state.canBrowseFiles)
            assertTrue(state.files.isEmpty())
            assertNull(state.statusMessage)
            assertNull(state.emptyMessage)
            assertNotNull(state.errorMessage)
        }
    }

    @Test fun cachedEmptyListAndRefreshHaveUsefulStateBeforeTheServerAnswers() {
        val empty = presentGroupFiles(FileCatalogState(isCached = true), "", GroupFileFilter.All)
        assertNotNull(empty.emptyMessage)
        assertNotNull(empty.statusMessage)
        assertTrue(empty.isLoading)
        val loaded = presentGroupFiles(FileCatalogState(files = listOf(file("1", "one.jpg", "image/jpeg")),
            isLoading = false, hasLoaded = true), "", GroupFileFilter.All)
        assertNull(loaded.statusMessage)
        assertNull(loaded.errorMessage)
    }
}
