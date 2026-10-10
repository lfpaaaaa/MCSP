package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.data.model.UploadState
import au.edu.unimelb.campuscompanion.data.repository.FileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/** Where an attachment is on its way to the group: being uploaded, stopped by an error, or stored. */
@Serializable
enum class AttachmentPhase { Uploading, Failed, Completed }

/**
 * An attachment the user chose to share, with the progress of its upload. The queue keeps it
 * across restarts, so a failed upload can be retried later.
 */
@Serializable
data class AttachmentUpload(
    val id: String,
    val userId: String,
    val groupId: String,
    val uri: String,
    val fileName: String,
    val mimeType: String,
    val createdAt: String,
    val phase: AttachmentPhase = AttachmentPhase.Uploading,
    val progress: Float = 0f,
    val fileId: String? = null,
    val error: String? = null
)

/** App-owned delivery; interrupted uploads survive restart as retryable items. */
class AttachmentUploadQueue(
    private val files: FileRepository,
    private val scope: CoroutineScope,
    private val currentUserId: () -> String?,
    private val readBytes: suspend (String) -> ByteArray,
    restored: List<AttachmentUpload> = emptyList(),
    private val persist: (List<AttachmentUpload>) -> Unit = {},
    private val onCompleted: suspend () -> Unit = {}
) {
    private val state = MutableStateFlow(restored.map {
        if (it.phase == AttachmentPhase.Uploading) it.copy(phase = AttachmentPhase.Failed,
            error = "Upload interrupted. Tap Retry.") else it
    })
    val uploads = state.asStateFlow()
    private val transferSlots = Semaphore(1) // Bound memory: existing storage API takes a byte array.

    @Synchronized
    fun enqueue(groupId: String, uri: String, name: String, type: String) {
        val userId = currentUserId() ?: throw DataError.Unauthenticated()
        val task = AttachmentUpload(UUID.randomUUID().toString(), userId, groupId, uri,
            name, type, Instant.now().toString())
        state.update { it + task }
        persist(state.value)
        deliver(task)
    }

    @Synchronized
    fun retry(id: String) {
        val task = state.value.firstOrNull { it.id == id && it.userId == currentUserId() && it.phase == AttachmentPhase.Failed } ?: return
        val next = task.copy(phase = AttachmentPhase.Uploading, progress = 0f, error = null)
        update(next)
        deliver(next)
    }

    @Synchronized
    fun dismiss(id: String) {
        state.update { list -> list.filterNot { it.id == id && it.userId == currentUserId() && it.phase == AttachmentPhase.Failed } }
        persist(state.value)
    }

    @Synchronized
    fun acknowledge(fileIds: Set<String>) {
        state.update { list -> list.filterNot { it.userId == currentUserId() && it.fileId in fileIds } }
        persist(state.value)
    }

    @Synchronized
    private fun update(task: AttachmentUpload, save: Boolean = true) {
        state.update { list -> list.map { if (it.id == task.id) task else it } }
        if (save) persist(state.value)
    }

    private fun deliver(task: AttachmentUpload) {
        scope.launch {
            try {
                transferSlots.withPermit {
                    if (currentUserId() != task.userId) throw DataError.Unauthenticated()
                    val bytes = readBytes(task.uri)
                    if (currentUserId() != task.userId) throw DataError.Unauthenticated()
                    files.uploadFile(task.groupId, task.fileName, task.mimeType, bytes).collect { result ->
                        when (result) {
                            is UploadState.InProgress -> update(task.copy(progress = result.fraction), save = false)
                            is UploadState.Failed -> update(task.copy(phase = AttachmentPhase.Failed,
                                error = result.error.toUserMessage().body))
                            is UploadState.Completed -> {
                                update(task.copy(phase = AttachmentPhase.Completed, progress = 1f, fileId = result.file.id))
                                onCompleted()
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                update(task.copy(phase = AttachmentPhase.Failed, error = "Upload interrupted. Tap Retry."))
                throw error
            } catch (error: Exception) {
                update(task.copy(phase = AttachmentPhase.Failed, error = error.toUserMessage().body))
            }
        }
    }
}
