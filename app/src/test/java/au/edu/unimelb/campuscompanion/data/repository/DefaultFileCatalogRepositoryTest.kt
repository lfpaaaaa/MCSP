package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.CurrentUser
import au.edu.unimelb.campuscompanion.data.model.FileCatalogState
import au.edu.unimelb.campuscompanion.data.model.UploadState
import au.edu.unimelb.campuscompanion.data.remote.SharedFileRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultFileCatalogRepositoryTest {
    private val remote = FakeFileRemoteDataSource()
    private val cache = CatalogCache()
    private var user: CurrentUser? = CurrentUser("account-a", "Alice")
    private val repository = DefaultFileRepository(remote, { user }, cache,
        firstRetryDelayMillis = 5, maxRetryDelayMillis = 20, reconciliationIntervalMillis = 10)

    @Test
    fun theInitialCatalogIsLoadingBeforeAnyHistoryHasLoaded() = runBlocking<Unit> {
        val initial = repository.observeFileCatalog(FILE_GROUP_ID).first()
        assertTrue(initial.isLoading)
        assertFalse(initial.hasLoaded)
        assertFalse(initial.isCached)
        assertTrue(initial.files.isEmpty())
        assertNull(initial.error)
    }

    @Test
    fun anAuthoritativeEmptyListReplacesPreviouslyCachedContents() = runBlocking<Unit> {
        cache.save("account-a", FILE_GROUP_ID, listOf(fileRow(1)))

        val loaded = withTimeout(5_000) {
            repository.observeFileCatalog(FILE_GROUP_ID).first { it.hasLoaded && !it.isCached && !it.isLoading }
        }

        assertTrue(loaded.files.isEmpty())
        assertNull(loaded.error)
        assertTrue(cache.load("account-a", FILE_GROUP_ID).orEmpty().isEmpty())
    }

    @Test
    fun offlineCatalogsKeepCachedFilesAndExposeTheirFailure() = runBlocking<Unit> {
        cache.save("account-a", FILE_GROUP_ID, listOf(fileRow(1)))
        remote.fetchFailure = DataError.Offline()

        val offline = withTimeout(5_000) {
            repository.observeFileCatalog(FILE_GROUP_ID).first { it.error is DataError.Offline }
        }

        assertEquals(listOf("file-1"), offline.files.map { it.id })
        assertTrue(offline.hasLoaded)
        assertTrue(offline.isCached)
        assertFalse(offline.isLoading)
    }

    @Test
    fun failureWithoutACacheDoesNotClaimThatTheCatalogIsEmpty() = runBlocking<Unit> {
        remote.fetchFailure = DataError.Offline()
        val offline = withTimeout(5_000) {
            repository.observeFileCatalog(FILE_GROUP_ID).first { it.error is DataError.Offline }
        }
        assertFalse(offline.hasLoaded)
        assertFalse(offline.isCached)
        assertFalse(offline.isLoading)
        assertTrue(offline.files.isEmpty())
    }

    @Test
    fun permissionDenialHidesSavedRowsAndStopsAutomaticRetriesUntilAnExplicitRefresh() = runBlocking<Unit> {
        cache.save("account-a", FILE_GROUP_ID, listOf(fileRow(1)))
        remote.fetchFailure = DataError.Forbidden()
        val shown = MutableStateFlow(FileCatalogState())
        val observer = launch { repository.observeFileCatalog(FILE_GROUP_ID).collect { shown.value = it } }
        try {
            val denied = withTimeout(5_000) { shown.first { it.error is DataError.Forbidden } }
            assertTrue(denied.files.isEmpty())
            assertFalse(denied.hasLoaded)
            assertFalse(denied.isCached)
            withTimeout(5_000) {
                while (cache.load("account-a", FILE_GROUP_ID).orEmpty().isNotEmpty()) yield()
            }
            val calls = remote.fetchCount.value
            delay(80)
            assertEquals(calls, remote.fetchCount.value)
            assertEquals(0, remote.events.subscriptionCount.value)

            remote.fetchFailure = null
            remote.stored += fileRow(2)
            repository.refreshFiles(FILE_GROUP_ID).getOrThrow()
            val recovered = withTimeout(5_000) { shown.first { it.files.singleOrNull()?.id == "file-2" } }
            assertNull(recovered.error)
            assertFalse(recovered.isCached)
            withTimeout(5_000) { remote.events.subscriptionCount.first { it > 0 } }
            remote.stored += fileRow(3)
            remote.events.emit(Unit)
            withTimeout(5_000) { shown.first { it.files.size == 2 } }
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test
    fun expiredAuthenticationHidesCachedMetadata() = runBlocking<Unit> {
        cache.save("account-a", FILE_GROUP_ID, listOf(fileRow(1)))
        remote.fetchFailure = DataError.Unauthenticated()
        val denied = withTimeout(5_000) {
            repository.observeFileCatalog(FILE_GROUP_ID).first { it.error is DataError.Unauthenticated }
        }
        assertTrue(denied.files.isEmpty())
        assertFalse(denied.hasLoaded)
        assertTrue(cache.load("account-a", FILE_GROUP_ID).orEmpty().isEmpty())
    }

    @Test
    fun aRemovedGroupDoesNotExposePreviouslyCachedRows() = runBlocking<Unit> {
        cache.save("account-a", FILE_GROUP_ID, listOf(fileRow(1)))
        remote.fetchFailure = DataError.NotFound()
        val missing = withTimeout(5_000) {
            repository.observeFileCatalog(FILE_GROUP_ID).first { it.error is DataError.NotFound }
        }
        assertTrue(missing.files.isEmpty())
        assertFalse(missing.hasLoaded)
        assertFalse(missing.isCached)
    }

    @Test
    fun aSignedOutAccountDoesNotReadTheRemoteOrExposeCacheContents() = runBlocking<Unit> {
        cache.save("account-a", FILE_GROUP_ID, listOf(fileRow(1)))
        user = null
        val signedOut = repository.observeFileCatalog(FILE_GROUP_ID).first()
        assertTrue(signedOut.error is DataError.Unauthenticated)
        assertTrue(signedOut.files.isEmpty())
        assertFalse(signedOut.isLoading)
        assertTrue(repository.refreshFiles(FILE_GROUP_ID).exceptionOrNull() is DataError.Unauthenticated)
        assertEquals(0, remote.fetchCount.value)
    }

    @Test
    fun eachAccountSeesOnlyItsOwnCacheAndOldObserversLoseTheirContents() = runBlocking<Unit> {
        cache.save("account-a", FILE_GROUP_ID, listOf(fileRow(1)))
        cache.save("account-b", FILE_GROUP_ID, listOf(fileRow(2)))
        remote.fetchFailure = DataError.Offline()
        val shownA = MutableStateFlow(FileCatalogState())
        val accountAObserver = launch {
            repository.observeFileCatalog(FILE_GROUP_ID).collect { shownA.value = it }
        }
        try {
            withTimeout(5_000) { shownA.first { it.error is DataError.Offline } }
            assertEquals(listOf("file-1"), shownA.value.files.map { it.id })
            user = CurrentUser("account-b", "Bob")
            val accountB = withTimeout(5_000) {
                repository.observeFileCatalog(FILE_GROUP_ID).first { it.error is DataError.Offline }
            }
            assertEquals(listOf("file-2"), accountB.files.map { it.id })
            withTimeout(5_000) { shownA.first { it.error is DataError.Unauthenticated && it.files.isEmpty() } }
            assertTrue(repository.createPreviewUrl("file-1").exceptionOrNull() is DataError.NotFound)
            assertTrue(remote.accessRequests.isEmpty())
        } finally {
            accountAObserver.cancelAndJoin()
        }
    }

    @Test
    fun aReadThatFinishesAfterAccountSwitchCannotPublishOrWriteToTheNextAccountCache() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        remote.fetchRequest = {
            started.complete(Unit)
            release.await()
            listOf(fileRow(1))
        }
        val oldRead = async { repository.refreshFiles(FILE_GROUP_ID) }
        started.await()
        user = CurrentUser("account-b", "Bob")
        repository.clearSession()
        release.complete(Unit)

        assertTrue(oldRead.await().exceptionOrNull() is DataError.Unauthenticated)
        assertNull(cache.load("account-a", FILE_GROUP_ID))
        assertNull(cache.load("account-b", FILE_GROUP_ID))
        remote.fetchRequest = null
        remote.stored += fileRow(2)
        repository.refreshFiles(FILE_GROUP_ID).getOrThrow()
        assertEquals(listOf("file-2"), repository.observeFileCatalog(FILE_GROUP_ID).first().files.map { it.id })
    }

    @Test
    fun signingOutInvalidatesAnOldReadEvenWhenTheSameAccountSignsInAgain() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        remote.fetchRequest = {
            started.complete(Unit)
            release.await()
            listOf(fileRow(1))
        }
        val oldRead = async { repository.refreshFiles(FILE_GROUP_ID) }
        started.await()
        repository.clearSession()
        release.complete(Unit)
        assertTrue(oldRead.await().exceptionOrNull() is DataError.Unauthenticated)
        assertNull(cache.load("account-a", FILE_GROUP_ID))
    }

    @Test
    fun deletionCannotBeUndoneLocallyByAnEarlierHistoryRead() = runBlocking<Unit> {
        val row = fileRow(1, uploaderId = "account-a")
        remote.stored += row
        repository.refreshFiles(FILE_GROUP_ID).getOrThrow()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        remote.fetchRequest = {
            val snapshot = remote.stored.toList()
            started.complete(Unit)
            release.await()
            snapshot
        }
        val refresh = async { repository.refreshFiles(FILE_GROUP_ID) }
        started.await()
        val deletion = async { repository.deleteFile(row.id) }
        yield()
        assertTrue(remote.stored.isEmpty())
        release.complete(Unit)
        refresh.await().getOrThrow()
        deletion.await().getOrThrow()
        assertTrue(repository.observeFileCatalog(FILE_GROUP_ID).first().files.isEmpty())
        assertTrue(cache.load("account-a", FILE_GROUP_ID).orEmpty().isEmpty())
    }

    @Test
    fun uploadConfirmationCannotBeOverwrittenByAnEarlierHistoryRead() = runBlocking<Unit> {
        repository.refreshFiles(FILE_GROUP_ID).getOrThrow()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        remote.fetchRequest = {
            val snapshot = remote.stored.toList()
            started.complete(Unit)
            release.await()
            snapshot
        }
        val refresh = async { repository.refreshFiles(FILE_GROUP_ID) }
        started.await()
        val upload = async {
            repository.uploadFile(FILE_GROUP_ID, "new.pdf", "application/pdf", byteArrayOf(1)).toList()
        }
        yield()
        assertEquals(1, remote.stored.size)
        release.complete(Unit)
        refresh.await().getOrThrow()
        val completed = upload.await().last() as UploadState.Completed
        assertEquals(listOf(completed.file.id), repository.observeFileCatalog(FILE_GROUP_ID).first().files.map { it.id })
        assertEquals(listOf(completed.file.id), cache.load("account-a", FILE_GROUP_ID)?.map { it.id })
    }
}

private class CatalogCache : SharedFileListCache {
    private val rows = mutableMapOf<Pair<String, String>, List<SharedFileRow>>()
    @Synchronized override fun load(userId: String, groupId: String) = rows[userId to groupId]
    @Synchronized override fun save(userId: String, groupId: String, rows: List<SharedFileRow>) {
        this.rows[userId to groupId] = rows
    }
}
