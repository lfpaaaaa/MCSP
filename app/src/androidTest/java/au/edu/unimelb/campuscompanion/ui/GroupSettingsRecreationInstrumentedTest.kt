package au.edu.unimelb.campuscompanion.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.AttachmentPhase
import au.edu.unimelb.campuscompanion.data.AttachmentUpload
import au.edu.unimelb.campuscompanion.data.AttachmentUploadQueue
import au.edu.unimelb.campuscompanion.data.toUserMessage
import au.edu.unimelb.campuscompanion.data.model.FileCatalogState
import au.edu.unimelb.campuscompanion.data.model.GroupInvite
import au.edu.unimelb.campuscompanion.data.model.GroupMember
import au.edu.unimelb.campuscompanion.data.model.GroupRole
import au.edu.unimelb.campuscompanion.data.model.SharedFile
import au.edu.unimelb.campuscompanion.data.model.UploadState
import au.edu.unimelb.campuscompanion.data.repository.FileRepository
import au.edu.unimelb.campuscompanion.data.repository.FileUploadIdentity
import au.edu.unimelb.campuscompanion.testing.HomeRecreationTestActivity
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.GroupOrigin
import au.edu.unimelb.campuscompanion.ui.screens.GroupSettingsDialog
import au.edu.unimelb.campuscompanion.ui.theme.CampusCompanionTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Production settings/browser Compose content in an empty debug host, with real Activity
 * recreation and in-memory members/files. Fixture callbacks only capture values: they have
 * no sign-in/out actions, user-preference writes, or network upload/download calls.
 */
@RunWith(AndroidJUnit4::class)
class GroupSettingsRecreationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun editedDraftSurvivesMembersBackAndRecreationBeforeSaving() = withFixture { fixture, scenario ->
        editDraft()
        clickText("Members (2)")
        awaitText("Group members")
        awaitTextContaining("Fixture colleague")
        recreate(scenario, fixture, "Group members")
        clickText("Back")

