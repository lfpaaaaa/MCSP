package au.edu.unimelb.campuscompanion.data.remote

import au.edu.unimelb.campuscompanion.data.model.GroupRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class GroupRowsTest {

    @Test
    fun groupSummariesAreDecodedFromPostgrestJson() {
        val body = """
            [{"id":"e5000000-0000-4000-8000-000000000005","name":"Mobile team","course_code":"COMP90018",
              "private_content_enabled":false,"created_by":"a1000000-0000-4000-8000-000000000001",
              "created_at":"2026-09-25T05:30:00.123456+00:00","my_role":"owner","member_count":3,
              "unread_count":2,"latest_message_preview":"Meeting at 3 pm",
              "latest_activity_at":"2026-09-25T06:00:00+00:00","latest_file_name":null,
              "column_added_later":true}]
        """.trimIndent()

        val summary = decodeRows(body, GroupSummaryRow.serializer()).single().toModel()

        assertEquals("Mobile team", summary.group.name)
        assertEquals("COMP90018", summary.group.courseCode)
        assertEquals(Instant.parse("2026-09-25T05:30:00.123456Z"), summary.group.createdAt)
        assertEquals(GroupRole.Owner, summary.myRole)
        assertEquals(3, summary.memberCount)
        assertEquals(2, summary.unreadCount)
        assertEquals(Instant.parse("2026-09-25T06:00:00Z"), summary.latestActivityAt)
        assertNull(summary.latestFileName)
    }

    @Test
    fun singleRowsAreReadFromObjectsAndArrays() {
        val row = """{"id":"g1","name":"Revision","course_code":null,"private_content_enabled":false,"created_by":null,"created_at":"2026-09-25T05:30:00+00:00"}"""

        val fromObject = decodeRow(row, GroupRow.serializer()).toModel()
        val fromArray = decodeRow("[$row]", GroupRow.serializer()).toModel()

        assertEquals(fromObject, fromArray)
        assertNull(fromObject.createdBy)
        assertNull(fromObject.courseCode)
    }

    @Test
    fun invitesBecomeJoinLinks() {
        val body = """[{"token":"0123abcd","expires_at":"2026-09-25T06:10:00+00:00"}]"""

        val invite = decodeRow(body, InviteRow.serializer()).toModel("g1")

        assertEquals("campuscompanion://join?token=0123abcd", invite.joinUri)
        assertEquals(Instant.parse("2026-09-25T06:10:00Z"), invite.expiresAt)
    }

    @Test
    fun unknownRolesAreTreatedAsMembers() {
        val body = """
            [{"user_id":"u1","display_name":"Alice","avatar_url":null,"role":"owner","joined_at":"2026-09-25T05:30:00+00:00"},
             {"user_id":"u2","display_name":"Bob","avatar_url":null,"role":"moderator","joined_at":"2026-09-25T05:31:00+00:00"}]
        """.trimIndent()

        val members = decodeRows(body, GroupMemberRow.serializer()).map { it.toModel() }

        assertEquals(listOf(GroupRole.Owner, GroupRole.Member), members.map { it.role })
    }

    @Test
    fun deletedRowsAreCounted() {
        assertEquals(1, countRows("""[{"group_id":"g1","user_id":"u1"}]"""))
        assertEquals(0, countRows("[]"))
    }
}
