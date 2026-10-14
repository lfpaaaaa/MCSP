package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.data.model.GroupInvite
import au.edu.unimelb.campuscompanion.data.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.Duration

@Composable
internal fun GroupInviteQrDialog(
    groupName: String,
    createInvite: suspend () -> Result<GroupInvite>,
    onDismiss: () -> Unit
) {
    val textResources = textResources()
    var invite by remember { mutableStateOf<GroupInvite?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(Instant.now()) }
    val latestCreate by rememberUpdatedState(createInvite)
    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(1_000) } }
    LaunchedEffect(refresh) {
        loading = true; error = null; invite = null
        try { latestCreate().fold(onSuccess = { invite = it }, onFailure = { error = it.toUserMessage().body.resolve(textResources) }) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.toUserMessage().body.resolve(textResources) }
        finally { loading = false }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(textResources.getString(R.string.group_invite_qr_share_qr_code)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(groupName)
                if (loading) CircularProgressIndicator(Modifier.semantics { contentDescription = textResources.getString(R.string.qr_invite_creating_invitation) })
                error?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                invite?.let { current ->
                    if (current.isExpired(now)) {
                        Text(textResources.getString(R.string.group_invite_qr_this_qr_invitation_has_expired_generate_a_new), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    } else {
                        GroupQrDownload(current.joinUri)
                        val seconds = Duration.between(now, current.expiresAt).seconds.coerceAtLeast(0)
                        Text(textResources.getString(R.string.copy_qr_invitation_expires_in, seconds / 60, (seconds % 60).toString().padStart(2, '0')))
                        Text(textResources.getString(R.string.group_invite_qr_scan_in_campus_companion_to_join_downloaded_images))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(textResources.getString(R.string.course_reminder_settings_done)) } },
        dismissButton = { TextButton(enabled = !loading, onClick = { refresh++ }) { Text(textResources.getString(R.string.group_invite_qr_new_qr_code)) } }
    )
}
