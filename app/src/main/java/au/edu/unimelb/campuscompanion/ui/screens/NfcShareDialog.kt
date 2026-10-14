package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.ui.model.NfcShareUiState

@Composable
fun NfcShareDialog(
    state: NfcShareUiState,
    onDismiss: () -> Unit,
    onTryAgain: () -> Unit,
    onOpenNfcSettings: () -> Unit
) {
    val isCreating = state is NfcShareUiState.CreatingInvite
    val title = when (state) {
        NfcShareUiState.Idle -> ""
        NfcShareUiState.CreatingInvite -> "Creating invitation"
        NfcShareUiState.Disabled -> "Turn on NFC"
        NfcShareUiState.Unsupported -> "NFC sharing is not available"
        is NfcShareUiState.Ready -> "Ready to share"
        is NfcShareUiState.Shared -> "Invitation shared"
        is NfcShareUiState.Failed -> state.title
    }
    val message = when (state) {
        NfcShareUiState.Idle -> ""
        NfcShareUiState.CreatingInvite -> "Preparing a secure group invitation."
        NfcShareUiState.Disabled -> "NFC is switched off on this device. Turn it on, then return and try again."
        NfcShareUiState.Unsupported -> "This phone cannot share invitations with NFC. Use the QR invitation instead."
        is NfcShareUiState.Ready -> {
            "On the other phone, tap NFC join, then hold the backs of both phones together. The ${state.groupName} invitation expires in 10 minutes."
        }
        is NfcShareUiState.Shared -> "The other phone received the ${state.groupName} invitation."
        is NfcShareUiState.Failed -> state.message
    }

    AlertDialog(
        onDismissRequest = {
            if (!isCreating) onDismiss()
        },
        icon = {
            when (state) {
                NfcShareUiState.CreatingInvite -> CircularProgressIndicator(
                    modifier = Modifier.size(28.dp)
                )
                NfcShareUiState.Disabled,
                NfcShareUiState.Unsupported,
                is NfcShareUiState.Failed -> Icon(
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
                NfcShareUiState.Disabled -> TextButton(onClick = onOpenNfcSettings) {
                    Text("Open NFC settings")
                }
                is NfcShareUiState.Failed -> TextButton(onClick = onTryAgain) {
                    Text("Try again")
                }
                NfcShareUiState.CreatingInvite -> Unit
                is NfcShareUiState.Ready -> TextButton(onClick = onDismiss) {
                    Text("Stop sharing")
                }
                else -> TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }
        },
        dismissButton = {
            if (state is NfcShareUiState.Disabled || state is NfcShareUiState.Failed) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
