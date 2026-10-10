package au.edu.unimelb.campuscompanion.ui.model

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
        val state = TimetableState(isConnected = true, savedAt = savedAt, refreshError = "No connection")

        assertEquals(
            "Could not refresh the timetable. Showing the copy saved on Fri 9 Oct at 10:01 PM.",
            state.offlineNotice(melbourne)
        )
    }

    @Test
    fun thereIsNoNoticeWhileTheCopyIsFreshOrNothingIsSaved() {
        assertNull(TimetableState(isConnected = true, savedAt = savedAt).offlineNotice(melbourne))
        assertNull(TimetableState(isConnected = true, refreshError = "No connection").offlineNotice(melbourne))
    }
}
