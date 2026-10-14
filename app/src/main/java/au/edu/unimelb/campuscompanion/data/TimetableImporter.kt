package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.context.ContextConfig
import au.edu.unimelb.campuscompanion.ui.model.trimTrailingCommas
import au.edu.unimelb.campuscompanion.data.model.TimetableGroupSpec
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Period
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.TimeZone as IcalTimeZone
import net.fortuna.ical4j.model.TimeZoneRegistry
import net.fortuna.ical4j.model.TimeZoneRegistryImpl
import net.fortuna.ical4j.model.TemporalAmountAdapter
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.ExDate
import net.fortuna.ical4j.model.property.ExRule
import net.fortuna.ical4j.model.property.RDate
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.RecurrenceId
import net.fortuna.ical4j.model.property.Duration as IcalDuration
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.Temporal
import java.time.temporal.TemporalAmount
import java.time.zone.ZoneRules
import java.util.concurrent.ConcurrentHashMap

/**
 * What a calendar parsed into: recent post-class and upcoming sessions, the course groups they imply
 * and how many events the calendar held.
 */
data class TimetableImport(
    val sessions: List<CourseSession>,
    val groups: List<CourseGroup>,
    val sourceEventCount: Int
)

/** An original occurrence needed to verify a still-upcoming local edit against a new calendar. */
data class RetainedOccurrence(
    val importedId: String,
    val originalStart: ZonedDateTime
)

/**
 * Downloads a MyTimetable calendar subscription and turns it into a [TimetableImport]. [close]
 * releases the HTTP client.
 */
class TimetableImporter(
    private val client: HttpClient = defaultHttpClient(),
    private val parser: IcsTimetableParser = IcsTimetableParser()
) : AutoCloseable {

    /** Downloads and parses the calendar at [url]. */
    suspend fun importFromUrl(url: String): Result<TimetableImport> =
        download(url).fold(onSuccess = { bytes -> parse(bytes) }, onFailure = { Result.failure(it) })

    /**
     * Downloads the calendar at [url] and checks that it is an iCalendar file of a sensible size.
     * Only the parsed classes and metadata are stored for offline use.
     */
    suspend fun download(url: String): Result<ByteArray> {
        return try {
            val response = client.get(url) {
                header(HttpHeaders.Accept, "text/calendar, application/calendar+json;q=0.8, */*;q=0.2")
                header(HttpHeaders.UserAgent, "CampusCompanion/0.1")
            }

            if (!response.status.isSuccess()) {
                throw TimetableImportException(
                    "The timetable server returned HTTP ${response.status.value}. Check the URL and try again."
                )
            }

            val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredLength != null && declaredLength > MAX_CALENDAR_BYTES) {
                throw TimetableImportException("This calendar is too large to import.")
            }

            val bytes: ByteArray = response.body()
            if (bytes.size > MAX_CALENDAR_BYTES) {
                throw TimetableImportException("This calendar is too large to import.")
            }
            if (!bytes.toString(Charsets.UTF_8).contains("BEGIN:VCALENDAR", ignoreCase = true)) {
                throw TimetableImportException(
                    "This URL did not return an iCalendar file. Use the private subscription URL, not the timetable web page."
                )
            }
            Result.success(bytes)
        } catch (error: CancellationException) {
            throw error
        } catch (error: TimetableImportException) {
            Result.failure(error)
        } catch (error: HttpRequestTimeoutException) {
            Result.failure(
                TimetableImportException(
                    "The timetable server did not respond in time. Check your connection and try again."
                )
            )
        } catch (error: Exception) {
            Result.failure(
                TimetableImportException(
                    "The timetable could not be downloaded or read. Check your connection and subscription URL.",
                    error
                )
            )
        }
    }

    /** Parses newly downloaded calendar [bytes], including exact originals needed by local edits. */
    suspend fun parse(
        bytes: ByteArray,
        retainedOccurrences: List<RetainedOccurrence> = emptyList()
    ): Result<TimetableImport> {
        return try {
            val imported = withContext(Dispatchers.Default) {
                parser.parse(
                    bytes = bytes,
                    now = ZonedDateTime.now(),
                    displayZone = ZoneId.systemDefault(),
                    retainedOccurrences = retainedOccurrences
                )
            }
            Result.success(imported)
        } catch (error: CancellationException) {
            throw error
        } catch (error: TimetableImportException) {
            Result.failure(error)
        } catch (error: Exception) {
            Result.failure(
                TimetableImportException("The timetable could not be read. Check your subscription URL.", error)
            )
        }
    }

    override fun close() {
        client.close()
    }

    companion object {
        private const val MAX_CALENDAR_BYTES = 2 * 1024 * 1024L

        private fun defaultHttpClient() = HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 20_000
            }
            followRedirects = true
        }
    }
}

