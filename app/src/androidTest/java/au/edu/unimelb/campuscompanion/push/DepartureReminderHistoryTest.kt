package au.edu.unimelb.campuscompanion.push

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.edu.unimelb.campuscompanion.context.DepartureReminderStage
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** Uses an isolated preferences file; no real account history or device notifications are changed. */
@RunWith(AndroidJUnit4::class)
class DepartureReminderHistoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val initialNow = Instant.parse("2026-10-11T00:00:00Z")
    private val userId = "history-test:${UUID.randomUUID()}"
    private val session = CourseSession(
        id = "recurring:${UUID.randomUUID()}",
        code = "QA-HISTORY",
        title = "Reminder history test",
        location = "Test",
        room = "Test",
        start = ZonedDateTime.ofInstant(initialNow.plusSeconds(1800), ZoneId.of("Australia/Melbourne")),
        end = ZonedDateTime.ofInstant(initialNow.plusSeconds(5400), ZoneId.of("Australia/Melbourne"))
    )

    @Test fun deliveryRecordsSurviveHistoryRecreation() = withStorage { name, storage ->
        val history = DepartureReminderHistory(context, { initialNow }, name)
        assertTrue(history.record(userId, session, DepartureReminderStage.Prepare))
        assertEquals(listOf(session.end.toInstant().toEpochMilli()), storage.all.values.toList())

        // A recreated reminder service reads the delivery written by its previous instance.
        val restartedHistory = DepartureReminderHistory(context, { initialNow }, name)
        assertTrue(restartedHistory.wasSent(userId, session, DepartureReminderStage.Prepare))
        assertFalse(restartedHistory.wasSent(userId, session, DepartureReminderStage.LeaveNow))
    }

    @Test fun deliveryRecordsAreIsolatedByAccountOccurrenceAndState() = withStorage { name, _ ->
        val history = DepartureReminderHistory(context, { initialNow }, name)
        assertTrue(history.record(userId, session, DepartureReminderStage.Prepare))
        assertFalse(history.wasSent("other:$userId", session, DepartureReminderStage.Prepare))
        assertFalse(history.wasSent(userId, session.copy(id = "other:${session.id}"), DepartureReminderStage.Prepare))
        val nextWeek = session.copy(start = session.start.plusWeeks(1), end = session.end.plusWeeks(1))
        assertFalse(history.wasSent(userId, nextWeek, DepartureReminderStage.Prepare))
        assertFalse(history.wasSent(userId, session, DepartureReminderStage.LeaveNow))

        assertTrue(history.record(userId, session, DepartureReminderStage.LeaveNow))
        assertTrue(history.wasSent(userId, session, DepartureReminderStage.Prepare))
        assertTrue(history.wasSent(userId, session, DepartureReminderStage.LeaveNow))
        val sameInstantInUtc = session.copy(
            start = session.start.withZoneSameInstant(ZoneId.of("UTC")),
            end = session.end.withZoneSameInstant(ZoneId.of("UTC"))
        )
        assertTrue(history.wasSent(userId, sameInstantInUtc, DepartureReminderStage.Prepare))
    }

    @Test fun endedClassesAreIgnoredAndExpiredRecordsArePrunedOnNextDelivery() = withStorage { name, storage ->
        var now = initialNow
        val history = DepartureReminderHistory(context, { now }, name)
        assertTrue(history.record(userId, session, DepartureReminderStage.Prepare))
        assertTrue(history.record(userId, session, DepartureReminderStage.LeaveNow))
        now = session.end.toInstant().minusMillis(1)
        assertTrue(history.wasSent(userId, session, DepartureReminderStage.Prepare))

        now = session.end.toInstant()
        assertFalse(history.wasSent(userId, session, DepartureReminderStage.Prepare))
        assertFalse(history.wasSent(userId, session, DepartureReminderStage.LeaveNow))
        assertEquals(2, storage.all.size)
        val nextWeek = session.copy(start = session.start.plusWeeks(1), end = session.end.plusWeeks(1))
        assertTrue(history.record(userId, nextWeek, DepartureReminderStage.Prepare))
        assertEquals(listOf(nextWeek.end.toInstant().toEpochMilli()), storage.all.values.toList())
        assertTrue(history.wasSent(userId, nextWeek, DepartureReminderStage.Prepare))

        // Even a late caller cannot restore an already expired occurrence.
        assertTrue(history.record(userId, session, DepartureReminderStage.LeaveNow))
        assertEquals(1, storage.all.size)
        assertFalse(history.wasSent(userId, session, DepartureReminderStage.LeaveNow))
    }

    @Test fun oldTravelWarningKeysDoNotSuppressContextEngineReminders() = withStorage { name, storage ->
        val legacyKey = "${Uri.encode(userId)}:${Uri.encode(session.id)}:RUNNING_LATE"
        assertTrue(storage.edit().putLong(legacyKey, session.end.toInstant().toEpochMilli()).commit())
        val history = DepartureReminderHistory(context, { initialNow }, name)
        assertFalse(history.wasSent(userId, session, DepartureReminderStage.Prepare))
        assertFalse(history.wasSent(userId, session, DepartureReminderStage.LeaveNow))
        assertTrue(history.record(userId, session, DepartureReminderStage.LeaveNow))
        assertTrue(history.wasSent(userId, session, DepartureReminderStage.LeaveNow))
    }

    private fun withStorage(block: (String, SharedPreferences) -> Unit) {
        val name = "departure_history_test_${UUID.randomUUID()}"
        val storage = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            block(name, storage)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
