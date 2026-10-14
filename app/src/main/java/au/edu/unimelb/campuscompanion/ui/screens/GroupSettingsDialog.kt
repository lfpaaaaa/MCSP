package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.setValue

import androidx.compose.runtime.getValue

import au.edu.unimelb.campuscompanion.ui.text.textResources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import au.edu.unimelb.campuscompanion.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import au.edu.unimelb.campuscompanion.data.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.GroupOrigin
import au.edu.unimelb.campuscompanion.ui.model.StartedGroupAccess
import java.time.Instant

@Composable
fun GroupSettingsDialog(
    group: CourseGroup,
    currentUserId: String,
    initialFolded: Boolean,
    initialMuted: Boolean,
    initialDisplayName: String,
    onDismiss: () -> Unit,
    onOpenMembers: () -> Unit,
    onOpenFiles: () -> Unit,
    createQrInvite: suspend () -> Result<au.edu.unimelb.campuscompanion.data.model.GroupInvite>,
    observeMembers: () -> kotlinx.coroutines.flow.Flow<List<au.edu.unimelb.campuscompanion.data.model.GroupMember>>,
    onExitGroup: suspend (Boolean) -> Result<Unit>,
    onSave: suspend (folded: Boolean, muted: Boolean, displayName: String, nicknameChanged: Boolean) -> Result<Unit>
) {
    val textResources = textResources()
    var folded by rememberSaveable(group.id) { mutableStateOf(initialFolded) }
    var muted by rememberSaveable(group.id) { mutableStateOf(initialMuted) }
    var displayName by rememberSaveable(group.id) { mutableStateOf(initialDisplayName) }

    val scope = rememberCoroutineScope()
    val latestMembers by rememberUpdatedState(observeMembers)
    var serverName by rememberSaveable(group.id) { mutableStateOf<String?>(null) }
    var loadingName by remember(group.id) { mutableStateOf(true) }
    var loadAttempt by remember(group.id) { mutableStateOf(0) }
    var loadError by remember(group.id) { mutableStateOf<String?>(null) }
    var saveError by remember(group.id) { mutableStateOf<String?>(null) }
    var saving by remember(group.id) { mutableStateOf(false) }
    var nicknameEdited by rememberSaveable(group.id) { mutableStateOf(false) }
    BackHandler(enabled = saving) { /* Keep an in-flight save on screen. */ }
    LaunchedEffect(group.id, currentUserId, loadAttempt) {
        loadingName = true
        loadError = null
        try {
            val member = latestMembers().first().firstOrNull { it.userId == currentUserId }
                ?: throw au.edu.unimelb.campuscompanion.data.DataError.Forbidden()
            serverName = member.displayName
            if (!nicknameEdited) displayName = member.displayName
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { loadError = error.toUserMessage().body.resolve(textResources) }
        finally { loadingName = false }
    }

    val canInvite = group.origin != GroupOrigin.Timetable && group.timetableKey == null
    val joinAccess = StartedGroupAccess(group.name, group.joinCode, group.joinCodeExpiresAt)
    val hasActiveJoinCode = joinAccess.activeJoinCode(rememberJoinCodeTime(joinAccess)) != null
    var showJoinCode by rememberSaveable(group.id) { mutableStateOf(false) }
    if (canInvite && showJoinCode) {
        GroupJoinCodeDialog(
            access = joinAccess,
            onDismiss = { showJoinCode = false }
        )
    }
    var showQr by rememberSaveable(group.id) { mutableStateOf(false) }
    if (canInvite && showQr) {
        GroupInviteQrDialog(group.name, createQrInvite, onDismiss = { showQr = false })
    }

    GroupSettingsPage(
        onDismissRequest = { if (!saving) onDismiss() },
        title = textResources.getString(R.string.campus_companion_app_group_settings),
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenMembers,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Groups, contentDescription = null)
                    Text(textResources.getString(R.string.group_members_section_members, group.members), modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = onOpenFiles, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.group_files))
                }
                if (canInvite) {
                    if (hasActiveJoinCode) {
                        OutlinedButton(
                            onClick = { showJoinCode = joinAccess.activeJoinCode(Instant.now()) != null },
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.groups_view_join_code))
                        }
                    }
                    OutlinedButton(onClick = { showQr = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.QrCode, contentDescription = null)
                        Text(textResources.getString(R.string.group_invite_qr_share_qr_code), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                SettingSwitchRow(
                    title = textResources.getString(R.string.group_settings_fold_this_group),
                    description = if (group.origin == GroupOrigin.Timetable) {
                        textResources.getString(R.string.group_settings_timetable_groups_are_folded_automatically_after_the_final)
                    } else {
                        textResources.getString(R.string.group_settings_created_and_joined_groups_stay_active_until_you)
                    },
                    checked = folded,
                    onCheckedChange = { folded = it },
                    enabled = !saving
                )
                SettingSwitchRow(
                    title = textResources.getString(R.string.group_settings_mute_notifications),
                    description = textResources.getString(R.string.group_settings_keep_the_chat_available_without_notification_alerts),
                    checked = muted,
                    onCheckedChange = { muted = it },
                    enabled = !saving
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it.take(40); nicknameEdited = true; saveError = null },
                    enabled = !loadingName && serverName != null && !saving,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(textResources.getString(R.string.group_settings_your_name_in_this_group)) },
                    supportingText = { Text(if (loadingName) textResources.getString(R.string.group_settings_loading_your_group_nickname) else textResources.getString(R.string.group_settings_40_visible_to_everyone_in_this_group, displayName.length)) },
                    singleLine = true
                )
                loadError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onClick = { loadAttempt++ }) { Text(textResources.getString(R.string.action_retry)) }
                }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
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
                                .onFailure { saveError = it.toUserMessage().body.resolve(textResources) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { saveError = error.toUserMessage().body.resolve(textResources) }
                        finally { saving = false }
                    }
                },
                enabled = !saving && !loadingName && serverName != null && displayName.isNotBlank()
            ) {
                Text(if (saving) textResources.getString(R.string.chat_attachments_saving) else textResources.getString(R.string.group_settings_save))
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) {
                Text(textResources.getString(R.string.action_cancel))
            }
        }
    )
}

@Composable
internal fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
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
            onCheckedChange = null,
            enabled = enabled
        )
    }
}
