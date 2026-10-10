package au.edu.unimelb.campuscompanion.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.data.model.GroupMember
import au.edu.unimelb.campuscompanion.data.model.GroupRole
import au.edu.unimelb.campuscompanion.data.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupSettingsPage(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    bottomAction: @Composable () -> Unit
) {
    BackHandler(onBack = onDismissRequest)
    Scaffold(topBar = { TopAppBar(title = title, navigationIcon = {
        TextButton(onClick = onDismissRequest) { Text("Back") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) { text() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                dismissButton(); confirmButton()
            }
            Spacer(Modifier.height(12.dp))
            bottomAction()
        }
    }
}

@Composable
internal fun GroupMembersSection(
    isOwner: Boolean,
    observeMembers: () -> kotlinx.coroutines.flow.Flow<List<GroupMember>>,
    onTransfer: suspend (String) -> Result<Unit>
) {
    var members by remember { mutableStateOf<List<GroupMember>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<GroupMember?>(null) }
    var busy by remember { mutableStateOf(false) }
    var transferError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val latestLoad by rememberUpdatedState(observeMembers)
    LaunchedEffect(retry) {
        while (true) {
            try {
                latestLoad().collect { updated -> members = updated; error = null; loading = false }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.toUserMessage().body }
            loading = false
            delay(30_000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Members (${members.size})", style = MaterialTheme.typography.titleMedium)
        if (loading) CircularProgressIndicator()
        error?.let {
            Text(it)
            TextButton(onClick = { retry++ }) { Text("Retry") }
        }
        members.sortedBy { if (it.role == GroupRole.Owner) 0 else 1 }.forEach { member ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(member.displayName + if (member.role == GroupRole.Owner) " · 群主" else "",
                    modifier = Modifier.weight(1f))
                if (isOwner && member.role != GroupRole.Owner) {
                    TextButton(onClick = { selected = member; transferError = null }) { Text("Transfer & leave") }
                }
            }
        }
        if (isOwner && !loading && members.none { it.role != GroupRole.Owner }) {
            Text("Invite another member before transferring ownership and leaving.")
        }
    }
    selected?.let { member ->
        AlertDialog(
            onDismissRequest = { if (!busy) selected = null },
            title = { Text("Transfer ownership and leave?") },
            text = { Text(transferError ?: "${member.displayName} will become the group owner. You will leave the group, and the group and its messages will remain available to the other members.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        onTransfer(member.userId).fold(
                            onSuccess = { selected = null },
                            onFailure = { transferError = it.toUserMessage().body }
                        )
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (failure: Exception) { transferError = failure.toUserMessage().body
                    } finally { busy = false }
                }
            }) { Text(if (busy) "Please wait…" else "Transfer & leave") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { selected = null }) { Text("Cancel") } }
        )
    }
}
