package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
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
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.DtStart
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.Temporal
import java.time.zone.ZoneRules
import java.util.concurrent.ConcurrentHashMap

data class TimetableImport(
    val sessions: List<CourseSession>,
    val groups: List<CourseGroup>,
    val sourceEventCount: Int
)

class TimetableImporter(
    private val client: HttpClient = defaultHttpClient(),
    private val parser: IcsTimetableParser = IcsTimetableParser()
) : AutoCloseable {

    suspend fun importFromUrl(url: String): Result<TimetableImport> {
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

            val imported = withContext(Dispatchers.Default) {
                parser.parse(
                    bytes = bytes,
                    now = ZonedDateTime.now(),
                    displayZone = ZoneId.systemDefault()
                )
            }
            Result.success(imported)
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

    override fun close() {
        client.close()
    }

    companion object {
        private const val MAX_CALENDAR_BYTES = 2 * 1024 * 1024L

        private fun defaultHttpClient() = HttpClient(Android) {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 20_000
            }
            followRedirects = true
        }
    }
}

class IcsTimetableParser {
    fun parse(
        bytes: ByteArray,
        now: ZonedDateTime,
        displayZone: ZoneId
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

        val events = calendar.componentList.all
            .filterIsInstance<VEvent>()
            .filterNot { it.getStatus()?.value.equals("CANCELLED", ignoreCase = true) }

        val courseTitles = linkedMapOf<String, String>()
        val rangeStart = now.minusDays(1)
        val rangeEnd = now.plusMonths(6)
        val sessions = events.flatMapIndexed { index, event ->
            val eventDetails = detailsFor(event)
            if (eventDetails.code != FALLBACK_EVENT_CODE) {
                courseTitles.putIfAbsent(eventDetails.code, eventDetails.title)
            }

            occurrencesFor(event, rangeStart, rangeEnd).mapNotNull { occurrence ->
                val start = occurrence.start.toDisplayTime(displayZone) ?: return@mapNotNull null
                val rawEnd = occurrence.end.toDisplayTime(displayZone) ?: return@mapNotNull null
                val end = if (rawEnd.isAfter(start)) rawEnd else start.plusHours(1)
                val uid = event.uid.map { it.value }.orElse("event-$index")

                CourseSession(
                    id = "$uid@${start.toInstant().toEpochMilli()}",
                    code = eventDetails.code,
                    title = eventDetails.title,
                    location = eventDetails.location,
                    room = eventDetails.room,
                    start = start,
                    end = end
                )
            }
        }
            .distinctBy { it.id }
            .filter { session ->
                session.end.isAfter(now.withZoneSameInstant(session.end.zone))
            }
            .sortedBy { it.start.toInstant() }
            .take(MAX_IMPORTED_SESSIONS)

        val groups = courseTitles.map { (code, title) ->
            CourseGroup(
                id = "${code.lowercase()}-group",
                courseCode = code,
                name = title,
                members = 0,
                unreadCount = 0,
                latestMessage = "Added from your connected timetable.",
                latestFileName = null,
                privateContentEnabled = false
            )
        }

        return TimetableImport(
            sessions = sessions,
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
        val cleanedTitle = summary
            .replace(COURSE_CODE_REGEX, "")
            .replace('_', ' ')
            .trim(' ', '-', ':', '|', '/')
            .ifBlank { summary }

        val locationParts = event.getLocation()?.value
            .orEmpty()
            .split(Regex("[,\\n]"))
            .map(String::trim)
            .filter(String::isNotBlank)

        return EventDetails(
            code = code,
            title = cleanedTitle,
            location = locationParts.firstOrNull() ?: "Location not provided",
            room = locationParts.drop(1).joinToString(", ")
        )
    }

    private fun occurrencesFor(
        event: VEvent,
        rangeStart: ZonedDateTime,
        rangeEnd: ZonedDateTime
    ): List<Occurrence> {
        val eventStart = event.getProperty<DtStart<Temporal>>(Property.DTSTART)
            .orElse(null)
            ?.date
            ?: return emptyList()
        return when (eventStart) {
            is ZonedDateTime -> calculateOccurrences(
                event,
                rangeStart.withZoneSameInstant(eventStart.zone),
                rangeEnd.withZoneSameInstant(eventStart.zone)
            )
            is OffsetDateTime -> calculateOccurrences(
                event,
                rangeStart.toOffsetDateTime(),
                rangeEnd.toOffsetDateTime()
            )
            is Instant -> calculateOccurrences(event, rangeStart.toInstant(), rangeEnd.toInstant())
            is LocalDateTime -> calculateOccurrences(
                event,
                rangeStart.toLocalDateTime(),
                rangeEnd.toLocalDateTime()
            )
            is LocalDate -> calculateOccurrences(event, rangeStart.toLocalDate(), rangeEnd.toLocalDate())
            else -> emptyList()
        }
    }

    private fun <T : Temporal> calculateOccurrences(
        event: VEvent,
        rangeStart: T,
        rangeEnd: T
    ): List<Occurrence> {
        val range = Period(rangeStart, rangeEnd)
        return event.calculateRecurrenceSet<T>(range)
            .map { period -> Occurrence(period.start, period.end) }
    }

    private fun Temporal.toDisplayTime(zone: ZoneId): ZonedDateTime? = when (this) {
        is ZonedDateTime -> withZoneSameInstant(zone)
        is OffsetDateTime -> atZoneSameInstant(zone)
        is Instant -> atZone(zone)
        is LocalDateTime -> atZone(zone)
        is LocalDate -> atStartOfDay(zone)
        else -> null
    }

    private data class EventDetails(
        val code: String,
        val title: String,
        val location: String,
        val room: String
    )

    private data class Occurrence(
        val start: Temporal,
        val end: Temporal
    )

    companion object {
        private const val FALLBACK_EVENT_CODE = "EVENT"
        private const val MAX_IMPORTED_SESSIONS = 500
        private val COURSE_CODE_REGEX = Regex(
            pattern = "(?<![A-Z0-9])[A-Z]{4}\\s?\\d{5}(?!\\d)",
            option = RegexOption.IGNORE_CASE
        )
    }
}

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
