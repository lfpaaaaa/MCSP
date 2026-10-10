package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import au.edu.unimelb.campuscompanion.data.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.GroupOrigin
import au.edu.unimelb.campuscompanion.ui.model.isValidGroupJoinCode

@Composable
fun GroupSettingsDialog(
    group: CourseGroup,
    currentUserId: String,
    initialFolded: Boolean,
    initialMuted: Boolean,
    initialDisplayName: String,
    onDismiss: () -> Unit,
    onInviteWithNfc: () -> Unit,
    createQrInvite: suspend () -> Result<au.edu.unimelb.campuscompanion.data.model.GroupInvite>,
    observeMembers: () -> kotlinx.coroutines.flow.Flow<List<au.edu.unimelb.campuscompanion.data.model.GroupMember>>,
    onTransfer: suspend (String) -> Result<Unit>,
    onExitGroup: suspend (Boolean) -> Result<Unit>,
    onSave: suspend (folded: Boolean, muted: Boolean, displayName: String, nicknameChanged: Boolean) -> Result<Unit>
) {
    var folded by rememberSaveable(group.id) { mutableStateOf(initialFolded) }
    var muted by rememberSaveable(group.id) { mutableStateOf(initialMuted) }
    var displayName by rememberSaveable(group.id) { mutableStateOf(initialDisplayName) }

    val scope = rememberCoroutineScope()
    val latestMembers by rememberUpdatedState(observeMembers)
    var serverName by remember(group.id) { mutableStateOf<String?>(null) }
    var loadingName by remember(group.id) { mutableStateOf(true) }
    var loadAttempt by remember(group.id) { mutableStateOf(0) }
    var loadError by remember(group.id) { mutableStateOf<String?>(null) }
    var saveError by remember(group.id) { mutableStateOf<String?>(null) }
    var saving by remember(group.id) { mutableStateOf(false) }
    LaunchedEffect(group.id, currentUserId, loadAttempt) {
        loadingName = true
        loadError = null
        try {
            val member = latestMembers().first().firstOrNull { it.userId == currentUserId }
                ?: throw au.edu.unimelb.campuscompanion.data.DataError.Forbidden()
            serverName = member.displayName
            displayName = member.displayName
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { loadError = error.toUserMessage().body }
        finally { loadingName = false }
    }

    var showMembers by rememberSaveable(group.id) { mutableStateOf(false) }
    if (showMembers) {
        GroupSettingsPage(
            onDismissRequest = { showMembers = false },
            title = { Text("Group members") },
            text = {
                GroupMembersSection(
                    isOwner = group.origin == GroupOrigin.CreatedByUser,
                    observeMembers = observeMembers,
                    onTransfer = onTransfer
                )
            },
            confirmButton = {},
            dismissButton = {},
            bottomAction = {}
        )
        return
    }

    val canInvite = group.origin != GroupOrigin.Timetable && group.timetableKey == null
    var showQr by rememberSaveable(group.id) { mutableStateOf(false) }
    if (canInvite && showQr) {
        GroupInviteQrDialog(group.name, createQrInvite, onDismiss = { showQr = false })
    }

    GroupSettingsPage(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Group settings") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                OutlinedButton(
                    onClick = { showMembers = true },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Groups, contentDescription = null)
                    Text("Members (${group.members})", modifier = Modifier.padding(start = 8.dp))
                }
                if (canInvite) {
                    OutlinedButton(onClick = { showQr = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.QrCode, contentDescription = null)
                        Text("Share QR code", modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        onClick = onInviteWithNfc,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Nfc,
                            contentDescription = null
                        )
                        Text(
                            text = "Invite with NFC",
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
                    onValueChange = { displayName = it.take(40); saveError = null },
                    enabled = !loadingName && serverName != null && !saving,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Your name in this group") },
                    supportingText = { Text(if (loadingName) "Loading your group nickname…" else "${displayName.length}/40 · Visible to everyone in this group") },
                    singleLine = true
                )
                loadError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { loadAttempt++ }) { Text("Retry") }
                }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        bottomAction = {
            if (group.origin != GroupOrigin.Timetable) {
                GroupExitAction(
                    isOwner = group.origin == GroupOrigin.CreatedByUser,
                    onExit = onExitGroup
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    saving = true
                    saveError = null
                    scope.launch {
                        try {
                            onSave(folded, muted, displayName.trim(), displayName.trim() != serverName)
                                .onFailure { saveError = it.toUserMessage().body }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { saveError = error.toUserMessage().body }
                        finally { saving = false }
                    }
                },
                enabled = !saving && !loadingName && serverName != null && displayName.isNotBlank()
            ) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
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
