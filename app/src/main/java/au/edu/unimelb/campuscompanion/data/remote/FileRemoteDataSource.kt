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
    @SerialName("created_at") val createdAt: String,
    @SerialName("allow_downloads") val allowDownloads: Boolean = true,
    @SerialName("duration_ms") val durationMillis: Long? = null,
    @SerialName("is_voice_recording") val isVoiceRecording: Boolean = false
)

/** An uploaded object that still needs its file record. */
data class NewFileRecord(
    val uploadId: String,
    val groupId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val storagePath: String,
    val allowDownloads: Boolean = true,
    val durationMillis: Long? = null,
    val isVoiceRecording: Boolean = false
)

/** The row as the screens use it; a missing uploader name is shown as "Group member". */
fun SharedFileRow.toModel(): SharedFile = SharedFile(
    id = id,
    groupId = groupId,
    uploaderId = uploaderId,
    uploaderName = uploaderName ?: UNKNOWN_UPLOADER_NAME,
    fileName = fileName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    createdAt = parseTimestamp(createdAt),
    allowDownloads = allowDownloads,
    durationMillis = durationMillis,
    isVoiceRecording = isVoiceRecording
)

private const val UNKNOWN_UPLOADER_NAME = "Group member"

/**
 * Server calls behind the file repository. Implementations throw
 * [au.edu.unimelb.campuscompanion.data.DataError] when a call fails.
 */
interface FileRemoteDataSource {
    /** Files of [groupId], newest first, with the uploaders' names. */
    suspend fun fetchFiles(groupId: String): List<SharedFileRow>

    /** Reconciles a prior finalization whose response may have been lost. */
    suspend fun fetchFileRecord(fileId: String): SharedFileRow?

    /** Uploads an object and emits the number of bytes sent so far. Completes once it is stored. */
    fun uploadObject(path: String, bytes: ByteArray, mimeType: String): Flow<Long>

    /** Records an uploaded object as a file of its group, uploaded by the signed-in user. */
    suspend fun finalizeFileRecord(record: NewFileRecord): SharedFileRow

    /** Owner-authorized deletion; server retains durable cleanup work until storage is removed. */
    suspend fun deleteFile(fileId: String)

    /** Deletes one of the signed-in user's file records and returns how many were deleted. */
    suspend fun deleteFileRecord(fileId: String): Int

    suspend fun deleteObject(path: String)

    /** Server-authorized preview or download link; the server chooses its short lifetime. */
    suspend fun createAccessUrl(fileId: String, forDownload: Boolean): String

    /** Emits when [groupId] may have new files: after every (re)connection and every added file. */
    fun fileChanges(groupId: String): Flow<Unit>
}
