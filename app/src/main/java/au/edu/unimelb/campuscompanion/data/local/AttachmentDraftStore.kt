package au.edu.unimelb.campuscompanion.data.local

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.readAttachmentBytes
import au.edu.unimelb.campuscompanion.data.repository.FileRepository
import java.io.File
import java.io.FileOutputStream

/** Immutable app-owned bytes for queued uploads, outside the evictable Android cache. */
class AttachmentDraftStore(private val directory: File) {
    suspend fun preserve(uploadId: String, readOriginal: suspend () -> ByteArray): File {
        val target = file(uploadId)
        // The app may have stopped after the rename but before it persisted the local URI.
        if (target.isFile) return target
        if (!directory.isDirectory && !directory.mkdirs()) throw DataError.Unexpected()
        val partial = File.createTempFile("$uploadId-", ".part", directory)
        try {
            val bytes = readOriginal()
            if (bytes.isEmpty() || bytes.size > FileRepository.MAX_FILE_SIZE_BYTES) {
                throw DataError.Validation("Files must be between 1 byte and 20 MB.")
            }
            FileOutputStream(partial).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!partial.renameTo(target)) throw DataError.Unexpected()
            return target
        } finally {
            partial.delete()
        }
    }

    fun read(uploadId: String): ByteArray = file(uploadId).inputStream().use { it.readAttachmentBytes() }

    fun discard(uploadId: String) {
        file(uploadId).delete()
    }

    private fun file(uploadId: String): File {
        require(uploadId.matches(Regex("[A-Za-z0-9-]+")))
        return File(directory, uploadId)
    }
}
