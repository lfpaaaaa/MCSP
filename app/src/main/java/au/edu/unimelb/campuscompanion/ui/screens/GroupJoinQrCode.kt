package au.edu.unimelb.campuscompanion.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

internal fun createGroupQrBitmap(code: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { index ->
        if (matrix[index % size, index / size]) 0xff000000.toInt() else 0xffffffff.toInt()
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
internal fun GroupJoinQrCode(code: String) {
    val bitmap = remember(code) { createGroupQrBitmap(code, 512).asImageBitmap() }
    Image(bitmap, contentDescription = "Group invitation QR code. Join code: $code",
        modifier = Modifier.size(192.dp))
}
