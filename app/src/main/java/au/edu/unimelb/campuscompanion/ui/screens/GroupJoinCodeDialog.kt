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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.ui.model.StartedGroupAccess
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

@Composable
internal fun GroupJoinCodeDialog(access: StartedGroupAccess, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.groups_join_code)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(access.groupName)
                GroupJoinCodeContent(access)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.course_reminder_settings_done))
            }
        }
    )
}

/** Used both after creation and when reopening settings, with the same server expiry. */
@Composable
internal fun GroupJoinCodeContent(access: StartedGroupAccess) {
    val now = rememberJoinCodeTime(access)
    val clipboard = LocalClipboardManager.current
    val code = access.activeJoinCode(now)
    if (code == null) {
        Text(
            stringResource(R.string.groups_the_6_character_code_is_unavailable_or_expired),
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
                    Text(stringResource(R.string.groups_join_code), style = MaterialTheme.typography.labelMedium)
                    Text(code, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                }
                IconButton(onClick = {
                    // A tap at the expiry boundary must not copy an already expired code.
                    access.activeJoinCode(Instant.now())?.let { clipboard.setText(AnnotatedString(it)) }
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.groups_copy_join_code))
                }
            }
        }
        val remaining = ((Duration.between(now, access.expiresAt).toMillis() + 999) / 1_000).coerceAtLeast(0)
        Text(stringResource(
            R.string.groups_code_expires_in_valid_only_during_the_first,
            remaining / 60,
            (remaining % 60).toString().padStart(2, '0')
        ))
    }
}

/** Settings and code content both update against the original creation deadline. */
@Composable
internal fun rememberJoinCodeTime(access: StartedGroupAccess): Instant {
    var now by remember(access) { mutableStateOf(Instant.now()) }
    LaunchedEffect(access) {
        do {
            now = Instant.now()
            if (access.activeJoinCode(now) == null) break
            delay(Duration.between(now, access.expiresAt).toMillis().coerceIn(1, 1_000))
        } while (true)
    }
    return now
}