/**
 * Parses an iCalendar timetable into the sessions from the day before [parse]'s `now` to six
 * months after it, expanding recurring events and showing them in the given display zone.
 * Exact retained originals can additionally come from an earlier expansion range; they do not
 * consume the upcoming-session allowance or contribute slot groups.
 */
class IcsTimetableParser(
    private val postClassMinutes: Int = ContextConfig().postClassMinutes
) {
    fun parse(
        bytes: ByteArray,
        now: ZonedDateTime,
        displayZone: ZoneId,
        retainedOccurrences: List<RetainedOccurrence> = emptyList()
    ): TimetableImport {
        val calendar = try {
            CalendarBuilder(AndroidTimeZoneRegistry()).build(ByteArrayInputStream(bytes))
        } catch (error: LinkageError) {
            throw TimetableImportException(
                "This calendar uses a time-zone format that is not supported on this device.",
                error
            )
        } catch (error: Exception) {
            throw TimetableImportException("The downloaded file is not a valid iCalendar timetable.", error)
        }

        val allEvents = calendar.componentList.all.filterIsInstance<VEvent>()
        val events = allEvents
            .filterNot { it.getStatus()?.value.equals("CANCELLED", ignoreCase = true) }
        val retainedIds = retainedOccurrences.mapTo(hashSetOf()) { it.importedId }
        // A recurrence exception supersedes its original instant even if expanding the master
        // event still produces it. Only retained IDs use this additional verification, so normal
        // imports keep their established output until general exception support is introduced.
        val supersededRetainedIds = allEvents.mapNotNull { event ->
            val recurrenceStart = event.getProperty<RecurrenceId<Temporal>>(Property.RECURRENCE_ID)
                .orElse(null)?.date?.toDisplayTime(displayZone) ?: return@mapNotNull null
            val uid = event.uid.orElse(null)?.value ?: return@mapNotNull null
            val originalId = "$uid@${recurrenceStart.toInstant().toEpochMilli()}"
            if (originalId !in retainedIds) return@mapNotNull null
            val changedStart = event.getProperty<DtStart<Temporal>>(Property.DTSTART)
                .orElse(null)?.date?.toDisplayTime(displayZone)
            originalId.takeIf {
                event.getStatus()?.value.equals("CANCELLED", ignoreCase = true) ||
                    changedStart != null && changedStart.toInstant() != recurrenceStart.toInstant()
            }
        }.toSet()

        val courseTitles = linkedMapOf<String, String>()
        events.map(::detailsFor).forEach { details ->
            if (details.code != FALLBACK_EVENT_CODE &&
                (courseTitles[details.code] == null || courseTitles[details.code] == details.code)) {
                courseTitles[details.code] = details.title
            }
        }
        val normalRangeStart = now.minusDays(1)
        val rangeEnd = now.plusMonths(6)
        val earliestRetained = retainedOccurrences.minByOrNull { it.originalStart.toInstant() }
            ?.originalStart
        // Include an occurrence exactly at the retained lower boundary even when recurrence
        // expansion treats the range start as exclusive. Floating/all-day events need this too.
        val rangeStart = if (earliestRetained != null &&
            !earliestRetained.toInstant().isAfter(normalRangeStart.toInstant())) {
            earliestRetained.withZoneSameInstant(now.zone).minusSeconds(1)
        } else normalRangeStart
        val expandedSessions = events.flatMapIndexed { index, event ->
            val eventDetails = detailsFor(event)
            if (eventDetails.code != FALLBACK_EVENT_CODE) {
                courseTitles.putIfAbsent(eventDetails.code, eventDetails.title)
            }

            occurrencesFor(event, rangeStart, rangeEnd, normalRangeStart, earliestRetained).mapNotNull { occurrence ->
                val start = occurrence.start.toDisplayTime(displayZone) ?: return@mapNotNull null
                val rawEnd = occurrence.end.toDisplayTime(displayZone) ?: return@mapNotNull null
                val end = if (rawEnd.isAfter(start)) rawEnd else start.plusHours(1)
                val uid = event.uid.map { it.value }.orElse("event-$index")

                val session = CourseSession(
                    id = "$uid@${start.toInstant().toEpochMilli()}",
                    code = eventDetails.code,
                    title = eventDetails.title,
                    location = eventDetails.location,
                    room = eventDetails.room,
                    start = start,
                    end = end,
                    activity = eventDetails.activity.takeIf {
                        rawEnd.isAfter(start) && event.getProperty<DtStart<Temporal>>(Property.DTSTART)
                            .orElse(null)?.date !is LocalDate
                    }
                )
                session to occurrence.isInNormalRange
            }
        }
        val occurrences = expandedSessions
            .filter { retainedOccurrences.isEmpty() || it.second }
            .map { it.first }
            .distinctBy { it.id }
            .sortedBy { it.start.toInstant() }
        val nowInstant = now.toInstant()
        // Recent history must not consume the existing upcoming quota or create slot memberships.
        val upcoming = occurrences.filter { it.end.toInstant().isAfter(nowInstant) }
            .take(MAX_IMPORTED_SESSIONS)
        val recent = occurrences.filter {
            !it.end.toInstant().isAfter(nowInstant) && it.isRetainedForContext(nowInstant, postClassMinutes)
        }
        val sessions = (recent + upcoming).sortedBy { it.start.toInstant() }

        val specs = courseTitles.map { (code, title) -> TimetableGroupSpec(code, title.take(60), "course") } +
            upcoming.mapNotNull { session ->
                session.copy(title = courseTitles[session.code] ?: session.title).timetableGroupSpec()
            }.distinctBy { it.key }
        val groups = specs.map { spec ->
            CourseGroup(
                id = spec.key,
                courseCode = spec.courseCode,
                name = spec.name,
                members = 0,
                unreadCount = 0,
                latestMessage = "Added from your connected timetable.",
                latestFileName = null,
                timetableKey = spec.key,
                timetableSlot = spec.slotLabel(),
                timetableSpec = spec
            )
        }

        return TimetableImport(
            sessions = if (retainedOccurrences.isEmpty()) sessions else {
                (sessions + expandedSessions.map { it.first }.filter { it.id in retainedIds })
                    .filterNot { it.id in supersededRetainedIds }
                    .distinctBy { it.id }
                    .sortedBy { it.start.toInstant() }
            },
            groups = groups,
            sourceEventCount = events.size
        )
    }

    private fun detailsFor(event: VEvent): EventDetails {
        val summary = event.getSummary()?.value?.trim().orEmpty().ifBlank { "Class" }
        val description = event.getDescription()?.value.orEmpty()
        val code = COURSE_CODE_REGEX.find("$summary $description")
            ?.value
            ?.replace(" ", "")
            ?.uppercase()
            ?: FALLBACK_EVENT_CODE
        // Prefer an explicit activity field, then an activity suffix/prefix in the summary.
        val activity = ACTIVITY_FIELD.find(description)?.groupValues?.get(1)?.lowercase()
            ?: ACTIVITY_SUFFIX.find(summary)?.groupValues?.get(1)?.lowercase()
            ?: ACTIVITY_PREFIX.find(summary.replace(COURSE_CODE_REGEX, "").trim(' ', '-', ':', '|', '/', '_'))
                ?.groupValues?.get(1)?.lowercase()
        val subjectName = SUBJECT_NAME.find(description)?.groupValues?.get(1)?.trim()
        val cleanedTitle = (subjectName ?: summary)
            .replace(ACTIVITY_SUFFIX, "")
            .replace(ACTIVITY_PREFIX, "")
            .replace(COURSE_CODE_REGEX, "")
            .replace('_', ' ')
            .trim(' ', '-', ':', '|', '/')
            .replace(ACTIVITY_PREFIX, "")
            .trim(' ', '-', ':', '|', '/')
            .trimTrailingCommas()
            .ifBlank { code }

        val locationParts = event.getLocation()?.value
            .orEmpty()
            .split(Regex("[,\\n]"))
            .map(String::trim)
            .filter(String::isNotBlank)

        return EventDetails(
            code = code,
            title = cleanedTitle,
            location = locationParts.firstOrNull() ?: "Location not provided",
            room = locationParts.drop(1).joinToString(", "),
            activity = activity
        )
    }

    private fun occurrencesFor(
        event: VEvent,
        rangeStart: ZonedDateTime,
        rangeEnd: ZonedDateTime,
        normalRangeStart: ZonedDateTime,
        retainedOriginalStart: ZonedDateTime?
    ): List<Occurrence> {
        val eventStart = event.getProperty<DtStart<Temporal>>(Property.DTSTART)
            .orElse(null)
            ?.date
            ?: return emptyList()
        return when (eventStart) {
            is ZonedDateTime -> calculateOccurrences(
                event,
                rangeStart.withZoneSameInstant(eventStart.zone),
                rangeEnd.withZoneSameInstant(eventStart.zone),
                normalRangeStart.withZoneSameInstant(eventStart.zone)
            )
            is OffsetDateTime -> calculateOccurrences(
                event,
                rangeStart.toOffsetDateTime(),
                rangeEnd.toOffsetDateTime(),
                normalRangeStart.toOffsetDateTime()
            )
            is Instant -> calculateOccurrences(event, rangeStart.toInstant(), rangeEnd.toInstant(),
                normalRangeStart.toInstant())
            is LocalDateTime -> {
                // Floating campus dates are interpreted in Melbourne, while the established
                // normal window uses the device's wall clock. A retained instant can precede
                // that wall-clock boundary even when it is inside the normal instant window.
                val floatingRetainedStart = retainedOriginalStart
                    ?.withZoneSameInstant(ZoneId.of("Australia/Melbourne"))
                    ?.toLocalDateTime()?.minusSeconds(1)
                val floatingRangeStart = floatingRetainedStart?.let {
                    minOf(normalRangeStart.toLocalDateTime(), it)
                } ?: rangeStart.toLocalDateTime()
                calculateOccurrences(
                    event,
                    floatingRangeStart,
                    rangeEnd.toLocalDateTime(),
                    normalRangeStart.toLocalDateTime()
                )
            }
            is LocalDate -> calculateOccurrences(event, rangeStart.toLocalDate(), rangeEnd.toLocalDate(),
                normalRangeStart.toLocalDate())
            else -> emptyList()
        }
    }

    private fun <T : Temporal> calculateOccurrences(
        event: VEvent,
        rangeStart: T,
        rangeEnd: T,
        normalRangeStart: T
    ): List<Occurrence> {
        val range = Period(rangeStart, rangeEnd)
        val normalRange = Period(normalRangeStart, rangeEnd)
        // RDATE values are admitted by their start, while rule/initial occurrences can overlap
        // the lower boundary. Track those contributions during one expansion when widening;
        // otherwise an old, long RDATE could unexpectedly consume the ordinary quota/groups.
        if (rangeStart != normalRangeStart && event.getProperties<RDate<T>>(Property.RDATE).isNotEmpty()) {
            return calculateWidenedOccurrencesWithDates(event, range, normalRange)
        }
        return event.calculateRecurrenceSet<T>(range)
            .map { period ->
                Occurrence(period.start, period.end,
                    normalRange.includes(period.start) || normalRange.intersects(period))
            }
    }

    private fun <T : Temporal> calculateWidenedOccurrencesWithDates(
        event: VEvent,
        range: Period<T>,
        normalRange: Period<T>
    ): List<Occurrence> {
        val start = event.getProperty<DtStart<T>>(Property.DTSTART).orElse(null)?.date
            ?: return emptyList()
        val end = event.getProperty<DtEnd<T>>(Property.DTEND).orElse(null)?.date
        val duration: TemporalAmount = event.getProperty<IcalDuration>(Property.DURATION)
            .orElse(null)?.duration
            ?: end?.let { TemporalAmountAdapter.between(start, it).duration }
            ?: if (start is LocalDate) java.time.Period.ofDays(1) else java.time.Duration.ZERO
        val candidates = linkedMapOf<Period<T>, Boolean>()
        fun add(period: Period<T>, ordinary: Boolean) {
            candidates[period] = candidates[period] == true || ordinary
        }
        event.getProperties<RDate<T>>(Property.RDATE).forEach { dateProperty ->
            val periods = dateProperty.periods.orElse(null)
            if (periods != null) {
                periods.filter(range::intersects).forEach { add(it, normalRange.intersects(it)) }
            } else {
                dateProperty.dates.filter(range::includes).forEach { date ->
                    add(Period(date, duration), normalRange.includes(date))
                }
            }
        }
        val rules = event.getProperties<RRule<T>>(Property.RRULE)
        val expansionStart = range.start.minus(duration)
        if (rules.isEmpty()) {
            val initial = Period(start, duration)
            if (range.intersects(initial)) add(initial, normalRange.intersects(initial))
        } else {
            // The wider rule expansion includes the normal rule dates. Preserve the same
            // inclusive lower bound (normal range minus event duration) without expanding twice.
            val ruleRange = Period(normalRange.start.minus(duration), normalRange.end)
            rules.forEach { rule ->
                rule.recur.getDates(start, expansionStart, range.end).forEach { date ->
                    add(Period(date, duration), ruleRange.includes(date))
                }
            }
        }
        val excludedDates = event.getProperties<ExDate<T>>(Property.EXDATE)
            .flatMap { it.dates }.toSet()
        val excludedRulePeriods = event.getProperties<ExRule<T>>(Property.EXRULE).flatMap { rule ->
            rule.recur.getDates(start, expansionStart, range.end).map { Period(it, duration) }
        }.toSet()
        return candidates.entries
            .filterNot { it.key.start in excludedDates || it.key in excludedRulePeriods }
            .sortedBy { it.key }
            .map { (period, ordinary) -> Occurrence(period.start, period.end, ordinary) }
    }

    private fun Temporal.toDisplayTime(zone: ZoneId): ZonedDateTime? = when (this) {
        is ZonedDateTime -> withZoneSameInstant(zone)
        is OffsetDateTime -> atZoneSameInstant(zone)
        is Instant -> atZone(zone)
        is LocalDateTime -> atZone(ZoneId.of("Australia/Melbourne")).withZoneSameInstant(zone)
        is LocalDate -> atStartOfDay(zone)
        else -> null
    }

    private data class EventDetails(
        val code: String,
        val title: String,
        val location: String,
        val room: String,
        val activity: String?
    )

    private data class Occurrence(
        val start: Temporal,
        val end: Temporal,
        val isInNormalRange: Boolean
    )

    companion object {
        private const val FALLBACK_EVENT_CODE = "EVENT"
        private const val MAX_IMPORTED_SESSIONS = 500
        private val ACTIVITY_FIELD = Regex("(?im)^(?:activity(?: type)?|class(?: type)?|type)\\s*:\\s*(tutorial|workshop|lecture)\\b")
        private val ACTIVITY_SUFFIX = Regex("(?i)(?:^|[\\s_:/|(-])(tutorial|workshop|lecture)(?:[\\s_:#-]*[A-Z]?\\d+)?[)\\s]*$")
        private val ACTIVITY_PREFIX = Regex("(?i)^(tutorial|workshop|lecture)(?:[\\s_:#-]*\\d+)?(?:[\\s_:/|-]+|$)")
        private val SUBJECT_NAME = Regex("(?im)^(?:subject|course)(?: name| title)\\s*:\\s*(.+)$")
        private val COURSE_CODE_REGEX = Regex(
            pattern = "(?<![A-Z0-9])[A-Z]{4}\\s?\\d{5}(?!\\d)",
            option = RegexOption.IGNORE_CASE
        )
    }
}

