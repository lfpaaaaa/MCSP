package au.edu.unimelb.campuscompanion.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep

object NfcInviteReader {
    fun readInviteUri(tag: Tag): Result<String> = runCatching {
        val isoDep = IsoDep.get(tag) ?: error("The other device is not sharing a Campus Companion invite.")
        try {
            isoDep.connect()
            isoDep.timeout = READ_TIMEOUT_MILLIS
            val response = isoDep.transceive(NfcInviteProtocol.selectAidCommand)
            NfcInviteProtocol.inviteUriFromResponse(response)
                ?: error("The NFC invitation could not be read. Hold the phones together and try again.")
        } finally {
            runCatching { isoDep.close() }
        }
    }

    private const val READ_TIMEOUT_MILLIS = 5_000
}
