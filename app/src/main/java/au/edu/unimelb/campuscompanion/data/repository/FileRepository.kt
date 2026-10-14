package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.model.SharedFile
import au.edu.unimelb.campuscompanion.data.model.FileCatalogState
import au.edu.unimelb.campuscompanion.data.model.UploadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Persisted before sending so a retry addresses the same object and metadata row. */
data class FileUploadIdentity(
    val id: String,
    val storagePath: String,
    val durationMillis: Long? = null,
    val isVoiceRecording: Boolean = false
)

/** Files shared inside a group, kept in private cloud storage. */
interface FileRepository {
    fun observeFiles(groupId: String): Flow<List<SharedFile>>

    /** Catalog status, including retained offline contents and recoverable loading failures. */
    fun observeFileCatalog(groupId: String): Flow<FileCatalogState> = observeFiles(groupId).map {
        FileCatalogState(files = it, isLoading = false, hasLoaded = true)
    }

    /** Explicitly refreshes remote contents; in-memory repositories already publish their changes. */
    suspend fun refreshFiles(groupId: String): Result<Unit> = Result.success(Unit)

    /** Invalidates in-memory data and in-flight publications when the account signs out. */
    fun clearSession() {}

    /** Uploads a file and reports progress until it completes or fails. */
    fun uploadFile(
        groupId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        allowDownloads: Boolean = true,
        identity: FileUploadIdentity? = null
    ): Flow<UploadState>

    /** Creates a member-only link for rendering an attachment within the app. */
    suspend fun createPreviewUrl(fileId: String): Result<String>

    /** Creates a download link only if the uploader permits it or the requester is the uploader. */
    suspend fun createDownloadUrl(fileId: String): Result<String>

    /** Deletes a file. Only the member who uploaded it can delete it. */
    suspend fun deleteFile(fileId: String): Result<Unit>

    companion object {
        const val MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024
    }
}
