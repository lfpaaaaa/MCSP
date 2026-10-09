package au.edu.unimelb.campuscompanion.ui.chat

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import au.edu.unimelb.campuscompanion.data.repository.FileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads shared photos for the timeline through their short-lived download links, scaled down to
 * screen size and kept in memory while the chat is open. Concurrent requests for the same photo
 * share one download.
 */
class ChatImageLoader(
    private val files: FileRepository,
    private val scope: CoroutineScope,
    maxEntries: Int = 32
) {
    private val cache = LruCache<String, ImageBitmap>(maxEntries)
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

    /** The photo stored as [fileId], or null when it cannot be fetched or decoded. */
    suspend fun load(fileId: String): ImageBitmap? {
        cache.get(fileId)?.let { return it }
        val load = inFlight.computeIfAbsent(fileId) {
            scope.async(Dispatchers.IO) {
                try {
                    fetch(fileId)?.also { image -> cache.put(fileId, image) }
                } finally {
                    inFlight.remove(fileId)
                }
            }
        }
        return load.await()
    }

    private suspend fun fetch(fileId: String): ImageBitmap? {
        val url = files.createDownloadUrl(fileId).getOrNull() ?: return null
        val bytes = download(url) ?: return null
        return decode(bytes)
    }

    private fun download(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { it.readUpTo(MAX_DOWNLOAD_BYTES) }
        } catch (error: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_DIMENSION || bounds.outHeight / sampleSize > MAX_DIMENSION) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    private companion object {
        const val MAX_DIMENSION = 1024
        const val MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
    }
}
