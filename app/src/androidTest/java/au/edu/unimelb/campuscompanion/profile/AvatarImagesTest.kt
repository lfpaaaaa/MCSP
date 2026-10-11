package au.edu.unimelb.campuscompanion.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AvatarImagesTest {
    @Test fun photoIsResizedToSquareJpegWithoutLocationMetadata() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("avatar-test-", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "37/1,48/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "S")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "144/1,57/1,36/1")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
                saveAttributes()
            }
            val bytes = AvatarImages.readForUpload(context, Uri.fromFile(source))
            assertTrue(bytes.size in 1..MAX_AVATAR_BYTES)
            assertEquals(0xff, bytes[0].toInt() and 0xff)
            assertEquals(0xd8, bytes[1].toInt() and 0xff)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(512, decoded.width)
            assertEquals(512, decoded.height)
            decoded.recycle()
            assertFalse(ExifInterface(bytes.inputStream()).getLatLong(FloatArray(2)))
        } finally { source.delete() }
    }
    @Test fun invalidImageFailsBeforeUpload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("avatar-invalid-", ".txt", context.cacheDir)
        try {
            source.writeText("This is not a photo")
            assertTrue(runCatching { AvatarImages.readForUpload(context, Uri.fromFile(source)) }.isFailure)
        } finally { source.delete() }
    }
}
