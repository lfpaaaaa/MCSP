package au.edu.unimelb.campuscompanion.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.ui.invite.InviteQrCode
import au.edu.unimelb.campuscompanion.ui.model.QrShareUiState
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/** Shows a group's invitation as a QR code for other members to scan. */
@Composable
fun InviteQrDialog(
    state: QrShareUiState,
    onDismiss: () -> Unit,
    onNewCode: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (state) {
                    QrShareUiState.Idle -> ""
                    QrShareUiState.CreatingInvite -> "Creating an invitation"
                    is QrShareUiState.Ready -> "Invite to ${state.groupName}"
                    is QrShareUiState.Failed -> state.title
                }
            )
        },
        text = {
            when (state) {
                QrShareUiState.Idle -> Unit
                QrShareUiState.CreatingInvite -> Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
                is QrShareUiState.Ready -> ReadyContent(state)
                is QrShareUiState.Failed -> Text(state.message)
            }
        },
        confirmButton = {
            when (state) {
                is QrShareUiState.Ready -> TextButton(onClick = onNewCode) { Text("New code") }
                is QrShareUiState.Failed -> TextButton(onClick = onNewCode) { Text("Try again") }
                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun ReadyContent(state: QrShareUiState.Ready) {
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = state.joinUri) {
        value = withContext(Dispatchers.Default) { InviteQrCode.matrix(state.joinUri).toImageBitmap() }
    }
    var minutesLeft by remember(state.expiresAt) { mutableLongStateOf(minutesUntil(state.expiresAt)) }
    LaunchedEffect(state.expiresAt) {
        while (true) {
            delay(15_000)
            minutesLeft = minutesUntil(state.expiresAt)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Invitation QR code",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentScale = ContentScale.Fit
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }
        Text(
            text = "Members scan this with Scan QR on their Groups page, or with the phone's camera.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = if (minutesLeft > 0) {
                "Expires in $minutesLeft min. Each invitation works for a limited number of joins."
            } else {
                "This invitation has expired. Create a new code."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun minutesUntil(instant: Instant): Long =
    Duration.between(Instant.now(), instant).toMinutes().coerceAtLeast(0)

/** Black modules on white, one pixel per matrix cell; the matrix already carries the quiet zone. */
private fun BitMatrix.toImageBitmap(): ImageBitmap {
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            pixels[y * width + x] = if (get(x, y)) Color.BLACK else Color.WHITE
        }
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap.asImageBitmap()
}
