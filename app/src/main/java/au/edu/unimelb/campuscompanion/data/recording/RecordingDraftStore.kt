package au.edu.unimelb.campuscompanion.data.recording

import au.edu.unimelb.campuscompanion.data.repository.FileRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

const val MAX_RECORDING_DURATION_MILLIS = 600_000L
const val MIN_RECORDING_DURATION_MILLIS = 1_000L
internal const val MAX_RECORDING_CODEC_PADDING_MILLIS = 100L

@Serializable
enum class RecordingDraftPhase { Review, Staged, Queued }

/** Only a finalized, validated AAC recording can become a draft. */
@Serializable
data class RecordingDraft(
    val id: String,
    val userId: String,
    val groupId: String,
    val localPath: String,
    val fileName: String,
    val durationMillis: Long,
    val phase: RecordingDraftPhase = RecordingDraftPhase.Review,
    val allowDownloads: Boolean = true
)

data class RecordingLoadResult(val draft: RecordingDraft? = null, val warning: String? = null)
data class QueuedRecordingReference(val userId: String, val groupId: String, val recordingId: String)

internal data class RecordingCapture(
    val id: String,
    val userId: String,
    val groupId: String,
    val fileName: String,
    val file: File
)

/**
 * One active review/composer draft per account and group. Queued clips retain their own metadata
 * and immutable bytes until the upload queue confirms delivery or explicitly discards them.
 * [audioDuration] must inspect the real audio container, returning null for an unusable file.
 */