/** A calendar that could not be downloaded or parsed; [message] is written for the user. */
class TimetableImportException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

private class AndroidTimeZoneRegistry : TimeZoneRegistry {
    private val embeddedZones = ConcurrentHashMap<String, IcalTimeZone>()
    private val fallbackRegistry = TimeZoneRegistryImpl()

    override fun register(timeZone: IcalTimeZone) {
        embeddedZones[timeZone.id] = timeZone
    }

    override fun register(timeZone: IcalTimeZone, update: Boolean) {
        register(timeZone)
    }

    override fun clear() {
        embeddedZones.clear()
    }

    override fun getTimeZone(id: String): IcalTimeZone? {
        return embeddedZones[id] ?: fallbackRegistry.getTimeZone(id)
    }

    override fun getZoneRules(): MutableMap<String, ZoneRules> {
        return embeddedZones.keys.mapNotNull { id ->
            resolvePlatformZone(id)?.let { zoneId -> id to zoneId.rules }
        }.toMap().toMutableMap()
    }

    override fun getZoneId(id: String): ZoneId {
        resolvePlatformZone(id)?.let { return it }

        val offsetMillis = embeddedZones[id]?.rawOffset ?: 0
        return ZoneOffset.ofTotalSeconds(offsetMillis / 1_000)
    }

    override fun getTzId(id: String): String {
        return fallbackRegistry.getTzId(id) ?: id
    }

    private fun resolvePlatformZone(id: String): ZoneId? {
        return runCatching { TimeZoneRegistry.getGlobalZoneId(id) }.getOrNull()
            ?: runCatching { ZoneId.of(id, TimeZoneRegistry.ZONE_ALIASES) }.getOrNull()
    }
}
