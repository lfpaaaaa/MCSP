package au.edu.unimelb.campuscompanion.data.remote

import au.edu.unimelb.campuscompanion.data.model.Group
import au.edu.unimelb.campuscompanion.data.model.GroupInvite
import au.edu.unimelb.campuscompanion.data.model.GroupMember
import au.edu.unimelb.campuscompanion.data.model.GroupRole
import au.edu.unimelb.campuscompanion.data.model.GroupSummary
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

/** A row of `public.groups`, as returned by `create_group` and `join_group_with_token`. */
@Serializable
data class GroupRow(
    val id: String,
    val name: String,
    @SerialName("course_code") val courseCode: String? = null,
    @SerialName("private_content_enabled") val privateContentEnabled: Boolean = false,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_at") val createdAt: String
)

/** A row returned by `my_group_summaries`. */
@Serializable
data class GroupSummaryRow(
    val id: String,
    val name: String,
    @SerialName("course_code") val courseCode: String? = null,
    @SerialName("private_content_enabled") val privateContentEnabled: Boolean = false,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("my_role") val myRole: String,
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("latest_message_preview") val latestMessagePreview: String? = null,
    @SerialName("latest_activity_at") val latestActivityAt: String? = null,
    @SerialName("latest_file_name") val latestFileName: String? = null
)

/** A row returned by `group_members`. */
@Serializable
data class GroupMemberRow(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val role: String,
    @SerialName("joined_at") val joinedAt: String
)

/** A row returned by `create_group_invite`. The server returns each token only once. */
@Serializable
data class InviteRow(
    val token: String,
    @SerialName("expires_at") val expiresAt: String
)

fun GroupRow.toModel(): Group = Group(
    id = id,
    name = name,
    courseCode = courseCode,
    privateContentEnabled = privateContentEnabled,
    createdBy = createdBy,
    createdAt = parseTimestamp(createdAt)
)

fun GroupSummaryRow.toModel(): GroupSummary = GroupSummary(
    group = Group(
        id = id,
        name = name,
        courseCode = courseCode,
        privateContentEnabled = privateContentEnabled,
        createdBy = createdBy,
        createdAt = parseTimestamp(createdAt)
    ),
    myRole = parseRole(myRole),
    memberCount = memberCount,
    unreadCount = unreadCount,
    latestMessagePreview = latestMessagePreview,
    latestActivityAt = latestActivityAt?.let { parseTimestamp(it) },
    latestFileName = latestFileName
)

fun GroupMemberRow.toModel(): GroupMember = GroupMember(
    userId = userId,
    displayName = displayName,
    avatarUrl = avatarUrl,
    role = parseRole(role),
    joinedAt = parseTimestamp(joinedAt)
)

fun InviteRow.toModel(groupId: String): GroupInvite = GroupInvite(
    groupId = groupId,
    token = token,
    expiresAt = parseTimestamp(expiresAt)
)

/** Parses a Postgres `timestamptz` value such as `2026-09-25T05:58:02.449382+00:00`. */
internal fun parseTimestamp(value: String): Instant = OffsetDateTime.parse(value).toInstant()

internal fun parseRole(value: String): GroupRole =
    if (value == "owner") GroupRole.Owner else GroupRole.Member
