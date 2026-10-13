package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.context.ContextState
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Text and visibility for the combined Home card; the engine's decision stays in [HomeCardUiState]. */
data class HomeCardGuidance(
    val cardLabel: String,
    val headline: String,
    val detail: String?,
    val departureLine: String?,
    val bufferExplanation: String?,
    val travelUnavailableMessage: String?,
    val showTravelDetails: Boolean,
    val showWeatherCredit: Boolean
)

private val guidanceTimeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val guidanceDateFormat = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)

/** Presents the supplied engine result without deriving another state or departure deadline. */
fun buildHomeCardGuidance(card: HomeCardUiState, now: ZonedDateTime): HomeCardGuidance {
    val session = card.session
    val label = when {
        session == null -> "YOUR DAY"
        !now.isBefore(session.start) -> "CURRENT CLASS"
        else -> "NEXT CLASS"
    }
    val state = card.state ?: return pendingGuidance(label, session != null)

    if (session == null) {
        return when (state) {
            ContextState.POST_CLASS -> guidanceWithoutTravel(
                label = label,
                headline = card.finishedSession?.code?.takeIf(String::isNotBlank)
                    ?.let { "$it just finished" } ?: "Class just finished"
            )
            ContextState.NO_UPCOMING_CLASS -> guidanceWithoutTravel(
                label = label,
                headline = "No more classes today",
                detail = "Your timetable has no upcoming classes."
            )
            else -> pendingGuidance(label, hasSession = false)
        }
    }

    val text = when (state) {
        ContextState.UPCOMING, ContextState.LEAVE_SOON -> {
            val deadline = card.leaveBy
            if (deadline == null) {
                HomeGuidanceText(classStartCountdown(session, now), UNAVAILABLE_DEPARTURE)
            } else {
                HomeGuidanceText(
                    if (deadline.isAfter(now)) "Leave in ${guidanceDuration(now, deadline)}" else "Leave now"
                )
            }
        }
        ContextState.LONG_BREAK -> {
            val deadline = card.leaveBy
            when {
                deadline == null -> HomeGuidanceText(
                    "A break before your next class",
                    "${nextClassStart(session, now)} · $UNAVAILABLE_DEPARTURE"
                )
                deadline.isAfter(now) -> HomeGuidanceText("Free for ${guidanceDuration(now, deadline)}")
                else -> HomeGuidanceText("Leave now")
            }
        }
        ContextState.EN_ROUTE -> HomeGuidanceText(
            "On your way",
            listOfNotNull(card.estimate?.let(::remainingTravel), classStartDetail(session, now))
                .joinToString(" · ")
        )
        ContextState.RUNNING_LATE -> {
            if (now.isBefore(session.start)) {
                val arrival = card.estimate?.let { now.plusMinutes(it.durationMinutes.toLong()) }
                HomeGuidanceText(
                    "Leave now",
                    if (arrival != null && arrival.isAfter(session.start)) {
                        "You may arrive about ${guidanceDuration(session.start, arrival)} after class starts."
                    } else {
                        classStartDetail(session, now)
                    }
                )
            } else {
                HomeGuidanceText(
                    classStartCountdown(session, now),
                    card.estimate?.let(::remainingTravel) ?: "You're not at the building yet."
                )
            }
        }
        ContextState.ARRIVED -> HomeGuidanceText(
            classStartCountdown(session, now),
            card.building?.name?.takeIf(String::isNotBlank)?.let { "You're at $it" }
        )
        ContextState.IN_CLASS -> HomeGuidanceText("In class until ${guidanceTime(session.end, session)}")
        ContextState.POST_CLASS -> HomeGuidanceText(nextClassStart(session, now))
        ContextState.NO_UPCOMING_CLASS -> HomeGuidanceText("No more classes today", nextClassStart(session, now))
    }

    val showTravel = state in TRAVEL_GUIDANCE_STATES
    val showDeparture = state in DEPARTURE_GUIDANCE_STATES && card.leaveBy != null
    val showBuffers = showDeparture && card.estimate != null
    val bufferParts = if (showBuffers) {
        buildList {
            if (card.leadMinutes > 0) add("${card.leadMinutes} min arrival buffer")
            if (card.weatherBufferMinutes > 0) add("${card.weatherBufferMinutes} min for weather")
            if (card.entryAllowanceMinutes > 0) add("${card.entryAllowanceMinutes} min to reach the room")
        }
    } else {
        emptyList()
    }

    return HomeCardGuidance(
        cardLabel = label,
        headline = text.headline,
        detail = text.detail,
        departureLine = card.leaveBy?.takeIf { showDeparture }?.let { "Leave by ${guidanceTime(it, session)}" },
        bufferExplanation = bufferParts.takeIf { it.isNotEmpty() }?.joinToString(" + "),
        travelUnavailableMessage = if (showTravel && card.estimate == null) {
            when {
                card.building == null -> "Travel time unavailable: destination could not be matched."
                card.distanceMeters == null -> "Waiting for your current location."
                else -> "Travel time unavailable right now."
            }
        } else {
            null
        },
        showTravelDetails = showTravel,
        showWeatherCredit = showBuffers && card.weatherBufferMinutes > 0
    )
}

