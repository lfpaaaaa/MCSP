package au.edu.unimelb.campuscompanion.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

class NfcInviteHostService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || !NfcInviteProtocol.isSelectAidCommand(commandApdu)) {
            return STATUS_COMMAND_NOT_SUPPORTED
        }
        val inviteUri = NfcInviteHostSession.currentInviteUri() ?: return STATUS_NOT_FOUND
        NfcInviteHostSession.markDelivered()
        return NfcInviteProtocol.inviteResponse(inviteUri)
    }

    override fun onDeactivated(reason: Int) = Unit

    private companion object {
        val STATUS_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        val STATUS_COMMAND_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
    }
}
