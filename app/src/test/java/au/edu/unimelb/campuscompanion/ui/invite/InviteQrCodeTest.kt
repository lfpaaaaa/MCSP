package au.edu.unimelb.campuscompanion.ui.invite

import au.edu.unimelb.campuscompanion.data.model.GroupInvite
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InviteQrCodeTest {
    @Test
    fun theCodeDecodesBackToTheJoinLink() {
        val link = "campuscompanion://join?token=abc123DEF456-ghi"

        val matrix = InviteQrCode.matrix(link, size = 256)

        assertEquals(256, matrix.width)
        assertEquals(256, matrix.height)
        assertEquals(link, decode(matrix))
        assertEquals("abc123DEF456-ghi", GroupInvite.tokenFromUri(decode(matrix)))
    }

    @Test
    fun emptyContentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { InviteQrCode.matrix("   ") }
    }

    private fun decode(matrix: BitMatrix): String {
        val source = object : LuminanceSource(matrix.width, matrix.height) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray {
                val target = row?.takeIf { it.size >= width } ?: ByteArray(width)
                for (x in 0 until width) target[x] = luminance(x, y)
                return target
            }

            override fun getMatrix(): ByteArray {
                val pixels = ByteArray(width * height)
                for (y in 0 until height) for (x in 0 until width) pixels[y * width + x] = luminance(x, y)
                return pixels
            }

            private fun luminance(x: Int, y: Int): Byte = if (matrix.get(x, y)) 0 else 0xFF.toByte()
        }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }
}
