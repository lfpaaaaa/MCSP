package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.data.remote.toDataError

/**
 * A failure explained for the user: what happened, in plain words, and what they can do next
 * (heuristic 9, "help users recognise, diagnose and recover from errors").
 */
data class UserMessage(val title: String, val body: String, val action: RecoveryAction?)

/** The action a screen offers next to a [UserMessage]. */
enum class RecoveryAction {
    Retry,
    SignIn,
    ScanNewInvite
}

fun DataError.toUserMessage(): UserMessage = when (this) {
    is DataError.Offline -> UserMessage(
        title = "No connection",
        body = "Check your Wi-Fi or mobile data, then try again. Anything already loaded stays on screen.",
        action = RecoveryAction.Retry
    )
    is DataError.Unauthenticated -> UserMessage(
        title = "Signed out",
        body = "Your session has ended. Sign in again to continue.",
        action = RecoveryAction.SignIn
    )
    is DataError.Forbidden -> UserMessage(
        title = "Not allowed",
        body = "You may have left this group, or only the person who added this item can change it.",
        action = null
    )
    is DataError.NotFound -> UserMessage(
        title = "No longer available",
        body = "It may have been deleted. Refresh to see the latest version.",
        action = RecoveryAction.Retry
    )
    is DataError.InvalidInvite -> when (reason) {
        DataError.InvalidInvite.Reason.Unknown -> UserMessage(
            title = "Invite not recognised",
            body = "This code or link is not a Campus Companion invite. Ask a group member to show a new QR code.",
            action = RecoveryAction.ScanNewInvite
        )
        DataError.InvalidInvite.Reason.Expired -> UserMessage(
            title = "Invite expired",
            body = "Invites work for 10 minutes. Ask a group member to show a new QR code.",
            action = RecoveryAction.ScanNewInvite
        )
        DataError.InvalidInvite.Reason.UsedUp -> UserMessage(
            title = "Invite used up",
            body = "This invite has been used by as many people as it allows. Ask a group member for a new one.",
            action = RecoveryAction.ScanNewInvite
        )
    }
    is DataError.RateLimited -> UserMessage(
        title = "Too many requests",
        body = "Wait a minute, then try again.",
        action = RecoveryAction.Retry
    )
    is DataError.Validation -> UserMessage(
        title = "Check your input",
        body = message ?: "Some of the details are not valid.",
        action = null
    )
    is DataError.Unexpected -> UserMessage(
        title = "Something went wrong",
        body = "Try again. If it keeps happening, restart the app.",
        action = RecoveryAction.Retry
    )
}

/** Explains any failure. Library and network exceptions are first mapped to a [DataError]. */
fun Throwable.toUserMessage(): UserMessage = toDataError().toUserMessage()
