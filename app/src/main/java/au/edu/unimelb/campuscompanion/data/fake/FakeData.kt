package au.edu.unimelb.campuscompanion.data.fake

import au.edu.unimelb.campuscompanion.data.model.ChatMessage
import au.edu.unimelb.campuscompanion.data.model.Group
import au.edu.unimelb.campuscompanion.data.model.GroupMember
import au.edu.unimelb.campuscompanion.data.model.GroupRole
import au.edu.unimelb.campuscompanion.data.model.GroupSummary
import au.edu.unimelb.campuscompanion.data.model.MessageStatus
import au.edu.unimelb.campuscompanion.data.model.SharedFile
import java.time.Duration
import java.time.Instant

/** Sample content shared by the fake repositories. Times are relative to the supplied instant. */
object FakeData {
    const val CURRENT_USER_ID = "00000000-0000-4000-8000-000000000001"
    const val CURRENT_USER_NAME = "You"
    const val DEFAULT_LATENCY_MILLIS = 400L

    const val TEAM_GROUP_ID = "10000000-0000-4000-8000-000000000001"
    const val STUDY_GROUP_ID = "10000000-0000-4000-8000-000000000002"
    const val JOINABLE_GROUP_ID = "10000000-0000-4000-8000-000000000003"

    /** Always joins [JOINABLE_GROUP_ID]; handy for demonstrating QR and NFC joining. */
    const val DEMO_INVITE_TOKEN = "demo-invite-token"

    private const val ALEX_ID = "00000000-0000-4000-8000-000000000002"
    private const val PRIYA_ID = "00000000-0000-4000-8000-000000000003"
    private const val SAM_ID = "00000000-0000-4000-8000-000000000004"

    fun groupSummaries(now: Instant): List<GroupSummary> = listOf(
        GroupSummary(
            group = Group(
                id = TEAM_GROUP_ID,
                name = "Campus Companion team",
                courseCode = "COMP90018",
                privateContentEnabled = true,
                createdBy = CURRENT_USER_ID,
                createdAt = now.minus(Duration.ofDays(14))
            ),
            myRole = GroupRole.Owner,
            memberCount = 4,
            unreadCount = 3,
            latestMessagePreview = "Uploaded proposal.pdf for review",
            latestActivityAt = now.minus(Duration.ofMinutes(12)),
            latestFileName = "proposal.pdf"
        ),
        GroupSummary(
            group = Group(
                id = STUDY_GROUP_ID,
                name = "Testing revision",
                courseCode = "SWEN90006",
                privateContentEnabled = false,
                createdBy = ALEX_ID,
                createdAt = now.minus(Duration.ofDays(7))
            ),
            myRole = GroupRole.Member,
            memberCount = 3,
            unreadCount = 0,
            latestMessagePreview = "See you in the library at 4",
            latestActivityAt = now.minus(Duration.ofHours(5)),
            latestFileName = null
        )
    )

    /** A group the signed-in user has not joined yet. */
    fun joinableGroup(now: Instant): Group = Group(
        id = JOINABLE_GROUP_ID,
        name = "Library study session",
        courseCode = null,
        privateContentEnabled = false,
        createdBy = PRIYA_ID,
        createdAt = now.minus(Duration.ofDays(2))
    )

    fun members(now: Instant): Map<String, List<GroupMember>> = mapOf(
        TEAM_GROUP_ID to listOf(
            member(CURRENT_USER_ID, CURRENT_USER_NAME, GroupRole.Owner, now.minus(Duration.ofDays(14))),
            member(ALEX_ID, "Alex Chen", GroupRole.Member, now.minus(Duration.ofDays(13))),
            member(PRIYA_ID, "Priya Nair", GroupRole.Member, now.minus(Duration.ofDays(13))),
            member(SAM_ID, "Sam Taylor", GroupRole.Member, now.minus(Duration.ofDays(12)))
        ),
        STUDY_GROUP_ID to listOf(
            member(ALEX_ID, "Alex Chen", GroupRole.Owner, now.minus(Duration.ofDays(7))),
            member(CURRENT_USER_ID, CURRENT_USER_NAME, GroupRole.Member, now.minus(Duration.ofDays(6))),
            member(SAM_ID, "Sam Taylor", GroupRole.Member, now.minus(Duration.ofDays(6)))
        ),
        JOINABLE_GROUP_ID to listOf(
            member(PRIYA_ID, "Priya Nair", GroupRole.Owner, now.minus(Duration.ofDays(2)))
        )
    )

    fun messages(now: Instant): Map<String, List<ChatMessage>> = mapOf(
        TEAM_GROUP_ID to listOf(
            message(
                id = "m1",
                groupId = TEAM_GROUP_ID,
                senderId = ALEX_ID,
                senderName = "Alex Chen",
                body = "Can everyone make the 3 pm meeting?",
                createdAt = now.minus(Duration.ofMinutes(40))
            ),
            message(
                id = "m2",
                groupId = TEAM_GROUP_ID,
                senderId = SAM_ID,
                senderName = "Sam Taylor",
                body = "Yes, I'll book a room in the library.",
                createdAt = now.minus(Duration.ofMinutes(25))
            ),
            message(
                id = "m3",
                groupId = TEAM_GROUP_ID,
                senderId = PRIYA_ID,
                senderName = "Priya Nair",
                body = "Uploaded proposal.pdf for review",
                createdAt = now.minus(Duration.ofMinutes(12))
            )
        ),
        STUDY_GROUP_ID to listOf(
            message(
                id = "m4",
                groupId = STUDY_GROUP_ID,
                senderId = ALEX_ID,
                senderName = "Alex Chen",
                body = "See you in the library at 4",
                createdAt = now.minus(Duration.ofHours(5))
            )
        )
    )

    fun files(now: Instant): List<SharedFile> = listOf(
        SharedFile(
            id = "f1",
            groupId = TEAM_GROUP_ID,
            uploaderId = PRIYA_ID,
            uploaderName = "Priya Nair",
            fileName = "proposal.pdf",
            mimeType = "application/pdf",
            sizeBytes = 482_133L,
            isPrivate = false,
            createdAt = now.minus(Duration.ofMinutes(12))
        )
    )

    private fun member(userId: String, name: String, role: GroupRole, joinedAt: Instant) = GroupMember(
        userId = userId,
        displayName = name,
        avatarUrl = null,
        role = role,
        joinedAt = joinedAt
    )

    private fun message(
        id: String,
        groupId: String,
        senderId: String,
        senderName: String,
        body: String,
        createdAt: Instant
    ) = ChatMessage(
        id = id,
        clientId = id,
        groupId = groupId,
        senderId = senderId,
        senderName = senderName,
        body = body,
        createdAt = createdAt,
        status = MessageStatus.Sent
    )
}
