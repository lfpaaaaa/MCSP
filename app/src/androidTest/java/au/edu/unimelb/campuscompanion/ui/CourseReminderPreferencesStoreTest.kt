package au.edu.unimelb.campuscompanion.ui

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CourseReminderPreferencesStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val userId = "reminder-test-${UUID.randomUUID()}"
    private val otherUserId = "reminder-test-${UUID.randomUUID()}"
    private val store = CourseReminderPreferencesStore(context, userId)
    private val otherStore = CourseReminderPreferencesStore(context, otherUserId)
    private val lecture = "activity:comp90018:lecture"
    private val tutorial = "activity:comp90018:tutorial"
    private val workshop = "activity:comp90018:workshop"

    @After fun cleanUp() {
        store.clear()
        otherStore.clear()
    }

    @Test fun settingsSurviveReloadAndStayWithinTheirActivityAndAccount() {
        store.save(tutorial, CourseReminderPreference(enabled = false, leadMinutes = 25, preparationMinutes = 20))
        val reloaded = CourseReminderPreferencesStore(context, userId)
        assertEquals(CourseReminderPreference(false, 25, 20), reloaded.load(tutorial))
        assertEquals(CourseReminderPreference(), reloaded.load(lecture))
        assertEquals(CourseReminderPreference(), reloaded.load(workshop))
        assertEquals(CourseReminderPreference(), reloaded.load("activity:swen90016:tutorial"))
        assertEquals(CourseReminderPreference(), otherStore.load(tutorial))
    }

    @Test fun oldChoicesAreRetainedUntilAnActivityIsEdited() {
        val legacy = "course:comp90018:mobile computing"
        // Write the pre-preparation format, without the new field, to exercise real migration.
        writeStoredChoice(legacy, enabled = false, leadMinutes = 20)
        assertEquals(CourseReminderPreference(false, 20, 5), store.load(lecture, legacy))
        assertFalse(store.load(tutorial, legacy).enabled)
        store.save(lecture, CourseReminderPreference(enabled = true, leadMinutes = 15, preparationMinutes = 10))
        assertEquals(CourseReminderPreference(true, 15, 10), store.load(lecture, legacy))
        assertEquals(CourseReminderPreference(false, 20, 5), store.load(tutorial, legacy))
    }

    @Test fun anExistingActivityRetainsItsBufferAndGetsFiveMinutesPreparation() {
        writeStoredChoice(tutorial, enabled = true, leadMinutes = 25)

        assertEquals(CourseReminderPreference(true, 25, 5), store.load(tutorial))
    }

    @Test fun legacyPreparationIsInheritedOnlyUntilTheActivityHasItsOwnChoice() {
        val legacy = "course:comp90018:mobile computing"
        store.save(legacy, CourseReminderPreference(false, 15, 20))
        assertEquals(CourseReminderPreference(false, 15, 20), store.load(tutorial, legacy))

        // An older explicit activity setting must not inherit preparation from the legacy row.
        writeStoredChoice(tutorial, enabled = true, leadMinutes = 10)
        assertEquals(CourseReminderPreference(true, 10, 5), store.load(tutorial, legacy))
    }

    @Test fun storedValuesFromTheOldRangeAreClampedToThirtyMinutes() {
        writeStoredChoice(lecture, enabled = true, leadMinutes = 60, preparationMinutes = 90)
        writeStoredChoice(tutorial, enabled = true, leadMinutes = -5, preparationMinutes = -10)

        assertEquals(CourseReminderPreference(true, 30, 30), store.load(lecture))
        assertEquals(CourseReminderPreference(true, 0, 0), store.load(tutorial))
    }

    @Test fun zeroAndMaximumSelectionsSurviveReloadAndSavingClampsOutOfRangeValues() {
        store.save(lecture, CourseReminderPreference(true, 0, 0))
        store.save(tutorial, CourseReminderPreference(true, 30, 30))
        store.save(workshop, CourseReminderPreference(true, -5, 60))
        val reloaded = CourseReminderPreferencesStore(context, userId)

        assertEquals(CourseReminderPreference(true, 0, 0), reloaded.load(lecture))
        assertEquals(CourseReminderPreference(true, 30, 30), reloaded.load(tutorial))
        assertEquals(CourseReminderPreference(true, 0, 30), reloaded.load(workshop))
    }

    @Test fun removingATimetableClearsOnlyItsAccountsReminderSettings() {
        store.save(tutorial, CourseReminderPreference(false, 15, 20))
        otherStore.save(tutorial, CourseReminderPreference(false, 25, 30))
        store.clear()
        assertEquals(CourseReminderPreference(), store.load(tutorial))
        assertEquals(CourseReminderPreference(false, 25, 30), otherStore.load(tutorial))
    }

    private fun writeStoredChoice(
        seriesKey: String,
        enabled: Boolean,
        leadMinutes: Int,
        preparationMinutes: Int? = null
    ) {
        val prefix = "${Uri.encode(userId)}:${Uri.encode(seriesKey)}:"
        val editor = context.getSharedPreferences("course_reminder_preferences", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("${prefix}enabled", enabled)
            .putInt("${prefix}lead_minutes", leadMinutes)
        if (preparationMinutes != null) editor.putInt("${prefix}preparation_minutes", preparationMinutes)
        else editor.remove("${prefix}preparation_minutes")
        assertTrue(editor.commit())
    }
}
