package au.edu.unimelb.campuscompanion.nfc

import au.edu.unimelb.campuscompanion.data.model.GroupInvite
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small APDU protocol used between the inviter's HCE service and the joiner's reader mode. */
object NfcInviteProtocol {
    const val AID = "F043414D50555301"

    private val successStatus = byteArrayOf(0x90.toByte(), 0x00)
    val selectAidCommand: ByteArray = byteArrayOf(
        0x00,
        0xA4.toByte(),
        0x04,
        0x00,
        (AID.length / 2).toByte(),
        *AID.hexToBytes(),
        0x00
    )

    fun isSelectAidCommand(command: ByteArray): Boolean =
        command.contentEquals(selectAidCommand)

    fun inviteResponse(inviteUri: String): ByteArray =
        inviteUri.toByteArray(StandardCharsets.UTF_8) + successStatus

    fun inviteUriFromResponse(response: ByteArray): String? {
        if (response.size <= successStatus.size) return null
        val statusStart = response.size - successStatus.size
        if (!response.copyOfRange(statusStart, response.size).contentEquals(successStatus)) {
            return null
        }
        val uri = response.copyOfRange(0, statusStart).toString(StandardCharsets.UTF_8)
        return uri.takeIf { GroupInvite.tokenFromUri(it) != null }
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { byte -> byte.toInt(16).toByte() }.toByteArray()
}

/** The active invitation is deliberately process-memory only. */
object NfcInviteHostSession {
    private val activeInviteUri = AtomicReference<String?>(null)
    private val _delivered = MutableStateFlow(false)

    val delivered: StateFlow<Boolean> = _delivered.asStateFlow()

    fun publish(inviteUri: String) {
        require(GroupInvite.tokenFromUri(inviteUri) != null) { "Invalid group invitation" }
        activeInviteUri.set(inviteUri)
        _delivered.value = false
    }

    fun currentInviteUri(): String? = activeInviteUri.get()

    fun markDelivered() {
        _delivered.value = true
    }

    fun clear() {
        activeInviteUri.set(null)
        _delivered.value = false
    }
}
