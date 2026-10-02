package au.edu.unimelb.campuscompanion.ui.model

import java.time.Instant

sealed interface NfcJoinUiState {
    data object Idle : NfcJoinUiState
    data object Waiting : NfcJoinUiState
    data object Joining : NfcJoinUiState
    data object Disabled : NfcJoinUiState
    data object Unsupported : NfcJoinUiState
    data class Joined(val groupName: String) : NfcJoinUiState
    data class Failed(val title: String, val message: String) : NfcJoinUiState
}

sealed interface NfcShareUiState {
    data object Idle : NfcShareUiState
    data object CreatingInvite : NfcShareUiState
    data object Disabled : NfcShareUiState
    data object Unsupported : NfcShareUiState
    data class Ready(val groupName: String, val expiresAt: Instant) : NfcShareUiState
    data class Shared(val groupName: String) : NfcShareUiState
    data class Failed(val title: String, val message: String) : NfcShareUiState
}
