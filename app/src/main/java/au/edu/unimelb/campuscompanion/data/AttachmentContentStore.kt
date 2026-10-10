package au.edu.unimelb.campuscompanion.data

import android.content.Context
import android.net.Uri
import au.edu.unimelb.campuscompanion.data.model.SharedFile
import au.edu.unimelb.campuscompanion.data.repository.FileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Never trusts the picker/provider's reported size. */
fun InputStream.readAttachmentBytes(limit: Long = FileRepository.MAX_FILE_SIZE_BYTES): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count == -1) break
        if (output.size().toLong() + count > limit) throw DataError.Validation("Files must be between 1 byte and 20 MB.")
        output.write(buffer, 0, count)
    }
    if (output.size() == 0) throw DataError.Validation("This file is empty.")
    return output.toByteArray()
}

suspend fun readAttachment(context: Context, uri: String): ByteArray = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readAttachmentBytes() }
        ?: throw DataError.Validation("This attachment is no longer available. Select it again.")
}

/** A fresh signed URL re-checks permission every time, even if a downloaded copy exists. */
suspend fun downloadAttachment(context: Context, repository: FileRepository, file: SharedFile): File {
    val url = repository.createDownloadUrl(file.id).getOrThrow()
    return withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "group-downloads").apply { mkdirs() }
        val target = File(directory, file.id.replace(Regex("[^A-Za-z0-9-]"), "_"))
        if (target.isFile && target.length() == file.sizeBytes) return@withContext target
        val partial = File.createTempFile("attachment-", ".part", directory)
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode !in 200..299) throw DataError.Offline()
            var total = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        if (total > FileRepository.MAX_FILE_SIZE_BYTES || total > file.sizeBytes) {
                            throw DataError.Validation("The downloaded file is larger than expected.")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (total != file.sizeBytes) throw DataError.Offline()
            if (!partial.renameTo(target)) throw DataError.Unexpected()
            target
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }
}
