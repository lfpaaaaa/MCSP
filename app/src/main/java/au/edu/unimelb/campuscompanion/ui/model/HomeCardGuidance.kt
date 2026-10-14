package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.ui.text.UiText
import au.edu.unimelb.campuscompanion.context.ContextState
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Text and visibility for the combined Home card; the engine's decision stays in [HomeCardUiState]. */
data class HomeCardGuidance(
    val cardLabel: UiText,
    val headline: UiText,
    val detail: UiText?,
    val departureLine: UiText?,
    val bufferExplanation: UiText?,
    val travelUnavailableMessage: UiText?,
    val showTravelDetails: Boolean,
    val showWeatherCredit: Boolean
)

private val guidanceTimeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val guidanceDateFormat = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)

/** Presents the supplied engine result without deriving another state or departure deadline. */
fun buildHomeCardGuidance(card: HomeCardUiState, now: ZonedDateTime): HomeCardGuidance {
    val session = card.session
    val label = when {
        session == null -> UiText(R.string.home_your_day)
        !now.isBefore(session.start) -> UiText(R.string.home_current_class)
        else -> UiText(R.string.label_next_class)
    }
    val state = card.state ?: return pendingGuidance(label, session != null)

    if (session == null) {
        return when (state) {
            ContextState.POST_CLASS -> guidanceWithoutTravel(
                label = label,
                headline = card.finishedSession?.code?.takeIf(String::isNotBlank)
                    ?.let { UiText(R.string.home_course_finished, it) } ?: UiText(R.string.home_class_finished)
            )
            ContextState.NO_UPCOMING_CLASS -> guidanceWithoutTravel(
                label = label,
                headline = UiText(R.string.home_no_more_today),
                detail = UiText(R.string.home_no_upcoming)
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
                    if (deadline.isAfter(now)) UiText(R.string.home_leave_in, guidanceDuration(now, deadline)) else UiText(R.string.home_leave_now)
                )
            }
        }
        ContextState.LONG_BREAK -> {
            val deadline = card.leaveBy
            when {
                deadline == null -> HomeGuidanceText(
                    UiText(R.string.home_break_before_class),
                    UiText.Joined(listOf(nextClassStart(session, now), UNAVAILABLE_DEPARTURE), " · ")
                )
                deadline.isAfter(now) -> HomeGuidanceText(UiText(R.string.home_free_for, guidanceDuration(now, deadline)))
                else -> HomeGuidanceText(UiText(R.string.home_leave_now))
            }
        }
        ContextState.LEAVE_NOW -> HomeGuidanceText(
            UiText(R.string.home_leave_now), UiText(R.string.departure_not_approaching)
        )
        ContextState.EN_ROUTE -> HomeGuidanceText(
            UiText(R.string.home_on_your_way),
            UiText.Joined(listOfNotNull(card.estimate?.let(::remainingTravel), classStartDetail(session, now)), " · ")
        )
        ContextState.RUNNING_LATE -> {
            if (now.isBefore(session.start)) {
                val arrival = card.estimate?.let { now.plusMinutes(it.durationMinutes.toLong()) }
                HomeGuidanceText(
                    UiText(R.string.home_leave_now),
                    if (arrival != null && arrival.isAfter(session.start)) {
                        UiText(R.string.home_late_arrival, guidanceDuration(session.start, arrival))
                    } else {
                        classStartDetail(session, now)
                    }
                )
            } else {
                HomeGuidanceText(
                    classStartCountdown(session, now),
                    card.estimate?.let(::remainingTravel) ?: UiText(R.string.home_not_at_building)
                )
            }
        }
        ContextState.ARRIVED -> HomeGuidanceText(
            classStartCountdown(session, now),
            card.building?.name?.takeIf(String::isNotBlank)?.let { UiText(R.string.home_at_building, it) }
        )
        ContextState.IN_CLASS -> HomeGuidanceText(UiText(R.string.home_in_class_until, guidanceTime(session.end, session)))
        ContextState.POST_CLASS -> HomeGuidanceText(nextClassStart(session, now))
        ContextState.NO_UPCOMING_CLASS -> HomeGuidanceText(UiText(R.string.home_no_more_today), nextClassStart(session, now))
    }

    val showTravel = state in TRAVEL_GUIDANCE_STATES
    val showDeparture = (state in DEPARTURE_GUIDANCE_STATES) && card.leaveBy != null
    val showBuffers = showDeparture && card.estimate != null
    val bufferParts = if (showBuffers) {
        buildList {
            if (card.leadMinutes > 0) add(UiText(R.string.context_home_arrival_buffer, card.leadMinutes))
            if (card.weatherBufferMinutes > 0) add(UiText(R.string.home_weather_allowance, card.weatherBufferMinutes))
            if (card.entryAllowanceMinutes > 0) add(UiText(R.string.home_entry_allowance, card.entryAllowanceMinutes))
        }
    } else {
        emptyList()
    }

    return HomeCardGuidance(
        cardLabel = label,
        headline = text.headline,
        detail = text.detail,
        departureLine = card.leaveBy?.takeIf { showDeparture }?.let { UiText(R.string.home_leave_by, guidanceTime(it, session)) },
        bufferExplanation = bufferParts.takeIf { it.isNotEmpty() }?.let { UiText.Joined(it, " + ") },
        travelUnavailableMessage = if (showTravel && card.estimate == null) {
            when {
                card.building == null -> UiText(R.string.home_unknown_destination)
                card.distanceMeters == null -> UiText(R.string.home_waiting_location)
                else -> UiText(R.string.home_route_unavailable)
            }
        } else {
            null
        },
        showTravelDetails = showTravel,
        showWeatherCredit = showBuffers && card.weatherBufferMinutes > 0
    )
}

