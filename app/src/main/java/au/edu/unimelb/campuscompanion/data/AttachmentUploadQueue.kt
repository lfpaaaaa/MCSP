package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.data.model.UploadState
import au.edu.unimelb.campuscompanion.data.repository.FileRepository
import au.edu.unimelb.campuscompanion.data.repository.FileUploadIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val error: String? = null,
    val allowDownloads: Boolean = true,
    val uploadId: String = id,
    val storagePath: String = "$groupId/$userId/$uploadId",
    val localUri: String? = null,
    val durationMillis: Long? = null,
    val isVoiceRecording: Boolean = false
)

/** App-owned delivery; interrupted uploads survive restart as retryable items. */
class AttachmentUploadQueue(
    private val files: FileRepository,
    private val scope: CoroutineScope,
    private val currentUserId: () -> String?,
    private val readBytes: suspend (String) -> ByteArray,
    restored: List<AttachmentUpload> = emptyList(),
    private val persist: (List<AttachmentUpload>) -> Unit = {},
    private val onCompleted: suspend () -> Unit = {},
    private val preserveLocally: suspend (AttachmentUpload) -> String = { it.uri },
    private val discardLocal: (AttachmentUpload) -> Unit = {}
) {
    private val state = MutableStateFlow(restored.map {
        if (it.phase == AttachmentPhase.Uploading) it.copy(phase = AttachmentPhase.Failed,
            error = "Upload interrupted. Tap Retry.") else it
    })
    val uploads = state.asStateFlow()
    private val transferSlots = Semaphore(1) // Bound memory: existing storage API takes a byte array.

    @Synchronized
    fun enqueue(
        groupId: String,
        uri: String,
        name: String,
        type: String,
        allowDownloads: Boolean = true,
        durationMillis: Long? = null,
        isVoiceRecording: Boolean = false,
        uploadId: String? = null,
        expectedUserId: String? = null
    ) {
        val userId = currentUserId() ?: throw DataError.Unauthenticated()
        // Auth can switch before the old screen recomposes. Never assign its private draft
        // to the new account; check ownership before persistence or starting any byte read.
        if (expectedUserId != null && expectedUserId != userId) throw DataError.Unauthenticated()
        val id = uploadId ?: UUID.randomUUID().toString()
        if (id.isBlank() || id.contains('/')) throw DataError.Validation("Invalid upload identifier.")
        state.value.firstOrNull { it.id == id || it.uploadId == id }?.let { existing ->
            if (existing.userId != userId || existing.groupId != groupId || existing.uri != uri ||
                existing.fileName != name || existing.mimeType != type || existing.allowDownloads != allowDownloads ||
                existing.durationMillis != durationMillis || existing.isVoiceRecording != isVoiceRecording) {
                throw DataError.Validation("This recording no longer matches its queued upload.")
            }
            // The staging record can survive a restart between enqueue and clearing the composer.
            // Retain the same queue item, including a completed/retryable prior delivery.
            return
        }
        val task = AttachmentUpload(id, userId, groupId, uri,
            name, type, Instant.now().toString(), allowDownloads = allowDownloads,
            durationMillis = durationMillis, isVoiceRecording = isVoiceRecording)
        publish(state.value + task)
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
        val discarded = state.value.filter { it.id == id && it.userId == currentUserId() && it.phase == AttachmentPhase.Failed }
        if (discarded.isEmpty()) return
        publish(state.value - discarded.toSet())
        discarded.forEach(discardLocal)
    }

    @Synchronized
    fun acknowledge(fileIds: Set<String>) {
        // Observing the stable metadata id is also confirmation when finalization's reply was lost.
        val completed = state.value.filter { it.userId == currentUserId() && (it.fileId in fileIds || it.uploadId in fileIds) }
        if (completed.isEmpty()) return
        publish(state.value - completed.toSet())
        completed.forEach(discardLocal)
    }

    @Synchronized
    private fun update(task: AttachmentUpload, save: Boolean = true): Boolean {
        if (state.value.none { it.id == task.id }) return false
        publish(state.value.map { if (it.id == task.id) task else it }, save)
        return true
    }

    /** Called while holding this queue's monitor; no network starts before a durable write. */
    private fun publish(next: List<AttachmentUpload>, save: Boolean = true) {
        if (save) persist(next)
        state.value = next
    }

    @Synchronized
    private fun isTracked(id: String): Boolean = state.value.any { it.id == id }

    /**
     * Keep an asynchronous result usable when the device cannot save it. The previous durable
     * item still has its identity and original URI; restart restores it as retryable and the
     * deterministic draft path recovers its bytes. A confirmed server success stays completed.
     */
    private fun finishDelivery(task: AttachmentUpload) {
        try {
            update(task)
        } catch (_: Exception) {
            update(task, save = false)
        }
    }

    private fun deliver(task: AttachmentUpload) {
        scope.launch {
            var pending = task
            try {
                transferSlots.withPermit {
                    if (!isTracked(task.id)) return@withPermit
                    if (currentUserId() != task.userId) throw DataError.Unauthenticated()
                    if (pending.localUri == null) {
                        pending = pending.copy(localUri = preserveLocally(pending))
                        if (!update(pending)) {
                            // An observed durable file can acknowledge an old attempt while its
                            // local copy is still in progress. Remove the newly returned draft.
                            discardLocal(pending)
                            return@withPermit
                        }
                    }
                    val bytes = readBytes(pending.localUri!!)
                    if (!isTracked(task.id)) return@withPermit
                    if (currentUserId() != task.userId) throw DataError.Unauthenticated()
                    files.uploadFile(task.groupId, task.fileName, task.mimeType, bytes, task.allowDownloads,
                        FileUploadIdentity(task.uploadId, task.storagePath, task.durationMillis, task.isVoiceRecording)).collect { result ->
                        when (result) {
                            is UploadState.InProgress -> update(pending.copy(progress = result.fraction), save = false)
                            is UploadState.Failed -> {
                                pending = pending.copy(phase = AttachmentPhase.Failed, error = result.error.toUserMessage().body)
                                finishDelivery(pending)
                            }
                            is UploadState.Completed -> {
                                pending = pending.copy(phase = AttachmentPhase.Completed, progress = 1f, fileId = result.file.id)
                                finishDelivery(pending)
                                // A failed summary refresh must not turn a durable delivery into
                                // a failed upload or make Send retry it.
                                try {
                                    onCompleted()
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: Exception) { }
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                if (pending.phase != AttachmentPhase.Completed) {
                    finishDelivery(pending.copy(phase = AttachmentPhase.Failed, error = "Upload interrupted. Tap Retry."))
                }
                throw error
            } catch (error: Exception) {
                if (pending.phase != AttachmentPhase.Completed) {
                    finishDelivery(pending.copy(phase = AttachmentPhase.Failed, error = error.toUserMessage().body))
                }
            }
        }
    }
}
