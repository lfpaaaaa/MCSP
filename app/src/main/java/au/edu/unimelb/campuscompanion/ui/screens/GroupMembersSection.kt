package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.R
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
    title: String,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    bottomAction: @Composable () -> Unit
) {
    val textResources = textResources()
    Scaffold(modifier = Modifier.semantics { paneTitle = title }, topBar = { TopAppBar(title = { Text(title, modifier = Modifier.semantics { heading() }) }, navigationIcon = {
        TextButton(onClick = onDismissRequest) { Text(textResources.getString(R.string.action_back)) }
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
    val textResources = textResources()
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
            catch (failure: Exception) { error = failure.toUserMessage().body.resolve(textResources) }
            loading = false
            delay(30_000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(textResources.getString(R.string.group_members_section_members, members.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (loading) CircularProgressIndicator(Modifier.semantics { contentDescription = textResources.getString(R.string.a11y_loading_members) })
        error?.let {
            Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = { retry++ }) { Text(textResources.getString(R.string.action_retry)) }
        }
        members.sortedBy { if (it.role == GroupRole.Owner) 0 else 1 }.forEach { member ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                au.edu.unimelb.campuscompanion.ui.profile.ProfileAvatar(member.displayName, member.avatarUrl, Modifier.size(40.dp).clearAndSetSemantics {})
                Text(if (member.role == GroupRole.Owner) textResources.getString(R.string.group_owner_name, member.displayName) else member.displayName,
                    modifier = Modifier.weight(1f))
                if (isOwner && member.role != GroupRole.Owner) {
                    TextButton(onClick = { selected = member; transferError = null }, modifier = Modifier.semantics { contentDescription = textResources.getString(R.string.a11y_transfer_owner, member.displayName) }) { Text(textResources.getString(R.string.group_members_section_transfer_leave)) }
                }
            }
        }
        if (isOwner && !loading && members.none { it.role != GroupRole.Owner }) {
            Text(textResources.getString(R.string.group_members_section_invite_another_member_before_transferring_ownership_and_leaving))
        }
    }
    selected?.let { member ->
        AlertDialog(
            onDismissRequest = { if (!busy) selected = null },
            title = { Text(textResources.getString(R.string.group_members_section_transfer_ownership_and_leave)) },
            text = { Text(transferError ?: textResources.getString(R.string.group_members_section_will_become_the_group_owner_you_will_leave, member.displayName)) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        onTransfer(member.userId).fold(
                            onSuccess = { selected = null },
                            onFailure = { transferError = it.toUserMessage().body.resolve(textResources) }
                        )
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (failure: Exception) { transferError = failure.toUserMessage().body.resolve(textResources)
                    } finally { busy = false }
                }
            }) { Text(if (busy) textResources.getString(R.string.group_exit_action_please_wait) else textResources.getString(R.string.group_members_section_transfer_leave)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { selected = null }) { Text(textResources.getString(R.string.action_cancel)) } }
        )
    }
}
