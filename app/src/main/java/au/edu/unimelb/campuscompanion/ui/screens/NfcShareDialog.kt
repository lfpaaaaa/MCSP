package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.R
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
    val textResources = textResources()
    val isCreating = state is NfcShareUiState.CreatingInvite
    val title = when (state) {
        NfcShareUiState.Idle -> ""
        NfcShareUiState.CreatingInvite -> textResources.getString(R.string.nfc_share_creating_invitation)
        NfcShareUiState.Disabled -> textResources.getString(R.string.groups_turn_on_nfc)
        NfcShareUiState.Unsupported -> textResources.getString(R.string.presentation_nfc_sharing_is_not_available)
        is NfcShareUiState.Ready -> textResources.getString(R.string.nfc_share_ready_to_share)
        is NfcShareUiState.Shared -> textResources.getString(R.string.nfc_share_invitation_shared)
        is NfcShareUiState.Failed -> state.title
    }
    val message = when (state) {
        NfcShareUiState.Idle -> ""
        NfcShareUiState.CreatingInvite -> textResources.getString(R.string.nfc_share_preparing_a_secure_group_invitation)
        NfcShareUiState.Disabled -> textResources.getString(R.string.groups_nfc_is_switched_off_on_this_device_turn)
        NfcShareUiState.Unsupported -> textResources.getString(R.string.nfc_share_this_phone_cannot_share_invitations_with_nfc_use)
        is NfcShareUiState.Ready -> {
            textResources.getString(R.string.nfc_share_on_the_other_phone_tap_nfc_join_then, state.groupName)
        }
        is NfcShareUiState.Shared -> textResources.getString(R.string.nfc_share_the_other_phone_received_the_invitation, state.groupName)
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
        text = { Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
        confirmButton = {
            when (state) {
                NfcShareUiState.Disabled -> TextButton(onClick = onOpenNfcSettings) {
                    Text(textResources.getString(R.string.groups_open_nfc_settings))
                }
                is NfcShareUiState.Failed -> TextButton(onClick = onTryAgain) {
                    Text(textResources.getString(R.string.groups_try_again))
                }
                NfcShareUiState.CreatingInvite -> Unit
                is NfcShareUiState.Ready -> TextButton(onClick = onDismiss) {
                    Text(textResources.getString(R.string.nfc_share_stop_sharing))
                }
                else -> TextButton(onClick = onDismiss) {
                    Text(textResources.getString(R.string.course_reminder_settings_done))
                }
            }
        },
        dismissButton = {
            if (state is NfcShareUiState.Disabled || state is NfcShareUiState.Failed) {
                TextButton(onClick = onDismiss) {
                    Text(textResources.getString(R.string.action_cancel))
                }
            }
        }
    )
}
