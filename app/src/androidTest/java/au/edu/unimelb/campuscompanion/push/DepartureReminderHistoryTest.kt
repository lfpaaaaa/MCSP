package au.edu.unimelb.campuscompanion.push

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.edu.unimelb.campuscompanion.context.ContextState
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
        assertTrue(history.record(userId, session, ContextState.LEAVE_SOON))
        assertEquals(listOf(session.end.toInstant().toEpochMilli()), storage.all.values.toList())

        // A recreated reminder service reads the delivery written by its previous instance.
        val restartedHistory = DepartureReminderHistory(context, { initialNow }, name)
        assertTrue(restartedHistory.wasSent(userId, session, ContextState.LEAVE_SOON))
        assertFalse(restartedHistory.wasSent(userId, session, ContextState.RUNNING_LATE))
    }

    @Test fun deliveryRecordsAreIsolatedByAccountOccurrenceAndState() = withStorage { name, _ ->
        val history = DepartureReminderHistory(context, { initialNow }, name)
        assertTrue(history.record(userId, session, ContextState.LEAVE_SOON))
        assertFalse(history.wasSent("other:$userId", session, ContextState.LEAVE_SOON))
        assertFalse(history.wasSent(userId, session.copy(id = "other:${session.id}"), ContextState.LEAVE_SOON))
        val nextWeek = session.copy(start = session.start.plusWeeks(1), end = session.end.plusWeeks(1))
        assertFalse(history.wasSent(userId, nextWeek, ContextState.LEAVE_SOON))
        assertFalse(history.wasSent(userId, session, ContextState.RUNNING_LATE))

        assertTrue(history.record(userId, session, ContextState.RUNNING_LATE))
        assertTrue(history.wasSent(userId, session, ContextState.LEAVE_SOON))
        assertTrue(history.wasSent(userId, session, ContextState.RUNNING_LATE))
        val sameInstantInUtc = session.copy(
            start = session.start.withZoneSameInstant(ZoneId.of("UTC")),
            end = session.end.withZoneSameInstant(ZoneId.of("UTC"))
        )
        assertTrue(history.wasSent(userId, sameInstantInUtc, ContextState.LEAVE_SOON))
    }

    @Test fun endedClassesAreIgnoredAndExpiredRecordsArePrunedOnNextDelivery() = withStorage { name, storage ->
        var now = initialNow
        val history = DepartureReminderHistory(context, { now }, name)
        assertTrue(history.record(userId, session, ContextState.LEAVE_SOON))
        assertTrue(history.record(userId, session, ContextState.RUNNING_LATE))
        now = session.end.toInstant().minusMillis(1)
        assertTrue(history.wasSent(userId, session, ContextState.LEAVE_SOON))

        now = session.end.toInstant()
        assertFalse(history.wasSent(userId, session, ContextState.LEAVE_SOON))
        assertFalse(history.wasSent(userId, session, ContextState.RUNNING_LATE))
        assertEquals(2, storage.all.size)
        val nextWeek = session.copy(start = session.start.plusWeeks(1), end = session.end.plusWeeks(1))
        assertTrue(history.record(userId, nextWeek, ContextState.LEAVE_SOON))
        assertEquals(listOf(nextWeek.end.toInstant().toEpochMilli()), storage.all.values.toList())
        assertTrue(history.wasSent(userId, nextWeek, ContextState.LEAVE_SOON))

        // Even a late caller cannot restore an already expired occurrence.
        assertTrue(history.record(userId, session, ContextState.RUNNING_LATE))
        assertEquals(1, storage.all.size)
        assertFalse(history.wasSent(userId, session, ContextState.RUNNING_LATE))
    }

    @Test fun oldTravelWarningKeysDoNotSuppressContextEngineReminders() = withStorage { name, storage ->
        val legacyKey = "${Uri.encode(userId)}:${Uri.encode(session.id)}:RUNNING_LATE"
        assertTrue(storage.edit().putLong(legacyKey, session.end.toInstant().toEpochMilli()).commit())
        val history = DepartureReminderHistory(context, { initialNow }, name)
        assertFalse(history.wasSent(userId, session, ContextState.LEAVE_SOON))
        assertFalse(history.wasSent(userId, session, ContextState.RUNNING_LATE))
        assertTrue(history.record(userId, session, ContextState.RUNNING_LATE))
        assertTrue(history.wasSent(userId, session, ContextState.RUNNING_LATE))
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
