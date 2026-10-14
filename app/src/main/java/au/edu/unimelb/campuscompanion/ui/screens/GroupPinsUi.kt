package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.model.*
import au.edu.unimelb.campuscompanion.data.toUserMessage
import au.edu.unimelb.campuscompanion.ui.text.textResources
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun GroupPinsBar(state: GroupPinsState, onOpen: (PinVisibility) -> Unit, onRetry: () -> Unit) {
    val resources = textResources()
    Column {
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().semantics {
            contentDescription = resources.getString(R.string.pins_loading)
        })
        state.items.firstOrNull { it.visibility == PinVisibility.Group }?.let { shared ->
            PinBarRow(stringResource(R.string.pins_shared), shared.preview, onClick = { onOpen(PinVisibility.Group) })
        }
        val personal = state.items.filter { it.visibility == PinVisibility.Personal }
        if (personal.isNotEmpty()) PinBarRow(
            pluralStringResource(R.plurals.pins_personal_count, personal.size, personal.size),
            personal.first().preview, onClick = { onOpen(PinVisibility.Personal) })
        state.error?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pins_load_failed), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

@Composable
private fun PinBarRow(label: String, preview: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.PushPin, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(preview, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
internal fun PinBadge(personal: Boolean, shared: Boolean, color: Color) {
    if (!personal && !shared) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Outlined.PushPin, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
        Text(stringResource(when {
            personal && shared -> R.string.pins_both
            shared -> R.string.pins_shared
            else -> R.string.pins_personal
        }), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
internal fun PinActionsDialog(
    state: GroupPinsState, target: PinTarget, busy: Boolean, error: String?,
    onPin: (Boolean) -> Unit, onUnpin: (GroupPin) -> Unit, onDismiss: () -> Unit
) {
    val personal = state.items.firstOrNull { it.target == target && it.visibility == PinVisibility.Personal }
    val shared = state.items.firstOrNull { it.target == target && it.visibility == PinVisibility.Group }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.pins_actions)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pins_personal_help))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                if (personal != null) onUnpin(personal) else onPin(false)
            }) { Text(stringResource(if (personal == null) R.string.pins_pin_personal else R.string.pins_unpin_personal)) }
            if (state.canPinForEveryone) {
                Text(stringResource(R.string.pins_shared_help), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    if (shared != null) onUnpin(shared) else onPin(true)
                }) { Text(stringResource(if (shared == null) R.string.pins_pin_shared else R.string.pins_unpin_shared)) }
            }
        } },
        confirmButton = {}, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.chat_attachments_close)) } })
}

@Composable
internal fun PinnedItemsDialog(
    state: GroupPinsState, visibility: PinVisibility, busy: Boolean, error: String?,
    onUnpin: (GroupPin) -> Unit, onOpenFile: (SharedFile) -> Unit, onRefresh: () -> Unit, onDismiss: () -> Unit
) {
    val resources = textResources()
    val pins = state.items.filter { it.visibility == visibility }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (visibility == PinVisibility.Group) R.string.pins_shared else R.string.pins_personal)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            (error ?: state.error?.toUserMessage()?.body?.resolve(resources))?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (pins.isEmpty() && !state.loading) Text(stringResource(R.string.pins_empty))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(pins, key = GroupPin::id) { pin ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pin.senderName, style = MaterialTheme.typography.titleSmall)
                        pin.message?.let { SelectionContainer { Text(it.body) } }
                        pin.file?.let { file ->
                            Text(file.fileName, style = MaterialTheme.typography.bodyLarge)
                            if (!file.allowDownloads) Text(stringResource(R.string.files_preview_only), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(enabled = !busy, onClick = { onOpenFile(file) }) { Text(stringResource(R.string.pins_open_attachment)) }
                        }
                        Text(stringResource(R.string.pins_attribution, pin.pinnedByName,
                            DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(pin.pinnedAt)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (pin.canUnpin) TextButton(enabled = !busy, onClick = { onUnpin(pin) }) {
                            Text(stringResource(if (visibility == PinVisibility.Group) R.string.pins_unpin_shared else R.string.pins_unpin_personal))
                        }
                        HorizontalDivider()
                    }
                }
            }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.chat_attachments_close)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onRefresh) { Text(stringResource(R.string.action_refresh)) } })
}
