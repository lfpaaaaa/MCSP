package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.ui.text.UiText

import au.edu.unimelb.campuscompanion.R

import au.edu.unimelb.campuscompanion.english

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TimetableStateTest {
    private val melbourne = ZoneId.of("Australia/Melbourne")
    private val savedAt = Instant.parse("2026-10-09T11:01:00Z")

    @Test
    fun theOfflineNoticeNamesWhenTheShownCopyWasSaved() {
        val state = TimetableState(isConnected = true, lastSyncedAt = savedAt, isCached = true, errorMessage = UiText(R.string.error_no_connection))

        assertEquals(
            "Could not refresh the timetable. Showing the copy saved on Fri 9 Oct at 10:01 PM.",
            state.offlineNotice(melbourne)?.english()
        )
    }

    @Test
    fun thereIsNoNoticeWhileTheCopyIsFreshOrNothingIsSaved() {
        assertNull(TimetableState(isConnected = true, lastSyncedAt = savedAt).offlineNotice(melbourne))
        assertNull(TimetableState(isConnected = true, isCached = true, errorMessage = UiText(R.string.error_no_connection)).offlineNotice(melbourne))
    }
}
