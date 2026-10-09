package au.edu.unimelb.campuscompanion.ui.chat

import au.edu.unimelb.campuscompanion.data.model.ChatMessage
import au.edu.unimelb.campuscompanion.data.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ChatTimelineRowsTest {
    private val melbourne: ZoneId = ZoneId.of("Australia/Melbourne")
    private val today: LocalDate = LocalDate.of(2026, 10, 9)

    @Test
    fun aDayLabelIsInsertedWhereTheLocalDateChanges() {
        val items = listOf(
            message("a", Instant.parse("2026-10-05T03:00:00Z")),
            message("b", Instant.parse("2026-10-08T12:30:00Z")), // 23:30 on 8 Oct in Melbourne
            message("c", Instant.parse("2026-10-08T13:30:00Z")), // 00:30 on 9 Oct in Melbourne
            message("d", Instant.parse("2026-10-09T02:00:00Z"))
        )

        val rows = timelineRows(items, melbourne, today)

        assertEquals(
            listOf("day:Mon 5 Oct", "message:a", "day:Yesterday", "message:b", "day:Today", "message:c", "message:d"),
            rows.map { it.key }
        )
    }

    @Test
    fun anEmptyTimelineHasNoRows() {
        assertEquals(emptyList<TimelineRow>(), timelineRows(emptyList(), melbourne, today))
    }

    private fun message(id: String, createdAt: Instant) = ChatTimelineItem.Message(
        message = ChatMessage(
            id = id,
            clientId = id,
            groupId = "g",
            senderId = "u",
            senderName = "User",
            body = "Message $id",
            createdAt = createdAt,
            status = MessageStatus.Sent
        ),
        isMine = false
    )
}
