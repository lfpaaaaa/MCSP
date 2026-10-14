package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.data.model.Group
import au.edu.unimelb.campuscompanion.data.model.GroupRole
import au.edu.unimelb.campuscompanion.data.model.GroupSummary
import java.time.Instant

/** Use server creation time for every presentation of the original five-minute code. */
internal fun Group.toCourseGroup(currentUserId: String, memberCount: Int): CourseGroup = CourseGroup(
    id = id, courseCode = courseCode.orEmpty(), name = name, members = memberCount,
    unreadCount = 0, latestMessage = "No messages yet.", latestFileName = null,
    origin = when {
        timetableKey != null -> GroupOrigin.Timetable
        createdBy == currentUserId -> GroupOrigin.CreatedByUser
        else -> GroupOrigin.Joined
    },
    joinCode = joinCode, joinCodeExpiresAt = groupJoinCodeExpiresAt(createdAt),
    timetableKey = timetableKey, timetableSlot = timetableSlot
)

internal fun GroupSummary.toCourseGroup(currentUserId: String): CourseGroup = CourseGroup(
    id = group.id, courseCode = group.courseCode.orEmpty(),
    name = if (group.timetableKey != null) group.name.trimTrailingCommas() else group.name,
    members = memberCount, unreadCount = unreadCount,
    latestMessage = latestMessagePreview ?: "No messages yet.", latestFileName = latestFileName,
    origin = when {
        group.timetableKey != null -> GroupOrigin.Timetable
        myRole == GroupRole.Owner -> GroupOrigin.CreatedByUser
        else -> GroupOrigin.Joined
    },
    joinCode = group.joinCode, joinCodeExpiresAt = groupJoinCodeExpiresAt(group.createdAt),
    timetableKey = group.timetableKey, timetableSlot = group.timetableSlot
)

/** Older saved state has no expiry; malformed saved values are unavailable, never renewed. */
internal fun restoreJoinCodeExpiresAt(value: String?): Instant? =
    value?.let { runCatching { Instant.parse(it) }.getOrNull() }
