package au.edu.unimelb.campuscompanion.data.model

import au.edu.unimelb.campuscompanion.data.DataError
import java.time.Instant

/** Current catalog contents and whether they have been checked against the server. */
data class FileCatalogState(
    val files: List<SharedFile> = emptyList(),
    val isLoading: Boolean = true,
    val hasLoaded: Boolean = false,
    val isCached: Boolean = false,
    val error: DataError? = null
)

/** A file shared in a group, as the chat and the file list show it. */
data class SharedFile(
    val id: String,
    val groupId: String,
    val uploaderId: String,
    val uploaderName: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Instant,
    val allowDownloads: Boolean = true
) {
    fun canDownloadBy(userId: String?): Boolean =
        userId != null && (allowDownloads || uploaderId == userId)
}

/**
 * Progress of an upload, emitted by
 * [au.edu.unimelb.campuscompanion.data.repository.FileRepository.uploadFile] until it completes
 * or fails.
 */
sealed interface UploadState {
    data class InProgress(val bytesSent: Long, val totalBytes: Long) : UploadState {
        val fraction: Float
            get() = if (totalBytes <= 0L) 0f else (bytesSent.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    data class Completed(val file: SharedFile) : UploadState

    data class Failed(val error: DataError) : UploadState
}
