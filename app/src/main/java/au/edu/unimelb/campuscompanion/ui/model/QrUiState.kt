package au.edu.unimelb.campuscompanion.ui.model

import java.time.Instant

/** The QR invitation dialog: creating the invite, showing it, or explaining a failure. */
sealed interface QrShareUiState {
    data object Idle : QrShareUiState

    data object CreatingInvite : QrShareUiState

    data class Ready(val groupName: String, val joinUri: String, val expiresAt: Instant) : QrShareUiState

    data class Failed(val title: String, val message: String) : QrShareUiState
}
