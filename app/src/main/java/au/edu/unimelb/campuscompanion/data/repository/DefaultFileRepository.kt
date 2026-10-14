package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.CurrentUser
import au.edu.unimelb.campuscompanion.data.model.FileCatalogState
import au.edu.unimelb.campuscompanion.data.model.SharedFile
import au.edu.unimelb.campuscompanion.data.model.UploadState
import au.edu.unimelb.campuscompanion.data.remote.FileRemoteDataSource
import au.edu.unimelb.campuscompanion.data.remote.NewFileRecord
import au.edu.unimelb.campuscompanion.data.remote.SharedFileRow
import au.edu.unimelb.campuscompanion.data.remote.dataResult
import au.edu.unimelb.campuscompanion.data.remote.toDataError
import au.edu.unimelb.campuscompanion.data.remote.toModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * [FileRepository] for files kept in private cloud storage. While a group's files are observed,
 * the list is refreshed whenever a member adds a file; the last list of each group is kept in
 * [cache], so an open chat still shows what was shared when there is no connection. An upload
 * uses a stable object path and idempotent finalization so retries survive lost responses.
 */
class DefaultFileRepository(
    private val remote: FileRemoteDataSource,
    private val currentUser: () -> CurrentUser?,
    private val cache: SharedFileListCache? = null,
    private val newObjectId: () -> String = { UUID.randomUUID().toString() },
    private val firstRetryDelayMillis: Long = FIRST_RETRY_DELAY_MILLIS,
    private val maxRetryDelayMillis: Long = MAX_RETRY_DELAY_MILLIS,
    private val reconciliationIntervalMillis: Long = RECONCILIATION_INTERVAL_MILLIS
) : FileRepository {

    private class Session(val userId: String)
    private data class Catalog(
        val rows: List<SharedFileRow> = emptyList(),
        val isLoading: Boolean = true,
        val hasLoaded: Boolean = false,
        val isCached: Boolean = false,
        val error: DataError? = null,
        val cacheRead: Boolean = false
    ) {
        fun toModel() = FileCatalogState(rows.map(SharedFileRow::toModel), isLoading, hasLoaded, isCached, error)
    }
    private data class CatalogStore(val session: Session? = null, val groups: Map<String, Catalog> = emptyMap())
    private val catalogs = MutableStateFlow(CatalogStore())
    private val refreshLock = Mutex()

    override fun clearSession() {
        catalogs.value = CatalogStore()
    }

    override fun observeFiles(groupId: String): Flow<List<SharedFile>> = observeFileCatalog(groupId)
        .filter { it.hasLoaded || it.error != null }
        .map { it.files }
        .distinctUntilChanged()

    override fun observeFileCatalog(groupId: String): Flow<FileCatalogState> = channelFlow {
        val session = dataResult { currentSession() }.getOrElse {
            send(FileCatalogState(isLoading = false, error = it.toDataError()))
            return@channelFlow
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            catalogs.map { store ->
                if (store.session === session && isCurrent(session)) {
                    (store.groups[groupId] ?: Catalog()).toModel()
                } else FileCatalogState(isLoading = false, error = DataError.Unauthenticated())
            }
            .distinctUntilChanged()
            .collect { send(it) }
        }
        showSavedList(session, groupId)
        if (isCurrent(session)) launch { keepFresh(session, groupId) }
        awaitCancellation()
    }

    override suspend fun refreshFiles(groupId: String): Result<Unit> = dataResult {
        val session = currentSession()
        showSavedList(session, groupId)
        refresh(session, groupId).getOrThrow()
    }

    override fun uploadFile(
        groupId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        allowDownloads: Boolean,
        identity: FileUploadIdentity?
    ): Flow<UploadState> = flow {
        val name = fileName.trim()
        if (bytes.isEmpty() || bytes.size > FileRepository.MAX_FILE_SIZE_BYTES) {
            emit(UploadState.Failed(DataError.Validation("Files must be between 1 byte and 20 MB.")))
            return@flow
        }
        if (name.isEmpty() || name.length > MAX_FILE_NAME_LENGTH) {
            emit(UploadState.Failed(DataError.Validation("File names need 1 to $MAX_FILE_NAME_LENGTH characters.")))
            return@flow
        }
        val user = currentUser()
        if (user == null) {
            emit(UploadState.Failed(DataError.Unauthenticated()))
            return@flow
        }
        val session = currentSession()
        val type = mimeType.trim().takeIf { it.contains('/') && it.length <= MAX_MIME_TYPE_LENGTH }
            ?: DEFAULT_MIME_TYPE
        val total = bytes.size.toLong()
        val upload = identity ?: newObjectId().let { FileUploadIdentity(it, "$groupId/${user.id}/$it") }
        if (upload.storagePath != "$groupId/${user.id}/${upload.id}" || upload.id.contains('/')) {
            emit(UploadState.Failed(DataError.Validation("This upload belongs to a different account or group.")))
            return@flow
        }
        if (upload.durationMillis != null && upload.durationMillis <= 0 ||
            upload.isVoiceRecording && (!type.startsWith("audio/", ignoreCase = true) || upload.durationMillis == null || upload.durationMillis > 600_000)) {
            emit(UploadState.Failed(DataError.Validation("Voice recordings need valid audio and a duration of up to 10 minutes.")))
            return@flow
        }
        val record = NewFileRecord(upload.id, groupId, name, type, total, upload.storagePath, allowDownloads,
            upload.durationMillis, upload.isVoiceRecording)

        emit(UploadState.InProgress(bytesSent = 0, totalBytes = total))
        val inserted = remote.fetchFileRecord(upload.id) ?: run {
            var uploadError: Throwable? = null
            try {
                remote.uploadObject(upload.storagePath, bytes, type).collect { sent ->
                    emit(UploadState.InProgress(bytesSent = sent.coerceIn(0, total), totalBytes = total))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Storage may have committed before its reply was lost. The server checks that
                // the owned object exists with the expected size before finalizing it.
                uploadError = error
            }
            try {
                remote.finalizeFileRecord(record)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Never delete here: finalization may already have committed on the server.
                throw uploadError ?: error
            }
        }
        if (inserted.id != upload.id || inserted.storagePath != upload.storagePath ||
            inserted.uploaderId != user.id || inserted.groupId != groupId ||
            inserted.fileName != name || inserted.mimeType != type || inserted.sizeBytes != total ||
            inserted.allowDownloads != allowDownloads || inserted.durationMillis != upload.durationMillis ||
            inserted.isVoiceRecording != upload.isVoiceRecording) {
            throw DataError.Validation("This upload no longer matches its original file.")
        }
        requireCurrent(session)
        val row = inserted.copy(uploaderName = user.displayName)
        refreshLock.withLock {
            storeList(session, groupId) { rows -> listOf(row) + rows.filterNot { it.id == row.id } }
        }
        requireCurrent(session)
        emit(UploadState.Completed(row.toModel()))
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(UploadState.Failed(error.toDataError()))
    }

    override suspend fun createPreviewUrl(fileId: String): Result<String> = dataResult {
        val session = currentSession()
        if (cachedRow(session, fileId) == null) throw DataError.NotFound()
        remote.createAccessUrl(fileId, forDownload = false).also { requireCurrent(session) }
    }

    override suspend fun createDownloadUrl(fileId: String): Result<String> = dataResult {
        val session = currentSession()
        val row = cachedRow(session, fileId) ?: throw DataError.NotFound()
        if (!row.toModel().canDownloadBy(session.userId)) {
            throw DataError.Validation("The uploader has disabled downloads for this attachment.")
        }
        // The server checks the current membership and saved permission again.
        remote.createAccessUrl(fileId, forDownload = true).also { requireCurrent(session) }
    }

    override suspend fun deleteFile(fileId: String): Result<Unit> = dataResult {
        val session = currentSession()
        val row = cachedRow(session, fileId)
        if (row != null && row.uploaderId != session.userId) {
            throw DataError.Forbidden()
        }
        // The server keeps a durable job and removes metadata only after Storage succeeds.
        // A failure leaves the cached row visible so the uploader can retry.
        remote.deleteFile(fileId)
        requireCurrent(session)
        if (row != null) refreshLock.withLock {
            storeList(session, row.groupId) { rows -> rows.filterNot { it.id == fileId } }
        }
        requireCurrent(session)
        Unit
    }

    /** Keeps the file list of [groupId] up to date for as long as the caller is active. */
    private suspend fun keepFresh(session: Session, groupId: String) {
        while (isCurrent(session)) {
            synchronizeWhileAllowed(session, groupId)
            if (!isCurrent(session)) return
            // A denial stops network work. An explicit successful refresh after rejoining resumes
            // this observer without requiring the user to close and reopen the file browser.
            catalogs.first { store ->
                !isCurrent(session) || store.groups[groupId]?.let {
                    it.hasLoaded && !it.isLoading && it.error == null
                } == true
            }
        }
    }

    private suspend fun synchronizeWhileAllowed(session: Session, groupId: String) {
        coroutineScope {
            // Coalesce reconnect/insert events while a read or retry is already in progress.
            val requests = Channel<Unit>(Channel.CONFLATED)
            val periodic = launch {
                while (isCurrent(session)) {
                    requests.send(Unit)
                    // Realtime DELETE filtering/RLS does not reliably identify a group. Reconcile
                    // while observed so another member's deletion also disappears locally.
                    delay(reconciliationIntervalMillis.coerceAtLeast(1))
                }
            }
            val realtime = launch {
                var attempt = 0
                while (isCurrent(session)) {
                    try {
                        remote.fileChanges(groupId).collect {
                            attempt = 0
                            requests.trySend(Unit)
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        requests.trySend(Unit)
                    }
                    delay(retryDelay(++attempt))
                }
            }
            try {
                for (request in requests) {
                    var attempt = 0
                    while (isCurrent(session)) {
                        val result = refresh(session, groupId)
                        if (result.isSuccess) break
                        if (result.exceptionOrNull().isAccessDenied()) return@coroutineScope
                        delay(retryDelay(++attempt))
                    }
                    if (!isCurrent(session)) return@coroutineScope
                }
            } finally {
                periodic.cancel()
                realtime.cancel()
                requests.close()
            }
        }
    }

    private suspend fun refresh(session: Session, groupId: String): Result<Unit> = refreshLock.withLock {
        dataResult {
            requireCurrent(session)
            updateCatalog(session, groupId) { it.copy(isLoading = true, error = null) }
            val fetched = dataResult { remote.fetchFiles(groupId) }
            requireCurrent(session)
            fetched.fold(
                onSuccess = { rows -> storeList(session, groupId) { rows } },
                onFailure = { error ->
                    val failure = error.toDataError()
                    if (failure.isAccessDenied()) {
                        updateCatalog(session, groupId) {
                            Catalog(isLoading = false, error = failure, cacheRead = true)
                        }
                        saveCache(session, groupId, emptyList())
                    } else {
                        updateCatalog(session, groupId) {
                            it.copy(isLoading = false, isCached = it.hasLoaded, error = failure)
                        }
                    }
                    throw failure
                }
            )
            requireCurrent(session)
        }
    }

    /**
     * Seeds the list of [groupId] from the copy on the device, so the chat shows its files before
     * the server answers and without a connection. A fresh list replaces it as soon as one arrives.
     */
    private suspend fun showSavedList(session: Session, groupId: String) = refreshLock.withLock {
        if (!isCurrent(session) || catalogs.value.groups[groupId]?.cacheRead == true) return@withLock
        val saved = withContext(Dispatchers.IO) {
            runCatching { cache?.load(session.userId, groupId) }.getOrNull()
        }
        if (!isCurrent(session)) return@withLock
        updateCatalog(session, groupId) {
            if (saved == null) it.copy(cacheRead = true)
            else it.copy(rows = saved, hasLoaded = true, isCached = true, cacheRead = true)
        }
    }

    /**
     * Replaces the list of [groupId] with [transform] of the current one and keeps a copy on the
     * device. The copy is written even if the caller is cancelled meanwhile, so it never lags.
     */
    private suspend fun storeList(session: Session, groupId: String, transform: (List<SharedFileRow>) -> List<SharedFileRow>) {
        requireCurrent(session)
        val rows = transform(catalogs.value.groups[groupId]?.rows.orEmpty())
        updateCatalog(session, groupId) {
            Catalog(rows, isLoading = false, hasLoaded = true, cacheRead = true)
        }
        saveCache(session, groupId, rows)
    }

    private suspend fun saveCache(session: Session, groupId: String, rows: List<SharedFileRow>) {
        val fileCache = cache ?: return
        // Retain the account that authorized the operation even if sign-out occurs during I/O.
        withContext(Dispatchers.IO + NonCancellable) { runCatching { fileCache.save(session.userId, groupId, rows) } }
    }

    private fun updateCatalog(session: Session, groupId: String, transform: (Catalog) -> Catalog) {
        requireCurrent(session)
        catalogs.update { store ->
            if (store.session !== session) store
            else store.copy(groups = store.groups + (groupId to transform(store.groups[groupId] ?: Catalog())))
        }
    }

    private fun currentSession(): Session {
        val userId = currentUser()?.id ?: run { clearSession(); throw DataError.Unauthenticated() }
        return catalogs.updateAndGet { store ->
            if (store.session?.userId == userId) store else CatalogStore(Session(userId))
        }.session!!.also { requireCurrent(it) }
    }

    private fun isCurrent(session: Session): Boolean =
        catalogs.value.session === session && currentUser()?.id == session.userId

    private fun requireCurrent(session: Session) {
        if (!isCurrent(session)) throw DataError.Unauthenticated()
    }

    private fun cachedRow(session: Session, fileId: String): SharedFileRow? =
        catalogs.value.takeIf { it.session === session }?.groups?.values
            ?.asSequence()?.flatMap { it.rows.asSequence() }?.firstOrNull { it.id == fileId }

    private fun Throwable?.isAccessDenied(): Boolean =
        this is DataError.Forbidden || this is DataError.Unauthenticated || this is DataError.NotFound

    private fun retryDelay(attempt: Int): Long {
        val factor = 1L shl (attempt - 1).coerceIn(0, 6)
        return (firstRetryDelayMillis * factor).coerceAtMost(maxRetryDelayMillis)
    }

    private companion object {
        const val MAX_FILE_NAME_LENGTH = 255
        const val MAX_MIME_TYPE_LENGTH = 255
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        const val FIRST_RETRY_DELAY_MILLIS = 2_000L
        const val MAX_RETRY_DELAY_MILLIS = 30_000L
        const val RECONCILIATION_INTERVAL_MILLIS = 30_000L
    }
}
