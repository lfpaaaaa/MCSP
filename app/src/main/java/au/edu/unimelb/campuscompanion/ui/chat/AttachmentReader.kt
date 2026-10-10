package au.edu.unimelb.campuscompanion.ui.chat

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.repository.FileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.roundToInt

/** The contents of a file chosen on the device, ready to be sent to the group. */
class AttachmentPayload(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
)

/** Reads files the user picked, or photographed, into memory within the upload size limit. */
object AttachmentReader {
    private const val MAX_BYTES = FileRepository.MAX_FILE_SIZE_BYTES
    private const val MAX_PHOTO_DIMENSION = 1600
    private const val PHOTO_JPEG_QUALITY = 85
    private const val DEFAULT_MIME_TYPE = "application/octet-stream"

    /** Reads the document or media item at [uri]; [fallbackName] is used when it has no name. */
    suspend fun read(context: Context, uri: Uri, fallbackName: String): Result<AttachmentPayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val declaredSize = querySize(resolver, uri)
                if (declaredSize != null && declaredSize > MAX_BYTES) throw tooLarge()
                val bytes = resolver.openInputStream(uri)?.use { it.readUpTo(MAX_BYTES.toInt() + 1) }
                    ?: throw DataError.NotFound()
                if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw tooLarge()
                val name = queryDisplayName(resolver, uri) ?: fallbackName
                AttachmentPayload(name, mimeType(resolver, uri, name), bytes)
            }
        }

    /** Loads a photo taken with the camera, scaled down for sending, as a JPEG called [fileName]. */
    suspend fun readCameraPhoto(context: Context, uri: Uri, fileName: String): Result<AttachmentPayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val imageFile = cameraFile(context, uri) ?: throw DataError.NotFound()
                val bitmap = decodeScaled(imageFile)
                    ?: throw DataError.Validation("The photo could not be read. Try taking it again.")
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, PHOTO_JPEG_QUALITY, output)
                bitmap.recycle()
                val bytes = output.toByteArray()
                if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw tooLarge()
                AttachmentPayload(fileName, "image/jpeg", bytes)
            }
        }

    private fun tooLarge() = DataError.Validation("Files must be 20 MB or smaller.")

    private fun querySize(resolver: ContentResolver, uri: Uri): Long? = queryColumn(resolver, uri, OpenableColumns.SIZE)
        ?.toLongOrNull()

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        queryColumn(resolver, uri, OpenableColumns.DISPLAY_NAME)?.takeIf(String::isNotBlank)

    private fun queryColumn(resolver: ContentResolver, uri: Uri, column: String): String? = runCatching {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(column)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun mimeType(resolver: ContentResolver, uri: Uri, name: String): String {
        resolver.getType(uri)?.takeIf { it.contains('/') }?.let { return it }
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: DEFAULT_MIME_TYPE
    }

    private fun cameraFile(context: Context, uri: Uri): File? {
        val fileName = uri.lastPathSegment ?: return null
        return File(context.cacheDir, "camera/$fileName").takeIf(File::isFile)
    }

    /** Decodes the photo at no more than [MAX_PHOTO_DIMENSION] pixels on its longer side. */
    private fun decodeScaled(imageFile: File): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(imageFile)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longerSide = maxOf(info.size.width, info.size.height)
                if (longerSide > MAX_PHOTO_DIMENSION) {
                    val scale = MAX_PHOTO_DIMENSION.toFloat() / longerSide
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt(),
                        (info.size.height * scale).roundToInt()
                    )
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > MAX_PHOTO_DIMENSION ||
            bounds.outHeight / sampleSize > MAX_PHOTO_DIMENSION
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeFile(imageFile.absolutePath, options)
    }
}

/** Reads at most [limit] bytes, so an oversized stream is noticed without filling memory. */
internal fun InputStream.readUpTo(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var remaining = limit
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) break
        output.write(buffer, 0, read)
        remaining -= read
    }
    return output.toByteArray()
}
