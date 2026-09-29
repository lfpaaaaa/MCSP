package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.remote.FileRemoteDataSource
import au.edu.unimelb.campuscompanion.data.remote.NewFileRecord
import au.edu.unimelb.campuscompanion.data.remote.SharedFileRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

const val FILE_GROUP_ID = "group-2"

/** Keeps files in memory the way storage and the database do, and records every call. */
class FakeFileRemoteDataSource : FileRemoteDataSource {
    var uploadFailure: DataError? = null
    var recordFailure: DataError? = null

    val stored = mutableListOf<SharedFileRow>()
    val uploadedPaths = mutableListOf<String>()
    val deletedPaths = mutableListOf<String>()
    val deletedRecords = mutableListOf<String>()
    val events = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val fetchCount = MutableStateFlow(0)

    override suspend fun fetchFiles(groupId: String): List<SharedFileRow> {
        fetchCount.value += 1
        return stored.filter { it.groupId == groupId }.sortedByDescending { it.createdAt }
    }

    override fun uploadObject(path: String, bytes: ByteArray, mimeType: String): Flow<Long> = flow {
        uploadedPaths += path
        uploadFailure?.let { throw it }
        emit(bytes.size / 2L)
        emit(bytes.size.toLong())
    }

    override suspend fun insertFileRecord(record: NewFileRecord): SharedFileRow {
        recordFailure?.let { throw it }
        val row = SharedFileRow(
            id = "file-${stored.size + 100}",
            groupId = record.groupId,
            uploaderId = record.storagePath.split('/')[1],
            fileName = record.fileName,
            mimeType = record.mimeType,
            sizeBytes = record.sizeBytes,
            storagePath = record.storagePath,
            isPrivate = record.isPrivate,
            createdAt = "2026-09-25T06:00:00Z"
        )
        stored += row
        return row
    }

    override suspend fun deleteFileRecord(fileId: String): Int {
        deletedRecords += fileId
        return if (stored.removeAll { it.id == fileId }) 1 else 0
    }

    override suspend fun deleteObject(path: String) {
        deletedPaths += path
    }

    override suspend fun createSignedUrl(path: String, expiresInSeconds: Long): String =
        "https://signed.example/$path?expires=$expiresInSeconds"

    override fun fileChanges(groupId: String): Flow<Unit> = events
}

/** File number [n], uploaded by another member at 05:nn. */
fun fileRow(n: Int, uploaderId: String = "user-2", uploaderName: String? = "Bob") = SharedFileRow(
    id = "file-$n",
    groupId = FILE_GROUP_ID,
    uploaderId = uploaderId,
    uploaderName = uploaderName,
    fileName = "notes-$n.pdf",
    mimeType = "application/pdf",
    sizeBytes = 1_000L * n,
    storagePath = "$FILE_GROUP_ID/$uploaderId/object-$n",
    createdAt = "2026-09-25T05:" + n.toString().padStart(2, '0') + ":00Z"
)
