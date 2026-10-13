package au.edu.unimelb.campuscompanion.ui.text

import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.ResourceText
import au.edu.unimelb.campuscompanion.english
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TextResourcesTest {
    @Test fun resourceFormatsAcceptTheirDeclaredArguments() {
        val placeholder = Regex("%(\\d+)\\$([.\\d]*[sdf])")
        ResourceText.strings.forEach { (name, template) ->
            val fields = placeholder.findAll(template).toList()
            if (fields.isNotEmpty()) {
                val args = MutableList<Any>(fields.maxOf { it.groupValues[1].toInt() }) { "text" }
                fields.forEach { match ->
                    args[match.groupValues[1].toInt() - 1] = when (match.groupValues[2].last()) {
                        'd' -> 2
                        'f' -> 2.5
                        else -> "value & <content>"
                    }
                }
                // Throws for stray percent signs or incompatible repeated placeholders.
                val rendered = String.format(Locale.ENGLISH, template, *args.toTypedArray())
                assertFalse(name, rendered.contains("%1$"))
            }
        }
    }

    @Test fun memberAndClassCountsUseSingularOnlyForOne() {
        assertEquals("0 members", ResourceText.quantity("member_count", 0, 0))
        assertEquals("1 member", ResourceText.quantity("member_count", 1, 1))
        assertEquals("2 members", ResourceText.quantity("member_count", 2, 2))
        assertEquals("1 class", ResourceText.quantity("class_count", 1, 1))
        assertEquals("10 classes", ResourceText.quantity("class_count", 10, 10))
        assertEquals("1 class this week", ResourceText.quantity("week_class_count", 1, 1))
    }

    @Test fun nestedCountdownQuantitiesUseRealResourceForms() {
        val duration = UiText.Joined(listOf(
            UiText.Plural(R.plurals.home_hours, 1, listOf(1)),
            UiText.Plural(R.plurals.home_minutes, 2, listOf(2))
        ))
        assertEquals("Leave in 1 hour 2 minutes", UiText(R.string.home_leave_in, duration).english())
        assertEquals("0 minutes", UiText.Plural(R.plurals.home_minutes, 0, listOf(0)).english())
        assertEquals("2 hours", UiText.Plural(R.plurals.home_hours, 2, listOf(2)).english())
    }

    @Test fun progressAndNestedLabelsAreFormattedAtRenderTime() {
        assertEquals("Uploading 42%", UiText(R.string.chat_attachments_uploading, 42).english())
        assertEquals("Travel time: 5 min on foot.", UiText(R.string.departure_travel, 5,
            UiText(R.string.departure_on_foot)).english())
        assertEquals("A 100% & <B>", UiText.Literal("A 100% & <B>").english())
    }
}