private data class HomeGuidanceText(val headline: String, val detail: String? = null)

private fun pendingGuidance(label: String, hasSession: Boolean) = HomeCardGuidance(
    cardLabel = label,
    headline = when {
        !hasSession -> "Updating your day"
        label == "CURRENT CLASS" -> "Updating class guidance"
        else -> "Checking your next class"
    },
    detail = null,
    departureLine = null,
    bufferExplanation = null,
    travelUnavailableMessage = "Updating travel information",
    showTravelDetails = false,
    showWeatherCredit = false
)

private fun guidanceWithoutTravel(label: String, headline: String, detail: String? = null) = HomeCardGuidance(
    cardLabel = label,
    headline = headline,
    detail = detail,
    departureLine = null,
    bufferExplanation = null,
    travelUnavailableMessage = null,
    showTravelDetails = false,
    showWeatherCredit = false
)

private fun guidanceTime(time: ZonedDateTime, session: CourseSession): String =
    time.withZoneSameInstant(session.start.zone).format(guidanceTimeFormat)

private fun nextClassStart(session: CourseSession, now: ZonedDateTime): String {
    val localNow = now.withZoneSameInstant(session.start.zone)
    return if (session.startDate == localNow.toLocalDate()) {
        "Next class starts at ${guidanceTime(session.start, session)}"
    } else {
        "Next class starts on ${session.start.format(guidanceDateFormat)} at ${guidanceTime(session.start, session)}"
    }
}

private fun classStartCountdown(session: CourseSession, now: ZonedDateTime): String = when {
    session.start.isAfter(now) -> "Class starts in ${guidanceDuration(now, session.start)}"
    session.start.toInstant() == now.toInstant() -> "Class starts now"
    else -> "Class started ${guidanceElapsedDuration(session.start, now)} ago"
}

private fun classStartDetail(session: CourseSession, now: ZonedDateTime): String =
    if (session.start.isAfter(now)) "Class starts at ${guidanceTime(session.start, session)}"
    else classStartCountdown(session, now)

private fun remainingTravel(estimate: TravelEstimate): String =
    if (estimate.durationMinutes == 0) "Less than a minute remaining"
    else "About ${estimate.durationMinutes} min remaining"

/** Positive fractional minutes round up, including a fraction smaller than one second. */
private fun guidanceDuration(from: ZonedDateTime, to: ZonedDateTime): String {
    val duration = Duration.between(from, to)
    val minutes = if (duration.isNegative || duration.isZero) {
        0L
    } else {
        duration.seconds / 60 + if (duration.seconds % 60 != 0L || duration.nano > 0) 1 else 0
    }
    return guidanceMinutes(minutes)
}

/** Elapsed time rounds down: thirty seconds after the start is still less than a minute ago. */
private fun guidanceElapsedDuration(from: ZonedDateTime, to: ZonedDateTime): String =
    guidanceMinutes(Duration.between(from, to).toMinutes().coerceAtLeast(0))

private fun guidanceMinutes(minutes: Long): String {
    if (minutes == 0L) return "less than a minute"
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return buildList {
        if (hours > 0) add("$hours ${if (hours == 1L) "hour" else "hours"}")
        if (remainingMinutes > 0) add("$remainingMinutes ${if (remainingMinutes == 1L) "minute" else "minutes"}")
    }.joinToString(" ")
}

private const val UNAVAILABLE_DEPARTURE = "Departure time is unavailable."

private val DEPARTURE_GUIDANCE_STATES = setOf(
    ContextState.UPCOMING,
    ContextState.LONG_BREAK,
    ContextState.LEAVE_SOON,
    ContextState.RUNNING_LATE
)

private val TRAVEL_GUIDANCE_STATES = DEPARTURE_GUIDANCE_STATES + ContextState.EN_ROUTE
