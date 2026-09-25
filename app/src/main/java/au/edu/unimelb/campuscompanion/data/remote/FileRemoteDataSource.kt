package au.edu.unimelb.campuscompanion.data.remote

import au.edu.unimelb.campuscompanion.data.model.SharedFile
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A row of `public.shared_files`. [uploaderName] is only filled in by `group_files`. */
@Serializable
data class SharedFileRow(
    val id: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("uploader_id") val uploaderId: String,
    @SerialName("uploader_name") val uploaderName: String? = null,
    @SerialName("file_name") val fileName: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("storage_path") val storagePath: String,
    @SerialName("is_private") val isPrivate: Boolean = false,
    @SerialName("created_at") val createdAt: String
)

/** An uploaded object that still needs its file record. */
data class NewFileRecord(
    val groupId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val storagePath: String,
    val isPrivate: Boolean
)

fun SharedFileRow.toModel(): SharedFile = SharedFile(
    id = id,
    groupId = groupId,
    uploaderId = uploaderId,
    uploaderName = uploaderName ?: UNKNOWN_UPLOADER_NAME,
    fileName = fileName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    isPrivate = isPrivate,
    createdAt = parseTimestamp(createdAt)
)

private const val UNKNOWN_UPLOADER_NAME = "Group member"

/**
 * Server calls behind the file repository. Implementations throw
 * [au.edu.unimelb.campuscompanion.data.DataError] when a call fails.
 */
interface FileRemoteDataSource {
    /** Files of [groupId], newest first, with the uploaders' names. */
    suspend fun fetchFiles(groupId: String): List<SharedFileRow>

    /** Uploads an object and emits the number of bytes sent so far. Completes once it is stored. */
    fun uploadObject(path: String, bytes: ByteArray, mimeType: String): Flow<Long>

    /** Records an uploaded object as a file of its group, uploaded by the signed-in user. */
    suspend fun insertFileRecord(record: NewFileRecord): SharedFileRow

    /** Deletes one of the signed-in user's file records and returns how many were deleted. */
    suspend fun deleteFileRecord(fileId: String): Int

    suspend fun deleteObject(path: String)

    /** A download link for [path] that stops working after [expiresInSeconds]. */
    suspend fun createSignedUrl(path: String, expiresInSeconds: Long): String

    /** Emits when [groupId] may have new files: after every (re)connection and every added file. */
    fun fileChanges(groupId: String): Flow<Unit>
}
