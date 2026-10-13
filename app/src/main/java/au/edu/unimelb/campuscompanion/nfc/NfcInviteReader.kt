package au.edu.unimelb.campuscompanion.nfc

import au.edu.unimelb.campuscompanion.ui.text.UiText
import au.edu.unimelb.campuscompanion.R
import android.nfc.Tag
import android.nfc.tech.IsoDep

object NfcInviteReader {
    fun readInviteUri(tag: Tag): Result<String> = runCatching {
        val isoDep = IsoDep.get(tag) ?: throw NfcReadException(UiText(R.string.nfc_the_other_device_is_not_sharing_a_campus))
        try {
            isoDep.connect()
            isoDep.timeout = READ_TIMEOUT_MILLIS
            val response = isoDep.transceive(NfcInviteProtocol.selectAidCommand)
            NfcInviteProtocol.inviteUriFromResponse(response)
                ?: throw NfcReadException(UiText(R.string.nfc_the_nfc_invitation_could_not_be_read_hold))
        } finally {
            runCatching { isoDep.close() }
        }
    }

    private const val READ_TIMEOUT_MILLIS = 5_000
}

class NfcReadException(val userMessage: UiText) : Exception("NFC read failed")
