package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.ui.model.StartedGroupAccess
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

@Composable
internal fun GroupJoinCodeDialog(
    access: StartedGroupAccess,
    onDismiss: () -> Unit,
    onShareQr: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join code") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(access.groupName)
                GroupJoinCodeContent(access)
                onShareQr?.let { action ->
                    TextButton(onClick = action, modifier = Modifier.fillMaxWidth()) { Text("Share QR code") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/** Used after creation and when reopening settings, always with the original server expiry. */
@Composable
internal fun GroupJoinCodeContent(access: StartedGroupAccess) {
    var now by remember(access) { mutableStateOf(Instant.now()) }
    LaunchedEffect(access) {
        do {
            now = Instant.now()
            if (access.activeJoinCode(now) == null) break
            delay(1_000)
        } while (true)
    }
    val clipboard = LocalClipboardManager.current
    val code = access.activeJoinCode(now)
    if (code == null) {
        Text(
            "The 6-character code is unavailable or expired. Use Share QR code in group settings.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Join code", style = MaterialTheme.typography.labelMedium)
                    Text(code, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                }
                IconButton(onClick = {
                    // A delayed timer/backgrounded phone must not copy a code past its boundary.
                    now = Instant.now()
                    access.activeJoinCode(now)?.let { clipboard.setText(AnnotatedString(it)) }
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy join code")
                }
            }
        }
        val remaining = ((Duration.between(now, access.expiresAt).toMillis() + 999) / 1_000).coerceAtLeast(0)
        Text("Code expires in ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}. Valid only during the first 5 minutes after group creation.")
    }
}