private data class HomeGuidanceText(val headline: UiText, val detail: UiText? = null)

private fun pendingGuidance(label: UiText, hasSession: Boolean) = HomeCardGuidance(
    cardLabel = label,
    headline = when {
        !hasSession -> UiText(R.string.home_updating_day)
        label == UiText(R.string.home_current_class) -> UiText(R.string.home_updating_class)
        else -> UiText(R.string.home_checking_class)
    },
    detail = null,
    departureLine = null,
    bufferExplanation = null,
    travelUnavailableMessage = UiText(R.string.home_updating_travel),
    showTravelDetails = false,
    showWeatherCredit = false
)

private fun guidanceWithoutTravel(label: UiText, headline: UiText, detail: UiText? = null) = HomeCardGuidance(
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

private fun nextClassStart(session: CourseSession, now: ZonedDateTime): UiText {
    val localNow = now.withZoneSameInstant(session.start.zone)
    return if (session.startDate == localNow.toLocalDate()) {
        UiText(R.string.home_next_starts_at, guidanceTime(session.start, session))
    } else {
        UiText(R.string.home_next_starts_on, session.start.format(guidanceDateFormat), guidanceTime(session.start, session))
    }
}

private fun classStartCountdown(session: CourseSession, now: ZonedDateTime): UiText = when {
    session.start.isAfter(now) -> UiText(R.string.home_starts_in, guidanceDuration(now, session.start))
    session.start.toInstant() == now.toInstant() -> UiText(R.string.home_starts_now)
    else -> UiText(R.string.home_started_ago, guidanceElapsedDuration(session.start, now))
}

private fun classStartDetail(session: CourseSession, now: ZonedDateTime): UiText =
    if (session.start.isAfter(now)) UiText(R.string.home_starts_at, guidanceTime(session.start, session))
    else classStartCountdown(session, now)

private fun remainingTravel(estimate: TravelEstimate): UiText =
    if (estimate.durationMinutes == 0) UiText(R.string.home_remaining_under_minute)
    else UiText(R.string.home_remaining_minutes, estimate.durationMinutes)

/** Positive fractional minutes round up, including a fraction smaller than one second. */
private fun guidanceDuration(from: ZonedDateTime, to: ZonedDateTime): UiText {
    val duration = Duration.between(from, to)
    val minutes = if (duration.isNegative || duration.isZero) {
        0L
    } else {
        duration.seconds / 60 + if (duration.seconds % 60 != 0L || duration.nano > 0) 1 else 0
    }
    return guidanceMinutes(minutes)
}

/** Elapsed time rounds down: thirty seconds after the start is still less than a minute ago. */
private fun guidanceElapsedDuration(from: ZonedDateTime, to: ZonedDateTime): UiText =
    guidanceMinutes(Duration.between(from, to).toMinutes().coerceAtLeast(0))

private fun guidanceMinutes(minutes: Long): UiText {
    if (minutes == 0L) return UiText(R.string.home_under_minute)
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return UiText.Joined(buildList {
        if (hours > 0) add(UiText.Plural(R.plurals.home_hours, hours.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), listOf(hours)))
        if (remainingMinutes > 0) add(UiText.Plural(R.plurals.home_minutes, remainingMinutes.toInt(), listOf(remainingMinutes)))
    })
}

private val UNAVAILABLE_DEPARTURE = UiText(R.string.home_departure_unavailable)

private val DEPARTURE_GUIDANCE_STATES = setOf(
    ContextState.UPCOMING,
    ContextState.LONG_BREAK,
    ContextState.LEAVE_SOON,
    ContextState.LEAVE_NOW,
    ContextState.RUNNING_LATE
)

private val TRAVEL_GUIDANCE_STATES = DEPARTURE_GUIDANCE_STATES + ContextState.EN_ROUTE
