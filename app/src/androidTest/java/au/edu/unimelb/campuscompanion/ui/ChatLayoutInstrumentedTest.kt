package au.edu.unimelb.campuscompanion.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.edu.unimelb.campuscompanion.MainActivity
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.testing.ChatLayoutTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Production shell/composer with an actual IME and isolated in-memory repositories. */
@RunWith(AndroidJUnit4::class)
class ChatLayoutInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val density = context.resources.displayMetrics.density

    @Test fun keyboardStaysAdjacentToComposerAndFailedUploadActionsRemainVisible() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            info.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        checkLayout(fontScale = 1f, pending = false)
    }

    @Test fun largeTextAndPendingAttachmentKeepTheComposerUsable() {
        checkLayout(fontScale = 1.3f, pending = true)
    }

    private fun checkLayout(fontScale: Float, pending: Boolean) {
        val intent = Intent(context, ChatLayoutTestActivity::class.java)
            .putExtra("font_scale", fontScale).putExtra("pending", pending)
        ActivityScenario.launch<ChatLayoutTestActivity>(intent).use { scenario ->
            waitFor("message field") { editor() != null }
            assertComposerPosition(scenario, keyboard = false)
            assertTrue(editor()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("keyboard to open") { imeVisible(scenario) }
            // Allow the IME animation and timeline's scroll-to-latest to settle.
            SystemClock.sleep(400)
            assertComposerPosition(scenario, keyboard = true)
            assertVisibleDescription(context.getString(R.string.campus_companion_app_back_to_groups))
            if (!pending) {
                assertVisibleDescription(context.getString(R.string.a11y_retry_upload, ChatLayoutTestActivity.FILE_NAME))
                assertVisibleDescription(context.getString(R.string.a11y_remove_file, ChatLayoutTestActivity.FILE_NAME))
                val filename = find { it.text?.toString() == ChatLayoutTestActivity.FILE_NAME }
                assertNotNull("Failed upload filename is visible", filename)
                assertTrue("Long filename stays on one line", bounds(filename!!).height() <= 28 * density)
            } else {
                assertVisibleDescription(context.getString(R.string.group_chat_allow_others_to_download_2, ChatLayoutTestActivity.FILE_NAME))
            }
            capture("chat-${if (pending) "large-text-attachment" else "failed-upload"}-keyboard.png")

            if (!pending) {
                val text = "Layout test message"
                assertTrue(editor()!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }))
                waitFor("draft to update") { editor()?.text?.toString() == text }
                val send = find { it.contentDescription?.toString() == context.getString(R.string.group_chat_send_message) }
                assertTrue(click(send!!))
                waitFor("local fixture message to send") {
                    editor()?.text?.toString().orEmpty().isEmpty() && find { it.text?.toString() == text } != null
                }
            }
            if (imeVisible(scenario)) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("keyboard to close") { !imeVisible(scenario) }
            SystemClock.sleep(1200)
            capture("chat-${if (pending) "large-text-attachment" else "failed-upload"}-closed.png")
            assertComposerPosition(scenario, keyboard = false)
        }
    }

    private fun assertComposerPosition(scenario: ActivityScenario<ChatLayoutTestActivity>, keyboard: Boolean) {
        val send = assertVisibleDescription(context.getString(R.string.group_chat_send_message))
        val add = assertVisibleDescription(context.getString(R.string.group_chat_add_attachment))
        val mic = assertVisibleDescription(context.getString(R.string.record_audio))
        val field = bounds(editor()!!)
        var availableBottom = 0
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            val origin = IntArray(2).also(root::getLocationOnScreen)
            val insets = ViewCompat.getRootWindowInsets(root)!!
            val type = if (keyboard) WindowInsetsCompat.Type.ime() else WindowInsetsCompat.Type.systemBars()
            if (keyboard) assertTrue("Use a docked keyboard to verify IME layout", insets.getInsets(type).bottom >= 150 * density)
            availableBottom = origin[1] + root.height - insets.getInsets(type).bottom
        }
        val gap = availableBottom - field.bottom
        assertTrue("Composer should touch keyboard/system bar; gap=${gap / density} dp", gap in 0..(12 * density).toInt())
        assertTrue("Input stays above keyboard", field.bottom <= availableBottom)
        assertTrue("Attachment and send controls align", abs(add.centerY() - send.centerY()) <= 2 * density)
        assertTrue("Microphone and send controls align", abs(mic.centerY() - send.centerY()) <= 2 * density)
        assertTrue("Text field is not squeezed by attachments", field.width() >= 160 * density)
    }

    private fun imeVisible(scenario: ActivityScenario<ChatLayoutTestActivity>): Boolean {
        var visible = false
        scenario.onActivity { visible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        return visible
    }

    private fun editor() = find { it.className?.toString() == "android.widget.EditText" && it.isVisibleToUser }

    private fun assertVisibleDescription(description: String): Rect {
        val node = find { it.contentDescription?.toString() == description && it.isVisibleToUser }
        assertNotNull("Visible control: $description", node)
        return bounds(node!!)
    }

    private fun bounds(node: AccessibilityNodeInfo): Rect {
        // IME animation moves Compose nodes without necessarily invalidating the accessibility cache.
        node.refresh()
        return Rect().also(node::getBoundsInScreen)
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable) return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target = target.parent
        }
        return false
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun capture(name: String) {
        val directory = File(context.getExternalFilesDir(null), "chat-layout-checks").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Timed out waiting for $description", condition())
    }
}
