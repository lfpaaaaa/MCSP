package au.edu.unimelb.campuscompanion.ui

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
        store.save(tutorial, CourseReminderPreference(enabled = false, leadMinutes = 25))
        val reloaded = CourseReminderPreferencesStore(context, userId)
        assertEquals(CourseReminderPreference(false, 25), reloaded.load(tutorial))
        assertTrue(reloaded.load(lecture).enabled)
        assertTrue(reloaded.load(workshop).enabled)
        assertTrue(reloaded.load("activity:swen90016:tutorial").enabled)
        assertTrue(otherStore.load(tutorial).enabled)
    }

    @Test fun oldChoicesAreRetainedUntilAnActivityIsEdited() {
        val legacy = "course:comp90018:mobile computing"
        store.save(legacy, CourseReminderPreference(enabled = false, leadMinutes = 20))
        assertEquals(CourseReminderPreference(false, 20), store.load(lecture, legacy))
        assertFalse(store.load(tutorial, legacy).enabled)
        store.save(lecture, CourseReminderPreference(enabled = true, leadMinutes = 15))
        assertEquals(CourseReminderPreference(true, 15), store.load(lecture, legacy))
        assertEquals(CourseReminderPreference(false, 20), store.load(tutorial, legacy))
    }

    @Test fun removingATimetableClearsOnlyItsAccountsReminderSettings() {
        store.save(tutorial, CourseReminderPreference(enabled = false))
        otherStore.save(tutorial, CourseReminderPreference(enabled = false))
        store.clear()
        assertTrue(store.load(tutorial).enabled)
        assertFalse(otherStore.load(tutorial).enabled)
    }
}
