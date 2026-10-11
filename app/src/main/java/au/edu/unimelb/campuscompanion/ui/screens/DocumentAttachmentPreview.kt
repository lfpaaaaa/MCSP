package au.edu.unimelb.campuscompanion.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Renders one bounded page at a time without exposing the original to another app. */
@Composable
internal fun PdfAttachmentPreview(file: File, onError: (String) -> Unit) {
    var page by remember(file) { mutableIntStateOf(0) }
    var pages by remember(file) { mutableIntStateOf(0) }
    var image by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file, page) {
        image = null
        try {
            val rendered = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        renderer.openPage(page).use { pdfPage ->
                            val scale = minOf(1200f / pdfPage.width, 1600f / pdfPage.height)
                            val bitmap = Bitmap.createBitmap((pdfPage.width * scale).toInt().coerceAtLeast(1),
                                (pdfPage.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap.asImageBitmap() to renderer.pageCount
                        }
                    }
                }
            }
            image = rendered.first
            pages = rendered.second
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { onError("This PDF cannot be previewed.") }
    }
    Column {
        image?.let { Image(it, contentDescription = "Page ${page + 1}", modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)) }
        if (pages > 0) Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TextButton(enabled = page > 0, onClick = { page-- }) { Text("Previous") }
            Text("${page + 1} / $pages", modifier = Modifier.padding(vertical = 12.dp))
            TextButton(enabled = page + 1 < pages, onClick = { page++ }) { Text("Next") }
        }
    }
}

@Composable
internal fun TextAttachmentPreview(file: File, onError: (String) -> Unit) {
    var content by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        try {
            content = withContext(Dispatchers.IO) {
                file.bufferedReader().use { reader ->
                    val buffer = CharArray(16_385)
                    var length = 0
                    while (length < buffer.size) {
                        val count = reader.read(buffer, length, buffer.size - length)
                        if (count < 0) break
                        length += count
                    }
                    String(buffer, 0, minOf(length, 16_384)) + if (length > 16_384) "\n…Preview truncated" else ""
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { onError("This text file cannot be previewed.") }
    }
    content?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
