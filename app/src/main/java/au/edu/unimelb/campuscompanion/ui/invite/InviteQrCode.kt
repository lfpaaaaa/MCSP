package au.edu.unimelb.campuscompanion.ui.invite

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Encodes an invite link as a QR code; the screen turns the matrix into pixels. */
object InviteQrCode {
    const val DEFAULT_SIZE = 512

    fun matrix(contents: String, size: Int = DEFAULT_SIZE): BitMatrix {
        require(contents.isNotBlank()) { "Nothing to encode" }
        require(size > 0) { "Size must be positive" }
        // No character-set hint: the link is ASCII, and leaving out the ECI header keeps the
        // code readable by the simplest scanners.
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES
        )
        return QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, size, size, hints)
    }

    private const val QUIET_ZONE_MODULES = 1
}
