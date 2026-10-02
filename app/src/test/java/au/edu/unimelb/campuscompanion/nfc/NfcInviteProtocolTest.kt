package au.edu.unimelb.campuscompanion.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcInviteProtocolTest {
    @Test
    fun selectCommandTargetsCampusCompanionAid() {
        assertTrue(NfcInviteProtocol.isSelectAidCommand(NfcInviteProtocol.selectAidCommand))
        assertFalse(NfcInviteProtocol.isSelectAidCommand(byteArrayOf(0x00, 0x01)))
    }

    @Test
    fun inviteRoundTripsThroughApduResponse() {
        val inviteUri = "campuscompanion://join?token=abc123"

        val response = NfcInviteProtocol.inviteResponse(inviteUri)

        assertTrue(response.size > inviteUri.length)
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0x00), response.takeLast(2).toByteArray())
        assertTrue(NfcInviteProtocol.inviteUriFromResponse(response) == inviteUri)
    }

    @Test
    fun invalidOrFailedResponsesAreRejected() {
        assertNull(NfcInviteProtocol.inviteUriFromResponse(byteArrayOf(0x6A, 0x82.toByte())))
        assertNull(
            NfcInviteProtocol.inviteUriFromResponse(
                NfcInviteProtocol.inviteResponse("https://example.com/not-an-invite")
            )
        )
    }
}
