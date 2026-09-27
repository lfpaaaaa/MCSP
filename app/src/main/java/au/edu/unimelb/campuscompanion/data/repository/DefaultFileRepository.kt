package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.CurrentUser
import au.edu.unimelb.campuscompanion.data.model.SharedFile
import au.edu.unimelb.campuscompanion.data.model.UploadState
import au.edu.unimelb.campuscompanion.data.remote.FileRemoteDataSource
import au.edu.unimelb.campuscompanion.data.remote.NewFileRecord
import au.edu.unimelb.campuscompanion.data.remote.SharedFileRow
import au.edu.unimelb.campuscompanion.data.remote.dataResult
import au.edu.unimelb.campuscompanion.data.remote.toDataError
import au.edu.unimelb.campuscompanion.data.remote.toModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * [FileRepository] for files kept in private cloud storage. While a group's files are observed,
 * the list is refreshed whenever a member adds a file. An upload stores the object first and then
 * its record; if the record cannot be saved, the object is removed again.
 */
class DefaultFileRepository(
    private val remote: FileRemoteDataSource,
    private val currentUser: () -> CurrentUser?,
    private val newObjectId: () -> String = { UUID.randomUUID().toString() },
    private val firstRetryDelayMillis: Long = FIRST_RETRY_DELAY_MILLIS,
    private val maxRetryDelayMillis: Long = MAX_RETRY_DELAY_MILLIS
) : FileRepository {

    private val filesByGroup = MutableStateFlow<Map<String, List<SharedFileRow>>>(emptyMap())
    private val refreshLock = Mutex()

    override fun observeFiles(groupId: String): Flow<List<SharedFile>> = channelFlow {
        launch { keepFresh(groupId) }
        filesByGroup
            .map { byGroup -> byGroup[groupId] }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { rows -> send(rows.map { it.toModel() }) }
    }

    override fun uploadFile(
        groupId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        isPrivate: Boolean
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
        val type = mimeType.trim().takeIf { it.contains('/') && it.length <= MAX_MIME_TYPE_LENGTH }
            ?: DEFAULT_MIME_TYPE
        val total = bytes.size.toLong()
        val path = "$groupId/${user.id}/${newObjectId()}"

        emit(UploadState.InProgress(bytesSent = 0, totalBytes = total))
        remote.uploadObject(path, bytes, type).collect { sent ->
            emit(UploadState.InProgress(bytesSent = sent.coerceIn(0, total), totalBytes = total))
        }
        val inserted = try {
            remote.insertFileRecord(NewFileRecord(groupId, name, type, total, path, isPrivate))
        } catch (error: Throwable) {
            if (error !is CancellationException) {
                // Without a record nobody could reach the object, so it is removed again.
                dataResult { remote.deleteObject(path) }
            }
            throw error
        }
        val row = inserted.copy(uploaderName = user.displayName)
        filesByGroup.update { byGroup ->
            byGroup + (groupId to listOf(row) + byGroup[groupId].orEmpty().filterNot { it.id == row.id })
        }
        emit(UploadState.Completed(row.toModel()))
    }.catch { error -> emit(UploadState.Failed(error.toDataError())) }

    override suspend fun createDownloadUrl(fileId: String): Result<String> {
        val row = cachedRow(fileId) ?: return Result.failure(DataError.NotFound())
        return dataResult { remote.createSignedUrl(row.storagePath, SIGNED_URL_SECONDS) }
    }

    override suspend fun deleteFile(fileId: String): Result<Unit> {
        val row = cachedRow(fileId) ?: return Result.failure(DataError.NotFound())
        val user = currentUser() ?: return Result.failure(DataError.Unauthenticated())
        if (row.uploaderId != user.id) {
            return Result.failure(DataError.Forbidden())
        }
        return dataResult {
            if (remote.deleteFileRecord(fileId) == 0) {
                throw DataError.NotFound()
            }
            filesByGroup.update { byGroup ->
                byGroup.mapValues { (_, rows) -> rows.filterNot { it.id == fileId } }
            }
            // The record is gone, so a failure here only leaves an unreachable object behind.
            dataResult { remote.deleteObject(row.storagePath) }
            Unit
        }
    }

    /** Keeps the file list of [groupId] up to date for as long as the caller is active. */
    private suspend fun keepFresh(groupId: String) {
        coroutineScope {
            launch { refreshUntilSuccessful(groupId) }
            var attempt = 0
            while (true) {
                try {
                    remote.fileChanges(groupId).collect {
                        attempt = 0
                        launch { refreshUntilSuccessful(groupId) }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    // The last list stays visible while the live feed is joined again.
                }
                attempt++
                delay(retryDelay(attempt))
            }
        }
    }

    private suspend fun refreshUntilSuccessful(groupId: String) {
        var attempt = 0
        while (!refresh(groupId)) {
            attempt++
            delay(retryDelay(attempt))
        }
    }

    private suspend fun refresh(groupId: String): Boolean = refreshLock.withLock {
        dataResult { remote.fetchFiles(groupId) }
            .onSuccess { rows -> filesByGroup.update { byGroup -> byGroup + (groupId to rows) } }
            .isSuccess
    }

    private fun cachedRow(fileId: String): SharedFileRow? =
        filesByGroup.value.values.asSequence().flatten().firstOrNull { it.id == fileId }

    private fun retryDelay(attempt: Int): Long {
        val factor = 1L shl (attempt - 1).coerceIn(0, 6)
        return (firstRetryDelayMillis * factor).coerceAtMost(maxRetryDelayMillis)
    }

    private companion object {
        const val MAX_FILE_NAME_LENGTH = 255
        const val MAX_MIME_TYPE_LENGTH = 255
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        const val SIGNED_URL_SECONDS = 300L
        const val FIRST_RETRY_DELAY_MILLIS = 2_000L
        const val MAX_RETRY_DELAY_MILLIS = 30_000L
    }
}
