package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.toUserMessage
import au.edu.unimelb.campuscompanion.ui.components.GroupUpdateRow
import au.edu.unimelb.campuscompanion.ui.components.QuickActionChip
import au.edu.unimelb.campuscompanion.ui.components.SectionHeader
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.GROUP_JOIN_CODE_LENGTH
import au.edu.unimelb.campuscompanion.ui.model.NfcJoinUiState
import au.edu.unimelb.campuscompanion.ui.model.StartedGroupAccess
import au.edu.unimelb.campuscompanion.ui.model.TimetableState
import au.edu.unimelb.campuscompanion.ui.model.isValidGroupJoinCode
import au.edu.unimelb.campuscompanion.ui.model.normalizeGroupJoinCode
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.launch

@Composable
fun GroupsScreen(
    timetableState: TimetableState,
    activeGroups: List<CourseGroup>,
    foldedGroups: List<CourseGroup>,
    mutedGroupIds: Set<String>,
    onOpenTimetableSetup: () -> Unit,
    onOpenGroup: (CourseGroup) -> Unit,
    onStartGroup: suspend (name: String, courseCode: String?) -> Result<StartedGroupAccess>,
    onJoinGroup: suspend (code: String) -> Result<Unit>,
    nfcJoinState: NfcJoinUiState,
    onStartNfcJoin: () -> Unit,
    onDismissNfcJoin: () -> Unit,
    onOpenNfcSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showFoldedGroups by rememberSaveable { mutableStateOf(false) }
    var showStartGroup by rememberSaveable { mutableStateOf(false) }
    var showJoinGroup by rememberSaveable { mutableStateOf(false) }

    if (showStartGroup) {
        StartGroupDialog(
            onDismiss = { showStartGroup = false },
            onStartGroup = onStartGroup
        )
    }

    if (showJoinGroup) {
        JoinGroupDialog(
            onDismiss = { showJoinGroup = false },
            onJoinGroup = onJoinGroup
        )
    }

    if (nfcJoinState !is NfcJoinUiState.Idle) {
        NfcJoinDialog(
            state = nfcJoinState,
            onDismiss = onDismissNfcJoin,
            onTryAgain = onStartNfcJoin,
            onOpenNfcSettings = onOpenNfcSettings
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Course groups",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = when {
                    timetableState.isLoading -> "Checking your timetable and group memberships."
                    timetableState.isConnected -> {
                        "Timetable groups are folded after their final class. Your own groups stay active."
                    }
                    else -> "Create a group or connect your timetable to add course groups."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { showStartGroup = true },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text(
                    text = "Start group",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            OutlinedButton(
                onClick = { showJoinGroup = true },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.GroupAdd, contentDescription = null)
                Text(
                    text = "Join group",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickActionChip(
                label = "Scan QR",
                icon = Icons.Outlined.QrCodeScanner,
                onClick = {}
            )
            QuickActionChip(
                label = "NFC join",
                icon = Icons.Outlined.Nfc,
                onClick = onStartNfcJoin
            )
        }

        if (timetableState.isLoading && activeGroups.isEmpty() && foldedGroups.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text("Loading groups...")
                }
            }
        } else {
            SectionHeader(title = "Active groups")
            if (activeGroups.isEmpty()) {
                EmptyActiveGroups(
                    timetableConnected = timetableState.isConnected,
                    hasFoldedGroups = foldedGroups.isNotEmpty(),
                    errorMessage = timetableState.errorMessage,
                    onOpenTimetableSetup = onOpenTimetableSetup
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    activeGroups.forEach { group ->
                        GroupUpdateRow(
                            group = group,
                            muted = group.id in mutedGroupIds,
                            onClick = { onOpenGroup(group) }
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { showFoldedGroups = !showFoldedGroups },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Archive, contentDescription = null)
                Text(
                    text = "已折叠 (${foldedGroups.size})",
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Icon(
                    imageVector = if (showFoldedGroups) {
                        Icons.Outlined.ExpandLess
                    } else {
                        Icons.Outlined.ExpandMore
                    },
                    contentDescription = if (showFoldedGroups) {
                        "Hide folded groups"
                    } else {
                        "Show folded groups"
                    }
                )
            }

            if (showFoldedGroups) {
                SectionHeader(title = "Folded groups")
                if (foldedGroups.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Text(
                            text = "No folded group chats.",
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        foldedGroups.forEach { group ->
                            GroupUpdateRow(
                                group = group,
                                muted = group.id in mutedGroupIds,
                                onClick = { onOpenGroup(group) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NfcJoinDialog(
    state: NfcJoinUiState,
    onDismiss: () -> Unit,
    onTryAgain: () -> Unit,
    onOpenNfcSettings: () -> Unit
) {
    val isJoining = state is NfcJoinUiState.Joining
    val title = when (state) {
        NfcJoinUiState.Idle -> ""
        NfcJoinUiState.Waiting -> "Ready to join"
        NfcJoinUiState.Joining -> "Joining group"
        NfcJoinUiState.Disabled -> "Turn on NFC"
        NfcJoinUiState.Unsupported -> "NFC is not available"
        is NfcJoinUiState.Joined -> "Group joined"
        is NfcJoinUiState.Failed -> state.title
    }
    val message = when (state) {
        NfcJoinUiState.Idle -> ""
        NfcJoinUiState.Waiting -> {
            "Hold the back of your phone near the phone sharing the invitation. Keep this screen open until the phone vibrates."
        }
        NfcJoinUiState.Joining -> "Checking the invitation and adding the group."
        NfcJoinUiState.Disabled -> "NFC is switched off on this device. Turn it on, then return and try again."
        NfcJoinUiState.Unsupported -> "This device cannot read NFC tags. You can still join with a QR code."
        is NfcJoinUiState.Joined -> "${state.groupName} now appears in your active groups."
        is NfcJoinUiState.Failed -> state.message
    }

    AlertDialog(
        onDismissRequest = {
            if (!isJoining) onDismiss()
        },
        icon = {
            when (state) {
                NfcJoinUiState.Joining -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
                is NfcJoinUiState.Joined -> Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null
                )
                is NfcJoinUiState.Failed,
                NfcJoinUiState.Disabled,
                NfcJoinUiState.Unsupported -> Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null
                )
                else -> Icon(
                    imageVector = Icons.Outlined.Nfc,
                    contentDescription = null
                )
            }
        },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            when (state) {
                NfcJoinUiState.Disabled -> TextButton(onClick = onOpenNfcSettings) {
                    Text("Open NFC settings")
                }
                is NfcJoinUiState.Failed -> TextButton(onClick = onTryAgain) {
                    Text("Try again")
                }
                NfcJoinUiState.Joining -> Unit
                else -> TextButton(onClick = onDismiss) {
                    Text(if (state is NfcJoinUiState.Waiting) "Cancel" else "Done")
                }
            }
        },
        dismissButton = {
            if (state is NfcJoinUiState.Disabled || state is NfcJoinUiState.Failed) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
private fun EmptyActiveGroups(
    timetableConnected: Boolean,
    hasFoldedGroups: Boolean,
    errorMessage: String?,
    onOpenTimetableSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Groups,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = "No active groups",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = when {
                    hasFoldedGroups -> "Your existing group chats are in 已折叠."
                    timetableConnected -> "No active groups are available."
                    errorMessage != null -> errorMessage
                    else -> "Create a group or connect your timetable on Home."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!timetableConnected) {
                Button(onClick = onOpenTimetableSetup) {
                    Text("Go to Home")
                }
            }
        }
    }
}

@Composable
private fun StartGroupDialog(
    onDismiss: () -> Unit,
    onStartGroup: suspend (name: String, courseCode: String?) -> Result<StartedGroupAccess>
) {
    var name by rememberSaveable { mutableStateOf("") }
    var courseCode by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var isSubmitting by rememberSaveable { mutableStateOf(false) }
    var startedGroup by remember { mutableStateOf<StartedGroupAccess?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = {
            if (!isSubmitting) onDismiss()
        },
        icon = {
            if (startedGroup != null) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null)
            }
        },
        title = { Text(if (startedGroup == null) "Start group" else "Group started") },
        text = {
            val result = startedGroup
            if (result == null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it.take(60)
                            errorMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Group name") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = courseCode,
                        onValueChange = {
                            courseCode = it.uppercase().filterNot(Char::isWhitespace).take(9)
                            errorMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Course code (optional)") },
                        placeholder = { Text("COMP90018") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                    errorMessage?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${result.groupName} is ready.")
                    if (result.joinCode != null && result.expiresAt != null) {
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
                                    Text(
                                        text = "Join code",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Text(
                                        text = result.joinCode,
                                        style = MaterialTheme.typography.headlineMedium
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(result.joinCode))
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ContentCopy,
                                        contentDescription = "Copy join code"
                                    )
                                }
                            }
                        }
                        val minutesRemaining = Duration.between(Instant.now(), result.expiresAt)
                            .toMinutes()
                            .coerceAtLeast(1L)
                        Text(
                            text = "Expires in $minutesRemaining minutes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "A join code is temporarily unavailable.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (startedGroup == null) {
                Button(
                    onClick = {
                        scope.launch {
                            isSubmitting = true
                            onStartGroup(name, courseCode.ifBlank { null }).fold(
                                onSuccess = { startedGroup = it },
                                onFailure = {
                                    errorMessage = startGroupErrorMessage(it)
                                }
                            )
                            isSubmitting = false
                        }
                    },
                    enabled = name.isNotBlank() && !isSubmitting
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Start")
                    }
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }
        },
        dismissButton = {
            if (startedGroup == null) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSubmitting
                ) {
                    Text("Cancel")
                }
            }
        }
    )
}

private fun startGroupErrorMessage(error: Throwable): String = when (error) {
    is DataError.NotFound -> {
        "Group creation is not available on the connected server yet."
    }
    else -> error.toUserMessage().body
}

@Composable
private fun JoinGroupDialog(
    onDismiss: () -> Unit,
    onJoinGroup: suspend (code: String) -> Result<Unit>
) {
    var code by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var isSubmitting by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = {
            if (!isSubmitting) onDismiss()
        },
        title = { Text("Join group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Enter the 6-character code from the group creator.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = normalizeGroupJoinCode(it)
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Join code") },
                    placeholder = { Text("A1B2C3") },
                    supportingText = { Text("${code.length}/$GROUP_JOIN_CODE_LENGTH") },
                    isError = errorMessage != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                errorMessage?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        isSubmitting = true
                        onJoinGroup(code).fold(
                            onSuccess = { onDismiss() },
                            onFailure = {
                                errorMessage = joinCodeErrorMessage(it)
                            }
                        )
                        isSubmitting = false
                    }
                },
                enabled = isValidGroupJoinCode(code) && !isSubmitting
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Join")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting
            ) {
                Text("Cancel")
            }
        }
    )
}

private fun joinCodeErrorMessage(error: Throwable): String = when (error) {
    is DataError.InvalidInvite -> when (error.reason) {
        DataError.InvalidInvite.Reason.Unknown -> {
            "This 6-character code was not recognised. Check it and try again."
        }
        DataError.InvalidInvite.Reason.Expired -> {
            "This join code has expired. Ask the group creator for a new code."
        }
        DataError.InvalidInvite.Reason.UsedUp -> {
            "This join code has reached its usage limit. Ask the group creator for a new code."
        }
    }
    else -> error.toUserMessage().body
}
