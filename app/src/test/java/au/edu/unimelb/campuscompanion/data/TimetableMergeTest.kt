package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.data.local.ImportedClassEntity
import au.edu.unimelb.campuscompanion.data.local.LocalClassEntity
import au.edu.unimelb.campuscompanion.data.local.TimetableSourceEntity
import au.edu.unimelb.campuscompanion.data.model.TimetableGroupSpec
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.RouteEstimate
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class TimetableMergeTest {
    private val start = ZonedDateTime.parse("2026-10-12T09:00:00.123456789+11:00[Australia/Melbourne]")
    private val original = CourseSession("uid@1791756000123", "COMP90018", "Mobile Computing",
        "Parkville", "PAR-160", start, start.plusHours(1), activity = "tutorial")
    private val manualId = "manual:edab80a8-9acf-466f-8068-af97c17eaa49"
    private val manual = original.copy(id = manualId, title = "Study group", start = start.plusHours(3), end = start.plusHours(4))
    private val edit = original.copy(title = "Corrected class", location = "Southbank", room = "SOU-110",
        activity = "workshop", start = start.plusHours(5), end = start.plusHours(6))
    private val edited = LocalClass(original.id, LocalClassKind.EDITED, edit, original.start)

    @Test fun manualOnlyNeedsNoSource() {
        val snapshot = TimetableSnapshot(local = listOf(LocalClass(manualId, LocalClassKind.MANUAL, manual)))
        assertEquals(listOf(manual), snapshot.mergedSessions())
        assertEquals(emptyList<TimetableGroupSpec>(), snapshot.source?.groups.orEmpty())
        assertEquals(0, snapshot.forDisplay(start.toInstant(), start.zone).sourceEventCount)
    }

    @Test fun editedCopyReplacesEveryImportedFieldAndKeepsIdentity() {
        assertEquals(listOf(edit), TimetableSnapshot(imported = listOf(original), local = listOf(edited)).mergedSessions())
    }

    @Test fun hiddenAndRestoreUseTheCurrentOriginal() {
        val hidden = LocalClass(original.id, LocalClassKind.HIDDEN)
        assertTrue(TimetableSnapshot(imported = listOf(original), local = listOf(hidden)).mergedSessions().isEmpty())
        assertEquals(listOf(original), TimetableSnapshot(imported = listOf(original)).mergedSessions())
    }

    @Test fun unmatchedAdjustmentIsDormantAndReappliesWhenOriginalReturns() {
        val dormant = TimetableSnapshot(local = listOf(edited))
        assertTrue(dormant.mergedSessions().isEmpty())
        assertEquals(listOf(edited), dormant.local)
        assertEquals(listOf(edit), dormant.copy(imported = listOf(original)).mergedSessions())
    }

    @Test fun expiryIsAppliedAfterEditingAndDisplayClearsEstimatesAndConvertsZone() {
        val measuredEdit = edit.copy(etaMinutes = 10, routeEstimate = RouteEstimate(300, 10))
        val snapshot = TimetableSnapshot(imported = listOf(original), local = listOf(edited.copy(session = measuredEdit)))
        val now = start.plusHours(2).toInstant()
        val displayed = snapshot.forDisplay(now, ZoneId.of("UTC")).sessions.single()
        assertEquals(edit.start.toInstant(), displayed.start.toInstant())
        assertEquals(ZoneId.of("UTC"), displayed.start.zone)
        assertNull(displayed.etaMinutes)
        assertNull(displayed.routeEstimate)
        assertTrue(snapshot.forDisplay(edit.end.toInstant(), start.zone).sessions.isEmpty())
    }

    @Test fun orderUsesInstantsThenIdsAndDoesNotDependOnInputOrder() {
        val utc = original.copy(id = "b", start = start.withZoneSameInstant(ZoneId.of("UTC")), end = original.end.withZoneSameInstant(ZoneId.of("UTC")))
        val melbourne = original.copy(id = "a")
        assertEquals(listOf("a", "b"), TimetableSnapshot(imported = listOf(utc, melbourne)).mergedSessions().map { it.id })
    }

    @Test fun retentionIncludesDormantFutureEditsAndKeepsTheOriginalAnchor() {
        val future = edited.copy(session = edit.copy(start = start.plusDays(4), end = start.plusDays(4).plusHours(1)))
        val snapshot = TimetableSnapshot(local = listOf(future, LocalClass("hidden", LocalClassKind.HIDDEN),
            LocalClass(manualId, LocalClassKind.MANUAL, manual)))
        val target = snapshot.retainedOccurrences(start.plusDays(2).toInstant()).single()
        assertEquals(original.id, target.importedId)
        assertEquals(start, target.originalStart)
        assertTrue(snapshot.retainedOccurrences(start.plusDays(5).toInstant()).isEmpty())
    }

    @Test fun storedRowsPreserveTimesPrecisionZoneAndGroupSpecsWithoutLiveMetadata() {
        val measured = original.copy(etaMinutes = 20, routeEstimate = RouteEstimate(1200, 20))
        assertEquals(original, ImportedClassEntity.from("alice", measured).toDomain())
        assertEquals(edited, LocalClassEntity.from("alice", edited).toDomain())
        val spec = TimetableGroupSpec("COMP90018", "Mobile Computing", "tutorial", 1, "09:00", "10:00", "parkville,par-160")
        val source = TimetableSource("https://calendar.example/private", Instant.parse("2026-10-10T01:00:00.123456789Z"), 9, listOf(spec))
        val decoded = TimetableSourceEntity.from("alice", source).toDomain()
        assertEquals(source, decoded)
        val group = TimetableSnapshot(source = decoded).forDisplay(start.toInstant(), start.zone).groups.single()
        assertEquals(spec.key, group.timetableKey)
        assertEquals(spec.slotLabel(), group.timetableSlot)
        assertEquals(spec, group.timetableSpec)
        assertEquals(0, group.members)
        assertEquals(0, group.unreadCount)
    }

    @Test fun malformedRequiredPayloadOrKindFailsRatherThanDroppingTheRow() {
        val row = LocalClassEntity.from("alice", LocalClass(manualId, LocalClassKind.MANUAL, manual))
        for (bad in listOf(row.copy(title = null), row.copy(startIso = "not a time"), row.copy(kind = "UNKNOWN"))) {
            assertTrue(runCatching { bad.toDomain() }.isFailure)
        }
        assertTrue(runCatching { TimetableSourceEntity("alice", "url", null, 0, "not json").toDomain() }.isFailure)
    }

    @Test fun localWritesRequireCorrectIdentityAndPayloadShape() {
        for (bad in listOf(LocalClass("manual:not-a-uuid", LocalClassKind.MANUAL, manual),
            LocalClass(manualId, LocalClassKind.MANUAL, original),
            LocalClass(original.id, LocalClassKind.EDITED, edit),
            LocalClass(original.id, LocalClassKind.HIDDEN, original))) {
            assertTrue(runCatching { LocalClassEntity.from("alice", bad) }.isFailure)
        }
    }

    @Test fun importedIdsStartingWithManualKeepTheirExactIdentityForEditsAndHides() {
        val importedId = "manual:legacy@123"
        val edited = LocalClass(importedId, LocalClassKind.EDITED, edit.copy(id = importedId), start)
        val hidden = LocalClass(importedId, LocalClassKind.HIDDEN)
        assertEquals(edited, LocalClassEntity.from("alice", edited).toDomain())
        assertEquals(hidden, LocalClassEntity.from("alice", hidden).toDomain())
        val snapshot = TimetableSnapshot(imported = listOf(original.copy(id = importedId)), local = listOf(edited))
        assertEquals(importedId, snapshot.mergedSessions().single().id)
        assertTrue(snapshot.copy(local = listOf(hidden)).mergedSessions().isEmpty())
    }
}