class RecordingDraftStore(
    private val directory: File,
    private val audioDuration: (File) -> Long?
) {
    private var deleteFile: (File) -> Boolean = File::delete

    internal constructor(directory: File, audioDuration: (File) -> Long?, deleteFile: (File) -> Boolean) :
        this(directory, audioDuration) {
        this.deleteFile = deleteFile
    }

    @Serializable
    private enum class Phase { Capturing, Review, Staged, Queued }

    @Serializable
    private data class Entry(
        val id: String,
        val userId: String,
        val groupId: String,
        val localPath: String,
        val fileName: String,
        val durationMillis: Long = 0,
        val phase: Phase = Phase.Capturing,
        val allowDownloads: Boolean = true
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun load(userId: String, groupId: String): RecordingLoadResult {
        val folder = folder(userId, groupId)
        val pointer = File(folder, ACTIVE_FILE)
        if (!pointer.isFile) return RecordingLoadResult()
        if (pointer.canonicalFile.parentFile != folder) {
            pointer.delete()
            return RecordingLoadResult(warning = "The saved recording could not be restored. Record again.")
        }
        val id = runCatching { pointer.readText().trim().also(::requireId) }.getOrNull()
        if (id == null) {
            pointer.delete()
            return RecordingLoadResult(warning = "The saved recording could not be restored. Record again.")
        }
        val entry = readEntry(folder, id)
        if (isClaimed(userId, groupId, id)) {
            runCatching {
                if (entry != null && isOwned(entry, userId, groupId) && entry.phase != Phase.Capturing) {
                    writeEntry(folder, entry.copy(phase = Phase.Queued))
                }
                clearPointer(folder, id)
            }
            return RecordingLoadResult()
        }
        if (entry == null || !isOwned(entry, userId, groupId)) {
            removeOwned(folder, id)
            return RecordingLoadResult(warning = "The saved recording could not be restored. Record again.")
        }
        if (entry.phase == Phase.Queued) {
            // A crash between queue handoff and clearing the active pointer must not stage/send twice.
            runCatching {
                if (entry.phase != Phase.Queued) writeEntry(folder, entry.copy(phase = Phase.Queued))
                clearPointer(folder, id)
            }
            return RecordingLoadResult()
        }
        return try {
            if (entry.phase == Phase.Capturing) {
                val capture = capture(entry)
                val restored = finishCapture(capture)
                RecordingLoadResult(restored, "Recording was interrupted. Review the recovered clip before using it.")
            } else {
                val file = File(entry.localPath)
                val duration = validate(file)
                if (duration != entry.durationMillis) throw IllegalStateException("Recording duration changed")
                RecordingLoadResult(entry.toDraft())
            }
        } catch (_: Exception) {
            removeOwned(folder, id)
            RecordingLoadResult(warning = "The interrupted or damaged recording could not be recovered. Record again.")
        }
    }

    @Synchronized
    internal fun beginCapture(userId: String, groupId: String, id: String, fileName: String): RecordingCapture {
        requireId(id)
        require(fileName.isNotBlank())
        val folder = folder(userId, groupId)
        if (!folder.isDirectory && !folder.mkdirs()) throw IllegalStateException("Recording storage is unavailable")
        if (File(folder, ACTIVE_FILE).exists()) throw IllegalStateException("A recording draft is already active")
        if (recordFile(folder, id).exists() || finalFile(folder, id).exists() || captureFile(folder, id).exists()) {
            throw IllegalStateException("Recording identity is already in use")
        }
        val entry = Entry(id, userId, groupId, finalFile(folder, id).canonicalPath, fileName)
        writeEntry(folder, entry)
        writeAtomically(File(folder, ACTIVE_FILE), id)
        return capture(entry)
    }

    @Synchronized
    internal fun finishCapture(capture: RecordingCapture): RecordingDraft {
        val folder = folder(capture.userId, capture.groupId)
        val entry = readEntry(folder, capture.id) ?: throw IllegalStateException("Recording draft is missing")
        require(isOwned(entry, capture.userId, capture.groupId))
        require(capture.file.canonicalFile.parentFile == folder)
        require(capture.file.canonicalFile == captureFile(folder, capture.id).canonicalFile)
        if (entry.phase != Phase.Capturing) {
            require(entry.phase != Phase.Queued)
            validate(File(entry.localPath))
            return entry.toDraft()
        }
        val target = finalFile(folder, capture.id)
        // A process may have stopped after the atomic rename but before the metadata write.
        val source = if (capture.file.isFile) capture.file else target
        val duration = validate(source)
        FileOutputStream(source, true).use { it.fd.sync() }
        if (source != target) moveAtomically(source, target)
        val ready = entry.copy(durationMillis = duration, phase = Phase.Review)
        writeEntry(folder, ready)
        return ready.toDraft()
    }

    @Synchronized
    internal fun discardCapture(capture: RecordingCapture): Boolean {
        if (isClaimed(capture.userId, capture.groupId, capture.id)) return false
        val folder = folder(capture.userId, capture.groupId)
        val entry = readEntry(folder, capture.id) ?: return false
        if (!isOwned(entry, capture.userId, capture.groupId) || entry.phase == Phase.Queued) return false
        return removeOwned(folder, capture.id)
    }

    /** Saves Use recording before the composer displays it; this performs no upload. */
    @Synchronized
    fun stage(draft: RecordingDraft): RecordingDraft = changePhase(draft, Phase.Staged)

    /** Keeps staged download consent through ordinary recreation and process restart. */
    @Synchronized
    fun setDownloadAllowed(draft: RecordingDraft, allowed: Boolean): RecordingDraft {
        require(!isClaimed(draft.userId, draft.groupId, draft.id)) { "This recording belongs to the upload queue" }
        val folder = folder(draft.userId, draft.groupId)
        val entry = readEntry(folder, draft.id) ?: throw IllegalStateException("Recording draft is missing")
        require(matches(entry, draft))
        require(entry.phase == Phase.Review || entry.phase == Phase.Staged)
        require(validate(File(entry.localPath)) == entry.durationMillis)
        val changed = entry.copy(allowDownloads = allowed)
        writeEntry(folder, changed)
        return changed.toDraft()
    }

    /** Call only after enqueue persisted the same UUID. The queue continues to own these bytes. */
    @Synchronized
    fun markQueued(draft: RecordingDraft): RecordingDraft {
        claimQueued(draft.userId, draft.groupId, draft.id)
        val queued = changePhase(draft, Phase.Queued)
        clearPointer(folder(draft.userId, draft.groupId), draft.id)
        return queued
    }

    /** Claim immediately after durable enqueue, even when a subsequent metadata write fails. */
    @Synchronized
    fun claimQueued(userId: String, groupId: String, recordingId: String) {
        requireId(recordingId)
        folder(userId, groupId)
        claimedQueued += claimKey(userId, groupId, recordingId)
    }

    /**
     * Run once at queue restoration before offering draft controls. Restored queue ownership
     * protects a Staged clip after interrupted handoff; absent Queued records are cleanup after
     * acknowledgement committed but source removal did not finish.
     */
    @Synchronized
    fun reconcileQueued(records: List<QueuedRecordingReference>) {
        val valid = records.filter { reference ->
            runCatching { requireId(reference.recordingId); folder(reference.userId, reference.groupId) }.isSuccess
        }
        val keys = valid.mapTo(mutableSetOf()) { claimKey(it.userId, it.groupId, it.recordingId) }
        val prefix = directory.canonicalPath + '\u0000'
        claimedQueued.removeIf { it.startsWith(prefix) && it !in keys }
        claimedQueued.addAll(keys)
        val root = directory.canonicalFile
        val folders = root.listFiles().orEmpty().map { it.canonicalFile }
            .filter { it.isDirectory && it.parentFile == root }
        folders.forEach { candidate ->
            candidate.listFiles().orEmpty().filter { it.extension == "json" }.forEach entryLoop@ { metadata ->
                val id = metadata.nameWithoutExtension
                val entry = readEntry(candidate, id) ?: return@entryLoop
                if (!isOwned(entry, entry.userId, entry.groupId) || folder(entry.userId, entry.groupId) != candidate.canonicalFile) return@entryLoop
                if (claimKey(entry.userId, entry.groupId, entry.id) in keys && entry.phase != Phase.Capturing) {
                    runCatching {
                        writeEntry(candidate, entry.copy(phase = Phase.Queued))
                        clearPointer(candidate, entry.id)
                    }
                } else if (entry.phase == Phase.Queued) {
                    runCatching { removeOwned(candidate, entry.id) }
                }
            }
        }
    }

    /** User cancellation cannot delete a draft that has already been handed to the upload queue. */
    @Synchronized
    fun discard(draft: RecordingDraft): Boolean {
        if (isClaimed(draft.userId, draft.groupId, draft.id)) return false
        val folder = folder(draft.userId, draft.groupId)
        val entry = readEntry(folder, draft.id) ?: return false
        if (!isOwned(entry, draft.userId, draft.groupId) || entry.id != draft.id ||
            entry.localPath != draft.localPath || entry.phase == Phase.Queued) return false
        return removeOwned(folder, draft.id)
    }

    /**
     * Called by durable queue acknowledgement/explicit failed-task dismissal. It can finish an
     * interrupted handoff, and never clears the pointer or bytes of a newer active recording.
     */
    @Synchronized
    fun discardQueued(userId: String, groupId: String, recordingId: String): Boolean {
        requireId(recordingId)
        val folder = folder(userId, groupId)
        val entry = readEntry(folder, recordingId) ?: return false
        if (!isOwned(entry, userId, groupId) || entry.phase == Phase.Capturing) return false
        return removeOwned(folder, recordingId).also { claimedQueued.remove(claimKey(userId, groupId, recordingId)) }
    }

    private fun changePhase(draft: RecordingDraft, phase: Phase): RecordingDraft {
        require(phase == Phase.Queued || !isClaimed(draft.userId, draft.groupId, draft.id)) {
            "This recording belongs to the upload queue"
        }
        val folder = folder(draft.userId, draft.groupId)
        val entry = readEntry(folder, draft.id) ?: throw IllegalStateException("Recording draft is missing")
        require(matches(entry, draft))
        require(entry.phase != Phase.Capturing)
        if (entry.phase == Phase.Queued && phase != Phase.Queued) {
            throw IllegalStateException("This recording already belongs to the upload queue")
        }
        val duration = validate(File(entry.localPath))
        require(duration == entry.durationMillis)
        val changed = entry.copy(phase = phase)
        writeEntry(folder, changed)
        return changed.toDraft()
    }

    private fun validate(file: File): Long {
        if (!file.isFile || file.length() !in 1..FileRepository.MAX_FILE_SIZE_BYTES) {
            throw IllegalStateException("Recording is empty or too large")
        }
        val duration = audioDuration(file) ?: throw IllegalStateException("Recording is not playable")
        if (duration !in MIN_RECORDING_DURATION_MILLIS..MAX_RECORDING_DURATION_MILLIS + MAX_RECORDING_CODEC_PADDING_MILLIS) {
            throw IllegalStateException("Recording is too short or exceeds ten minutes")
        }
        // AAC frame/container rounding can add a fraction of a second after the native cap.
        return duration.coerceAtMost(MAX_RECORDING_DURATION_MILLIS)
    }

    private fun Entry.toDraft() = RecordingDraft(id, userId, groupId, localPath, fileName,
        durationMillis, when (phase) {
            Phase.Review -> RecordingDraftPhase.Review
            Phase.Staged -> RecordingDraftPhase.Staged
            Phase.Queued -> RecordingDraftPhase.Queued
            Phase.Capturing -> error("An unfinished capture is not a recording draft")
        }, allowDownloads)

    private fun capture(entry: Entry) = RecordingCapture(entry.id, entry.userId, entry.groupId,
        entry.fileName, captureFile(folder(entry.userId, entry.groupId), entry.id))

    private fun matches(entry: Entry, draft: RecordingDraft) = isOwned(entry, draft.userId, draft.groupId) &&
        entry.id == draft.id && entry.localPath == draft.localPath && entry.fileName == draft.fileName &&
        entry.durationMillis == draft.durationMillis && entry.allowDownloads == draft.allowDownloads

    private fun isOwned(entry: Entry, userId: String, groupId: String): Boolean = runCatching {
        requireId(entry.id)
        entry.userId == userId && entry.groupId == groupId &&
            finalFile(folder(userId, groupId), entry.id).canonicalFile.parentFile == folder(userId, groupId) &&
            File(entry.localPath).canonicalFile == finalFile(folder(userId, groupId), entry.id).canonicalFile
    }.getOrDefault(false)

    private fun folder(userId: String, groupId: String): File {
        require(userId.isNotBlank() && groupId.isNotBlank() && '\u0000' !in userId && '\u0000' !in groupId)
        val digest = MessageDigest.getInstance("SHA-256").digest("$userId\u0000$groupId".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val root = directory.canonicalFile
        return File(root, digest).canonicalFile.also { require(it.parentFile == root) }
    }

    private fun readEntry(folder: File, id: String): Entry? = runCatching {
        requireId(id)
        require(recordFile(folder, id).canonicalFile.parentFile == folder)
        json.decodeFromString(Entry.serializer(), recordFile(folder, id).readText())
    }.getOrNull()

    private fun writeEntry(folder: File, entry: Entry) =
        writeAtomically(recordFile(folder, entry.id), json.encodeToString(Entry.serializer(), entry))

    private fun removeOwned(folder: File, id: String): Boolean {
        requireId(id)
        val removed = recordFile(folder, id).exists()
        // Delete bytes first. A failed removal must keep metadata/pointer usable for Retry.
        deleteOwnedFile(finalFile(folder, id))
        deleteOwnedFile(captureFile(folder, id))
        deleteOwnedFile(recordFile(folder, id))
        clearPointer(folder, id)
        return removed
    }

    private fun clearPointer(folder: File, id: String) {
        val pointer = File(folder, ACTIVE_FILE)
        if (pointer.canonicalFile.parentFile != folder) { deleteOwnedFile(pointer); return }
        if (runCatching { pointer.readText().trim() }.getOrNull() == id) deleteOwnedFile(pointer)
    }

    private fun deleteOwnedFile(file: File) {
        if ((file.exists() || Files.isSymbolicLink(file.toPath())) && !deleteFile(file)) {
            throw IOException("The recording draft could not be removed")
        }
    }

    private fun finalFile(folder: File, id: String) = File(folder, "$id.m4a")
    private fun captureFile(folder: File, id: String) = File(folder, "$id.capturing.m4a")
    private fun recordFile(folder: File, id: String) = File(folder, "$id.json")

    private fun writeAtomically(target: File, text: String) {
        val partial = File.createTempFile("recording-", ".part", target.parentFile)
        try {
            FileOutputStream(partial).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            moveAtomically(partial, target)
        } finally { partial.delete() }
    }

    private fun moveAtomically(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    }

    private fun requireId(id: String) { require(id.matches(Regex("[A-Za-z0-9-]+"))) }

    private fun claimKey(userId: String, groupId: String, id: String) =
        directory.canonicalPath + '\u0000' + userId + '\u0000' + groupId + '\u0000' + id

    private fun isClaimed(userId: String, groupId: String, id: String) = claimKey(userId, groupId, id) in claimedQueued

    private companion object {
        const val ACTIVE_FILE = "active-recording"
        val claimedQueued = ConcurrentHashMap.newKeySet<String>()
    }
}
