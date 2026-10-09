package au.edu.unimelb.campuscompanion.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.data.toUserMessage
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.GroupOrigin
import kotlinx.coroutines.launch

@Composable
fun GroupSettingsDialog(
    group: CourseGroup,
    initialFolded: Boolean,
    initialMuted: Boolean,
    initialDisplayName: String,
    onDismiss: () -> Unit,
    onInviteWithNfc: () -> Unit,
    onShowQrCode: () -> Unit,
    onReplaceJoinCode: suspend () -> Result<String>,
    onSave: (folded: Boolean, muted: Boolean, displayName: String) -> Unit
) {
    var folded by rememberSaveable(group.id) { mutableStateOf(initialFolded) }
    var muted by rememberSaveable(group.id) { mutableStateOf(initialMuted) }
    var displayName by rememberSaveable(group.id) { mutableStateOf(initialDisplayName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                group.joinCode?.let { joinCode ->
                    JoinCodeSection(
                        groupName = group.name,
                        joinCode = joinCode,
                        canReplace = group.origin == GroupOrigin.CreatedByUser,
                        onReplaceJoinCode = onReplaceJoinCode
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onShowQrCode,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.QrCode2,
                            contentDescription = null
                        )
                        Text(
                            text = "QR",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    OutlinedButton(
                        onClick = onInviteWithNfc,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Nfc,
                            contentDescription = null
                        )
                        Text(
                            text = "NFC",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                SettingSwitchRow(
                    title = "Fold this group",
                    description = if (group.origin == GroupOrigin.Timetable) {
                        "Timetable groups are folded automatically after the final class."
                    } else {
                        "Created and joined groups stay active until you fold them."
                    },
                    checked = folded,
                    onCheckedChange = { folded = it }
                )
                SettingSwitchRow(
                    title = "Mute notifications",
                    description = "Keep the chat available without notification alerts.",
                    checked = muted,
                    onCheckedChange = { muted = it }
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it.take(40) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Your name in this group") },
                    supportingText = { Text("${displayName.length}/40") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(folded, muted, displayName.trim())
                },
                enabled = displayName.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * The code other people type to join. Everyone can copy or share it; the person who started the
 * group can replace it, which stops the old code from working.
 */
@Composable
private fun JoinCodeSection(
    groupName: String,
    joinCode: String,
    canReplace: Boolean,
    onReplaceJoinCode: suspend () -> Result<String>
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var isReplacing by remember { mutableStateOf(false) }
    var replaceError by remember { mutableStateOf<String?>(null) }
    var showReplaceConfirmation by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Join code",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = joinCode,
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
                IconButton(onClick = { clipboard.setText(AnnotatedString(joinCode)) }) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy join code"
                    )
                }
                IconButton(
                    onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Join \"$groupName\" on Campus Companion with the code $joinCode."
                            )
                        }
                        context.startActivity(Intent.createChooser(share, "Share join code"))
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = "Share join code"
                    )
                }
            }
        }
        Text(
            text = "Anyone with this code can join. It does not expire.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (canReplace) {
            TextButton(
                onClick = { showReplaceConfirmation = true },
                enabled = !isReplacing,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(if (isReplacing) "Replacing…" else "Replace code")
            }
        }
        replaceError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    if (showReplaceConfirmation) {
        AlertDialog(
            onDismissRequest = { showReplaceConfirmation = false },
            title = { Text("Replace the join code?") },
            text = {
                Text(
                    "People who have the current code will no longer be able to join with it. " +
                        "Members already in the group are not affected."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReplaceConfirmation = false
                        replaceError = null
                        isReplacing = true
                        scope.launch {
                            onReplaceJoinCode().onFailure { error ->
                                replaceError = error.toUserMessage().body
                            }
                            isReplacing = false
                        }
                    }
                ) {
                    Text("Replace")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceConfirmation = false }) {
                    Text("Keep current code")
                }
            }
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