        assertDraft("Edited fixture name", folded = true, muted = true)
        clickText("Save")
        await("Save should receive the restored draft") { fixture.saved.size == 1 }
        assertEquals(SavedDraft(true, true, "Edited fixture name", true), fixture.saved.single())
    }

    @Test
    fun editedDraftSurvivesGroupFilesBackAndRecreationBeforeSaving() = withFixture { fixture, scenario ->
        editDraft()
        clickText("Group files")
        awaitText("fixture-notes.pdf")
        recreate(scenario, fixture, "Group files")
        awaitText("fixture-notes.pdf")
        clickText("Back to group settings")

        assertDraft("Edited fixture name", folded = true, muted = true)
        assertTrue("The recreated browser must subscribe to the injected fixture repository", fixture.files.subscriptions.get() >= 2)
        clickText("Save")
        await("Save should receive the file-browser return draft") { fixture.saved.size == 1 }
        assertEquals(SavedDraft(true, true, "Edited fixture name", true), fixture.saved.single())
    }

    @Test
    fun cancelAfterBothNestedPagesDiscardsDraftWithoutSaving() = withFixture { fixture, _ ->
        editDraft()
        clickText("Members (2)")
        awaitText("Group members")
        clickText("Back")
        clickText("Group files")
        awaitText("fixture-notes.pdf")
        clickText("Back to group settings")
        assertDraft("Edited fixture name", folded = true, muted = true)
        clickText("Cancel")

        awaitText("Fixture settings closed")
        assertEquals(1, fixture.dismissals.get())
        assertTrue("Cancel must never call onSave", fixture.saved.isEmpty())
    }

    @Test
    fun unchangedServerNicknameAfterRecreationIsSavedWithoutNicknameChangedFlag() = withFixture { fixture, scenario ->
        ensureEditable()
        await("The server nickname must load") { editableText()?.contains(serverName) == true }
        setSwitch("Fold this group", true)
        recreate(scenario, fixture, "Group settings")
        assertDraft(serverName, folded = true, muted = false)
        clickText("Save")

        await("Save should distinguish an unchanged nickname") { fixture.saved.size == 1 }
        assertEquals(SavedDraft(true, false, serverName, false), fixture.saved.single())
    }

    @Test
    fun expiredJoinCodeDoesNotRenewOnActivityRecreationAndOffersQrAndNfc() = withFixture { fixture, scenario ->
        clickText("View join code")
        awaitTextContaining("The 6-character code is unavailable or expired")
        assertTrue(visibleTexts().contains("Share QR code"))
        assertTrue(visibleTexts().contains("Invite with NFC"))
        assertFalse(visibleTexts().contains("ABC234"))
        assertFalse(visibleTexts().contains("Copy join code"))

        recreate(scenario, fixture, "Join code")
        awaitTextContaining("The 6-character code is unavailable or expired")
        assertTrue(visibleTexts().contains("Share QR code"))
        assertTrue(visibleTexts().contains("Invite with NFC"))
        assertFalse(visibleTexts().contains("ABC234"))
        clickText("Done")
        awaitText("Group settings")
        assertTrue(fixture.saved.isEmpty())
    }

    @Test
    fun timetableGroupsHideInvitationAndExitControlsWhileMembersAndFilesRemain() {
        listOf(
            groupFixture().copy(origin = GroupOrigin.Timetable, timetableKey = "COMP90018|course"),
            groupFixture().copy(origin = GroupOrigin.Joined, timetableKey = "COMP90018|course")
        ).forEach { group ->
            withFixture(group) { fixture, _ ->
                awaitText("Members (2)")
                awaitText("Group files")
                val texts = allTexts()
                listOf("View join code", "Share QR code", "Invite with NFC", "Leave group", "Dissolve group").forEach { label ->
                    assertFalse("Timetable membership must hide $label", texts.contains(label))
                }
                assertTrue(fixture.saved.isEmpty())
            }
        }
    }

    @Test
    fun automaticFreshCatalogClearsFailedExplicitRefreshWithoutAnotherUserRefresh() = withFixture { fixture, _ ->
        val offline = DataError.Offline()
        val offlineMessage = offline.toUserMessage().body
        fixture.files.refreshFailure = offline
        clickText("Group files")
        awaitText("fixture-notes.pdf")
        clickText("Refresh group files")
        awaitText(offlineMessage)
        awaitText("Retry")
        assertEquals(1, fixture.files.refreshes.get())

        val originalFiles = fixture.files.catalog.value.files
        fixture.files.catalog.value = FileCatalogState(
            files = originalFiles, isLoading = false, hasLoaded = true, isCached = true, error = DataError.Offline()
        )
        awaitText("Showing saved files. Refresh to check for changes.")
        awaitText(offlineMessage)
        // Repository recovery can publish identical files. The status transition itself must
        // clear an older explicit refresh failure rather than requiring another tap on Retry.
        fixture.files.catalog.value = FileCatalogState(files = originalFiles, isLoading = false, hasLoaded = true)
        await("Fresh authoritative contents must clear the old failure and Retry") {
            val texts = allTexts()
            !texts.contains(offlineMessage) && !texts.contains("Retry") && texts.contains("fixture-notes.pdf")
        }
        assertEquals("Automatic catalog recovery must not trigger another explicit refresh", 1, fixture.files.refreshes.get())
    }

    @Test
    fun browserFiltersByUploaderAndMediaWhileRecipientDeleteControlsStayHidden() = withFixture { fixture, _ ->
        val own = fixture.files.catalog.value.files.single()
        val recipientPdf = own.copy(id = "recipient-pdf", uploaderId = "fixture-colleague",
            uploaderName = "Fixture colleague", fileName = "colleague-notes.pdf")
        val recipientPhoto = recipientPdf.copy(id = "recipient-photo", fileName = "colleague-photo.jpg", mimeType = "image/jpeg")
        fixture.files.catalog.value = fixture.files.catalog.value.copy(files = listOf(own, recipientPdf, recipientPhoto))
        clickText("Group files")
        ensureText("Delete fixture-notes.pdf")
        assertTrue(allTexts().contains("Delete fixture-notes.pdf"))
        ensureText("colleague-notes.pdf")
        assertFalse(allTexts().contains("Delete colleague-notes.pdf"))
        ensureText("colleague-photo.jpg")
        assertFalse(allTexts().contains("Delete colleague-photo.jpg"))

        ensureEditable()
        setEditableText("COLLEAGUE")
        ensureText("colleague-notes.pdf")
        await("Uploader search must exclude the current user's file") { !allTexts().contains("fixture-notes.pdf") }
        clickText("Photos")
        ensureText("colleague-photo.jpg")
        await("Photos must exclude the recipient's document") { !allTexts().contains("colleague-notes.pdf") }
        assertFalse(allTexts().contains("Delete colleague-photo.jpg"))
    }

    @Test
    fun freshBrowserAcknowledgesCompletedUploadBeforeUploaderDeletesItWithoutReturningToChat() = withFixture { fixture, _ ->
        val file = fixture.files.catalog.value.files.single()
        val restored = AttachmentUpload(
            id = "fixture-completed-upload", userId = fixtureUser, groupId = file.groupId,
            uri = "content://fixture/original", fileName = file.fileName, mimeType = file.mimeType,
            createdAt = Instant.EPOCH.toString(), phase = AttachmentPhase.Completed, progress = 1f,
            fileId = file.id, localUri = "file:///fixture/local-draft"
        )
        val persisted = AtomicReference(listOf(restored))
        val discarded = AtomicInteger()
        val confirmations = AtomicInteger()
        val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val queue = AttachmentUploadQueue(
                files = fixture.files, scope = queueScope, currentUserId = { fixtureUser },
                readBytes = { error("A restored completed upload must never transfer again") },
                restored = listOf(restored), persist = { persisted.set(it) },
                discardLocal = { discarded.incrementAndGet() }
            )
            fixture.confirmed = { ids -> confirmations.incrementAndGet(); queue.acknowledge(ids) }
            fixture.files.deleteFailure = null
            fixture.files.catalog.value = fixture.files.catalog.value.copy(isCached = true, error = DataError.Offline())
            clickText("Group files")
            awaitText("Showing saved files. Refresh to check for changes.")
            awaitText("fixture-notes.pdf")
            assertEquals("Cached/offline contents cannot acknowledge a completed task", listOf(restored), queue.uploads.value)
            assertEquals(0, confirmations.get())
            assertEquals(0, discarded.get())

            fixture.files.catalog.value = fixture.files.catalog.value.copy(isCached = false, error = null)
            await("Authoritative browser contents must acknowledge the restored completed upload") { queue.uploads.value.isEmpty() }
            assertTrue("Acknowledgement must persist queue removal", persisted.get().isEmpty())
            assertEquals("Acknowledgement must discard the local draft once", 1, discarded.get())
            clickText("Delete fixture-notes.pdf")
            awaitText("Delete attachment?")
            clickText("Delete")
            awaitText("No files have been shared in this group yet.")

            assertEquals(1, fixture.files.deletions.get())
            assertTrue(fixture.files.catalog.value.files.isEmpty())
            assertTrue("Deleting before returning to chat must not strand an Uploaded-syncing task", queue.uploads.value.isEmpty())
            assertTrue(persisted.get().isEmpty())
            assertEquals(1, discarded.get())
            assertTrue("The fixture must still be in the file browser", visibleTexts().contains("Group files"))
        } finally {
            queueScope.cancel()
        }
    }

    private fun editDraft() {
        ensureEditable()
        await("The server nickname must load before editing") { editableText()?.contains(serverName) == true }
        setEditableText("Edited fixture name")
        setSwitch("Fold this group", true)
        setSwitch("Mute notifications", true)
    }

    private fun assertDraft(name: String, folded: Boolean, muted: Boolean) {
        awaitText("Group settings")
        ensureText("Fold this group")
        assertEquals("Fold draft must survive nested navigation/recreation", folded, requireNotNull(switchFor("Fold this group")).isChecked)
        ensureText("Mute notifications")
        assertEquals("Mute draft must survive nested navigation/recreation", muted, requireNotNull(switchFor("Mute notifications")).isChecked)
        ensureEditable()
        await("Edited nickname must survive the refreshed member lookup") { editableText()?.contains(name) == true }
    }

    private fun recreate(scenario: ActivityScenario<HomeRecreationTestActivity>, fixture: Fixture, title: String) {
        val before = fixture.created.get()
        scenario.recreate()
        await("A real Activity recreation must install a new Compose root") { fixture.created.get() > before }
        awaitText(title)
    }

    private fun setSwitch(title: String, checked: Boolean) {
        ensureText(title)
        val node = requireNotNull(switchFor(title)) { "No switch beside $title" }
        if (node.isChecked != checked) assertTrue("Switch must respond to a click", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("$title must be $checked") { switchFor(title)?.isChecked == checked }
    }

    private fun switchFor(title: String): AccessibilityNodeInfo? {
        val label = findNode { it.isVisibleToUser && it.text?.toString() == title } ?: return null
        val bounds = Rect().also(label::getBoundsInScreen)
        // Compose can flatten rows without semantics. Associate a switch by its rendered row,
        // rather than assuming the text and switch have an accessibility parent in common.
        return nodes().filter { it.isVisibleToUser && it.isCheckable }.minByOrNull { node ->
            val switchBounds = Rect().also(node::getBoundsInScreen)
            abs(switchBounds.centerY() - bounds.centerY())
        }
    }

    private fun clickText(text: String) {
        ensureText(text)
        var node = findNode { it.isVisibleToUser && matches(it, text) }
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("$text must expose a clickable accessibility action",
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        instrumentation.waitForIdleSync()
    }

    private fun ensureText(text: String) = ensureVisible("$text must become visible") { matches(it, text) }

    private fun ensureEditable() = ensureVisible("The nickname field must become visible") {
        it.isEditable || it.className?.toString() == "android.widget.EditText"
    }

    private fun ensureVisible(message: String, predicate: (AccessibilityNodeInfo) -> Boolean) {
        if (findNode { it.isVisibleToUser && predicate(it) } != null) return
        repeat(12) { if (!scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return@repeat }
        repeat(16) {
            if (findNode { it.isVisibleToUser && predicate(it) } != null) return
            scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        await(message) { findNode { it.isVisibleToUser && predicate(it) } != null }
    }

    private fun scroll(action: Int): Boolean {
        val scrolled = nodes().filter { it.isVisibleToUser && it.isScrollable }.any { it.performAction(action) }
        if (scrolled) {
            SystemClock.sleep(150)
            instrumentation.waitForIdleSync()
        }
        return scrolled
    }

    private fun editable() = findNode {
        it.isVisibleToUser && (it.isEditable || it.className?.toString() == "android.widget.EditText")
    }

    private fun editableText() = editable()?.text?.toString()
    private fun setEditableText(text: String) {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        assertTrue("The text field must accept accessibility text input",
            requireNotNull(editable()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        await("The field must reflect the edited text") { editableText()?.contains(text) == true }
    }
    private fun matches(node: AccessibilityNodeInfo, text: String) =
        node.text?.toString() == text || node.contentDescription?.toString() == text

    private fun awaitText(text: String) = await("Rendered fixture must show $text") {
        visibleTexts().contains(text)
    }

    private fun awaitTextContaining(text: String) = await("Rendered fixture must show $text") {
        visibleTexts().any { it.contains(text) }
    }

    private fun visibleTexts() = nodes().filter { it.isVisibleToUser }.flatMap(::texts)
    private fun allTexts() = nodes().flatMap(::texts)
    private fun texts(node: AccessibilityNodeInfo) = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean) = nodes().firstOrNull(predicate)
    private fun nodes() = instrumentation.uiAutomation.rootInActiveWindow?.let(::descendants).orEmpty()
    private fun descendants(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            repeat(node.childCount) { index -> node.getChild(index)?.let(::visit) }
        }
        visit(root)
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        instrumentation.waitForIdleSync()
        assertTrue(message, condition())
    }

    private fun withFixture(
        group: CourseGroup = groupFixture(),
        action: (Fixture, ActivityScenario<HomeRecreationTestActivity>) -> Unit
    ) {
        val fixture = Fixture(group)
        val preferences = listOf("timetable_subscription", "group_chat_preferences", "course_reminder_preferences")
            .associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
        val application = context.applicationContext as Application
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is HomeRecreationTestActivity) return
                fixture.created.incrementAndGet()
                activity.setContentView(FrameLayout(activity))
                activity.setContent {
                    var showing by rememberSaveable { mutableStateOf(true) }
                    CampusCompanionTheme {
                        if (showing) GroupSettingsDialog(
                            group = group, currentUserId = fixtureUser, initialFolded = false, initialMuted = false,
                            initialDisplayName = "Original fixture profile",
                            onDismiss = { fixture.dismissals.incrementAndGet(); showing = false },
                            onInviteWithNfc = { fixture.invites.incrementAndGet() },
                            createQrInvite = { Result.success(GroupInvite(group.id, "a".repeat(64), Instant.now().plusSeconds(300))) },
                            observeMembers = { flowOf(fixture.members) },
                            onTransfer = { Result.failure(DataError.Forbidden()) },
                            onExitGroup = { Result.failure(DataError.Forbidden()) },
                            onSave = { folded, muted, name, changed ->
                                fixture.saved += SavedDraft(folded, muted, name, changed)
                                showing = false
                                Result.success(Unit)
                            },
                            fileRepository = fixture.files,
                            onConfirmedFiles = { ids -> fixture.confirmed(ids) }
                        ) else Text("Fixture settings closed")
                    }
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        var scenario: ActivityScenario<HomeRecreationTestActivity>? = null
        try {
            application.registerActivityLifecycleCallbacks(lifecycle)
            scenario = ActivityScenario.launch(HomeRecreationTestActivity::class.java)
            awaitText("Group settings")
            action(fixture, scenario)
            preferences.forEach { (name, original) ->
                assertEquals("Fixture callbacks must preserve existing $name preferences", original,
                    context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap())
            }
        } finally {
            try { scenario?.close() } finally {
                instrumentation.runOnMainSync {
                    val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                    Stage.values().filter { it != Stage.DESTROYED }
                        .flatMap { monitor.getActivitiesInStage(it) }.filterIsInstance<HomeRecreationTestActivity>()
                        .distinct().forEach { it.finish() }
                }
                instrumentation.waitForIdleSync()
                application.unregisterActivityLifecycleCallbacks(lifecycle)
            }
        }
    }

    private data class SavedDraft(val folded: Boolean, val muted: Boolean, val name: String, val nicknameChanged: Boolean)

    private class Fixture(group: CourseGroup) {
        val created = AtomicInteger()
        val dismissals = AtomicInteger()
        val invites = AtomicInteger()
        val saved = CopyOnWriteArrayList<SavedDraft>()
        @Volatile var confirmed: (Set<String>) -> Unit = {}
        val members = listOf(
            GroupMember(fixtureUser, serverName, null, GroupRole.Member, Instant.EPOCH),
            GroupMember("fixture-colleague", "Fixture colleague", null, GroupRole.Owner, Instant.EPOCH)
        )
        val files = FixtureFiles(group.id)
    }

    private class FixtureFiles(private val groupId: String) : FileRepository {
        val subscriptions = AtomicInteger()
        val refreshes = AtomicInteger()
        val deletions = AtomicInteger()
        @Volatile var refreshFailure: DataError? = null
        @Volatile var deleteFailure: DataError? = DataError.Forbidden()
        val catalog = MutableStateFlow(FileCatalogState(
            files = listOf(SharedFile("fixture-file", groupId, fixtureUser, serverName, "fixture-notes.pdf",
                "application/pdf", 42, Instant.EPOCH, allowDownloads = false)),
            isLoading = false, hasLoaded = true
        ))
        override fun observeFiles(groupId: String): Flow<List<SharedFile>> {
            check(groupId == this.groupId)
            return catalog.map { it.files }
        }
        override fun observeFileCatalog(groupId: String): Flow<FileCatalogState> {
            check(groupId == this.groupId)
            subscriptions.incrementAndGet()
            return catalog
        }
        override suspend fun refreshFiles(groupId: String): Result<Unit> {
            check(groupId == this.groupId)
            refreshes.incrementAndGet()
            refreshFailure?.let { failure ->
                catalog.value = catalog.value.copy(isLoading = false, isCached = true, error = failure)
                return Result.failure(failure)
            }
            catalog.value = catalog.value.copy(isLoading = false, isCached = false, error = null)
            return Result.success(Unit)
        }
        override fun uploadFile(groupId: String, fileName: String, mimeType: String, bytes: ByteArray,
            allowDownloads: Boolean, identity: FileUploadIdentity?): Flow<UploadState> =
            flowOf(UploadState.Failed(DataError.Forbidden()))
        override suspend fun createPreviewUrl(fileId: String): Result<String> = Result.failure(DataError.Forbidden())
        override suspend fun createDownloadUrl(fileId: String): Result<String> = Result.failure(DataError.Forbidden())
        override suspend fun deleteFile(fileId: String): Result<Unit> {
            deletions.incrementAndGet()
            deleteFailure?.let { return Result.failure(it) }
            val file = catalog.value.files.firstOrNull { it.id == fileId } ?: return Result.failure(DataError.NotFound())
            if (file.uploaderId != fixtureUser) return Result.failure(DataError.Forbidden())
            catalog.value = catalog.value.copy(files = catalog.value.files.filterNot { it.id == fileId })
            return Result.success(Unit)
        }
    }

    companion object {
        private const val fixtureUser = "fixture-settings-user"
        private const val serverName = "Fixture server nickname"

        private fun groupFixture() = CourseGroup(
            id = "fixture-settings-group", courseCode = "COMP90018", name = "Fixture revision group",
            members = 2, unreadCount = 0, latestMessage = "", latestFileName = null,
            origin = GroupOrigin.Joined, joinCode = "ABC234", joinCodeExpiresAt = Instant.EPOCH.plusSeconds(300)
        )
    }
}
