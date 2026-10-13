package au.edu.unimelb.campuscompanion.profile

import au.edu.unimelb.campuscompanion.ui.text.UiText
import au.edu.unimelb.campuscompanion.R
import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import au.edu.unimelb.campuscompanion.auth.SupabaseProvider
import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.ui.chat.readUpTo
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

object AvatarImages {
    private const val MAX_SOURCE_BYTES = 20 * 1024 * 1024

    suspend fun readForUpload(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val source = localBytes(context, uri)
        val bitmap = decode(source) ?: throw DataError.Validation(UiText(R.string.validation_this_photo_could_not_be_read_choose_another))
        val square = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            val side = min(bitmap.width, bitmap.height)
            val left = (bitmap.width - side) / 2
            val top = (bitmap.height - side) / 2
            Canvas(square).apply {
                drawColor(Color.WHITE)
                drawBitmap(bitmap, Rect(left, top, left + side, top + side), Rect(0, 0, 512, 512), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            ByteArrayOutputStream().use { output ->
                square.compress(Bitmap.CompressFormat.JPEG, 88, output)
                output.toByteArray()
            }
        } finally { bitmap.recycle(); square.recycle() }
    }

    suspend fun load(context: Context, reference: String): Bitmap? = withContext(Dispatchers.IO) {
        val path = avatarPath(reference)
        val bytes = when {
            path != null -> SupabaseProvider.client?.storage?.from(AVATAR_BUCKET)?.downloadAuthenticated(path)
            reference.startsWith("content://") -> localBytes(context, Uri.parse(reference))
            reference.startsWith("https://") -> {
                val connection = URL(reference).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 8_000
                    connection.instanceFollowRedirects = false
                    if (connection.responseCode !in 200..299) null
                    else connection.inputStream.use { it.readUpTo(MAX_SOURCE_BYTES + 1) }
                } finally { connection.disconnect() }
            }
            else -> null
        }
        bytes?.takeIf { it.size <= MAX_SOURCE_BYTES }?.let(::decode)
    }

    private fun localBytes(context: Context, uri: Uri): ByteArray {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readUpTo(MAX_SOURCE_BYTES + 1) }
            ?: throw DataError.Validation(UiText(R.string.validation_the_selected_photo_is_unavailable_choose_it_again))
        if (bytes.isEmpty() || bytes.size > MAX_SOURCE_BYTES) throw DataError.Validation(UiText(R.string.validation_choose_an_image_smaller_than_20_mb))
        return bytes
    }

    internal fun decode(bytes: ByteArray): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = max(info.size.width, info.size.height) / 1024f
                if (scale > 1) decoder.setTargetSize((info.size.width / scale).toInt().coerceAtLeast(1), (info.size.height / scale).toInt().coerceAtLeast(1))
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f, 1f) }
                8 -> setRotate(-90f)
            }
        }
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }
}
