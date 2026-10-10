package au.edu.unimelb.campuscompanion.ui.screens

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
    var invite by remember { mutableStateOf<GroupInvite?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(Instant.now()) }
    val latestCreate by rememberUpdatedState(createInvite)
    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(1_000) } }
    LaunchedEffect(refresh) {
        loading = true; error = null; invite = null
        try { latestCreate().fold(onSuccess = { invite = it }, onFailure = { error = it.toUserMessage().body }) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.toUserMessage().body }
        finally { loading = false }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share QR code") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(groupName)
                if (loading) CircularProgressIndicator()
                error?.let { Text(it) }
                invite?.let { current ->
                    if (current.isExpired(now)) {
                        Text("This QR invitation has expired. Generate a new one to share.")
                    } else {
                        GroupQrDownload(current.joinUri)
                        val seconds = Duration.between(now, current.expiresAt).seconds.coerceAtLeast(0)
                        Text("QR invitation expires in ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}.")
                        Text("Scan in Campus Companion to join. Downloaded images have the same expiry.")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(enabled = !loading, onClick = { refresh++ }) { Text("New QR code") } }
    )
}
