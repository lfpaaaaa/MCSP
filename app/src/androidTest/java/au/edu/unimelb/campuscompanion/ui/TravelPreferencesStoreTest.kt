package au.edu.unimelb.campuscompanion.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.TravelPreferencesStore
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TravelPreferencesStoreTest {
    @Test fun retiringGlobalReminderKeysPreservesTravelAndCourseChoices() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "travel-migration-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$prefix-$name", mode)
        }
        try {
            val preferences = context.getSharedPreferences("travel_preferences", Context.MODE_PRIVATE)
            preferences.edit().putBoolean("reminders_enabled", false).putInt("reminder_lead_minutes", 60)
                .putInt("walking_threshold_meters", 800).putString("longer_distance_mode", "Driving").commit()
            val course = CourseReminderPreferencesStore(context, "fixture-user")
            val choice = CourseReminderPreference(false, 25, 5)
            course.save("activity:comp90018:tutorial", choice)
            val store = TravelPreferencesStore(context)
            assertEquals(TravelPreferences(800, TravelMode.Driving), store.load())
            store.save(store.load().copy(walkingThresholdMeters = 1200))
            assertEquals(TravelPreferences(1200, TravelMode.Driving), TravelPreferencesStore(context).load())
            assertFalse(preferences.contains("reminders_enabled"))
            assertFalse(preferences.contains("reminder_lead_minutes"))
            assertEquals(choice, CourseReminderPreferencesStore(context, "fixture-user").load("activity:comp90018:tutorial"))
        } finally {
            base.deleteSharedPreferences("$prefix-travel_preferences")
            base.deleteSharedPreferences("$prefix-course_reminder_preferences")
        }
    }
}
