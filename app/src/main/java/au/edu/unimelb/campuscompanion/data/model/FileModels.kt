package au.edu.unimelb.campuscompanion.data.model

import au.edu.unimelb.campuscompanion.data.DataError
import java.time.Instant

data class SharedFile(
    val id: String,
    val groupId: String,
    val uploaderId: String,
    val uploaderName: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Private files require biometric confirmation before they are opened. */
    val isPrivate: Boolean,
    val createdAt: Instant
)

sealed interface UploadState {
    data class InProgress(val bytesSent: Long, val totalBytes: Long) : UploadState {
        val fraction: Float
            get() = if (totalBytes <= 0L) 0f else (bytesSent.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    data class Completed(val file: SharedFile) : UploadState

    data class Failed(val error: DataError) : UploadState
}
