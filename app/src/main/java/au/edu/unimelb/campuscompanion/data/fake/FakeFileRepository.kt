package au.edu.unimelb.campuscompanion.data.fake

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.SharedFile
import au.edu.unimelb.campuscompanion.data.model.UploadState
import au.edu.unimelb.campuscompanion.data.repository.FileRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.UUID

/** In-memory [FileRepository] that reports upload progress in a few steps. */
class FakeFileRepository(
    private val currentUserId: String = FakeData.CURRENT_USER_ID,
    private val currentUserName: String = FakeData.CURRENT_USER_NAME,
    private val latencyMillis: Long = FakeData.DEFAULT_LATENCY_MILLIS,
    private val clock: () -> Instant = Instant::now
) : FileRepository {

    private val files = MutableStateFlow(FakeData.files(clock()))

    override fun observeFiles(groupId: String): Flow<List<SharedFile>> =
        files.map { list -> list.filter { it.groupId == groupId }.sortedByDescending { it.createdAt } }

    override fun uploadFile(
        groupId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        isPrivate: Boolean
    ): Flow<UploadState> = flow {
        if (bytes.isEmpty() || bytes.size > FileRepository.MAX_FILE_SIZE_BYTES) {
            emit(UploadState.Failed(DataError.Validation("Files must be between 1 byte and 20 MB.")))
            return@flow
        }
        val total = bytes.size.toLong()
        for (step in 1..PROGRESS_STEPS) {
            delay(latencyMillis / PROGRESS_STEPS)
            emit(UploadState.InProgress(bytesSent = total * step / PROGRESS_STEPS, totalBytes = total))
        }
        val file = SharedFile(
            id = UUID.randomUUID().toString(),
            groupId = groupId,
            uploaderId = currentUserId,
            uploaderName = currentUserName,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = total,
            isPrivate = isPrivate,
            createdAt = clock()
        )
        files.update { list -> list + file }
        emit(UploadState.Completed(file))
    }

    override suspend fun createDownloadUrl(fileId: String): Result<String> {
        delay(latencyMillis)
        return if (files.value.any { it.id == fileId }) {
            Result.success("https://example.invalid/files/$fileId")
        } else {
            Result.failure(DataError.NotFound())
        }
    }

    override suspend fun deleteFile(fileId: String): Result<Unit> {
        delay(latencyMillis)
        val file = files.value.firstOrNull { it.id == fileId }
            ?: return Result.failure(DataError.NotFound())
        if (file.uploaderId != currentUserId) {
            return Result.failure(DataError.Forbidden())
        }
        files.update { list -> list.filterNot { it.id == fileId } }
        return Result.success(Unit)
    }

    private companion object {
        const val PROGRESS_STEPS = 4
    }
}
