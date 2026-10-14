package au.edu.unimelb.campuscompanion.ui.components

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.res.stringResource
import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.ui.model.trimTrailingCommas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.GroupOrigin
import au.edu.unimelb.campuscompanion.ui.model.SessionStatus
import au.edu.unimelb.campuscompanion.ui.model.HomeCardUiState
import au.edu.unimelb.campuscompanion.ui.model.forSession
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val dateFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

@Composable
fun SectionHeader(
    title: String,
    actionLabel: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (actionLabel != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    status: SessionStatus,
    modifier: Modifier = Modifier
) {
    val container = when (status) {
        SessionStatus.LeaveSoon -> MaterialTheme.colorScheme.tertiaryContainer
        SessionStatus.EnRoute -> MaterialTheme.colorScheme.primaryContainer
        SessionStatus.Arrived -> MaterialTheme.colorScheme.secondaryContainer
        SessionStatus.InProgress -> MaterialTheme.colorScheme.secondaryContainer
        SessionStatus.Finished -> MaterialTheme.colorScheme.surfaceVariant
        SessionStatus.Upcoming -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (status) {
        SessionStatus.LeaveSoon -> MaterialTheme.colorScheme.onTertiaryContainer
        SessionStatus.EnRoute -> MaterialTheme.colorScheme.onPrimaryContainer
        SessionStatus.Arrived -> MaterialTheme.colorScheme.onSecondaryContainer
        SessionStatus.InProgress -> MaterialTheme.colorScheme.onSecondaryContainer
        SessionStatus.Finished -> MaterialTheme.colorScheme.onSurfaceVariant
        SessionStatus.Upcoming -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(100),
        color = container,
        contentColor = content
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun MetricItem(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun CourseSessionRow(
    session: CourseSession,
    onNavigate: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    homeCard: HomeCardUiState? = null,
    now: ZonedDateTime = ZonedDateTime.now(session.start.zone)
) {
    val textResources = textResources()
    val guidance = homeCard?.forSession(session)
    val status = guidance?.statusTone ?: session.statusAt(now)
    val today = now.withZoneSameInstant(session.start.zone).toLocalDate()
    val dateLabel = when (session.startDate) {
        today -> textResources.getString(R.string.campus_components_today)
        today.plusDays(1) -> textResources.getString(R.string.campus_components_tomorrow)
        else -> session.startDate.format(dateFormatter)
    }
    val locationText = listOf(session.location, session.room)
        .filter(String::isNotBlank)
        .joinToString(" - ")

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(84.dp)
            ) {
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = session.startTime.format(timeFormatter),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = session.endTime.format(timeFormatter),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.code,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusPill(
                        label = guidance?.statusLabel?.resolve(textResources) ?: status.displayName(),
                        status = status
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = session.title.trimTrailingCommas(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = locationText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        onNavigate?.let { action ->
            androidx.compose.material3.TextButton(onClick = action, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(textResources.getString(R.string.campus_components_navigate_with_google_maps))
            }
        }
    }
}

@Composable
fun GroupUpdateRow(
    group: CourseGroup,
    onClick: () -> Unit,
    muted: Boolean = false,
    modifier: Modifier = Modifier
) {
    val textResources = textResources()
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics { onClick(label = textResources.getString(R.string.campus_components_open_group), action = null) },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (group.name == "${group.courseCode}-tutorial" ||
                            group.name == "${group.courseCode}-workshop") group.name
                        else listOf(group.courseCode, group.name)
                            .filter(String::isNotBlank)
                            .joinToString(" "),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            group.members > 0 -> textResources.getQuantityString(R.plurals.member_count, group.members, group.members)
                            group.origin == GroupOrigin.Timetable -> textResources.getString(R.string.campus_components_detected_from_timetable)
                            group.origin == GroupOrigin.CreatedByUser -> textResources.getString(R.string.campus_components_created_by_you)
                            else -> textResources.getString(R.string.campus_components_joined_group)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (group.unreadCount > 0) {
                    StatusPill(
                        label = textResources.getQuantityString(R.plurals.unread_count, group.unreadCount, group.unreadCount),
                        status = SessionStatus.EnRoute
                    )
                }
                if (muted) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Outlined.NotificationsOff,
                        contentDescription = textResources.getString(R.string.campus_components_muted),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            group.timetableSlot?.let { slot ->
                Text(slot, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            IconTextLine(
                icon = Icons.Outlined.ChatBubbleOutline,
                text = group.latestMessage
            )
            if (group.latestFileName != null) {
                Spacer(Modifier.height(8.dp))
                IconTextLine(
                    icon = Icons.Outlined.AttachFile,
                    text = group.latestFileName
                )
            }
        }
    }
}

@Composable
fun IconTextLine(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun QuickActionChip(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    AssistChip(
        modifier = modifier,
        onClick = onClick,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        label = { Text(label) }
    )
}

@Composable
fun SessionStatus.displayName(): String = when (this) {
    SessionStatus.Upcoming -> stringResource(R.string.label_upcoming)
    SessionStatus.LeaveSoon -> stringResource(R.string.label_leave_soon)
    SessionStatus.EnRoute -> stringResource(R.string.label_en_route)
    SessionStatus.Arrived -> stringResource(R.string.label_arrived)
    SessionStatus.InProgress -> stringResource(R.string.label_in_progress)
    SessionStatus.Finished -> stringResource(R.string.label_finished)
}

object CampusIcons {
    val Time = Icons.Outlined.AccessTime
    val Location = Icons.Outlined.LocationOn
    val Navigate = Icons.Outlined.NearMe
    val People = Icons.Outlined.People
}
