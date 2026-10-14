package au.edu.unimelb.campuscompanion.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.edu.unimelb.campuscompanion.data.AttachmentUploadQueue
import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.*
import au.edu.unimelb.campuscompanion.data.recording.*
import au.edu.unimelb.campuscompanion.data.repository.*
import au.edu.unimelb.campuscompanion.testing.HomeRecreationTestActivity
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.GroupOrigin
import au.edu.unimelb.campuscompanion.ui.screens.GroupChatScreen
import au.edu.unimelb.campuscompanion.ui.theme.CampusCompanionTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Production chat/recording Compose, controller and private store in an empty debug host.
 * Capture uses a fake device with a playable WAV fixture; native AAC/microphone tests are separate.
 * Permission token injection avoids changing the user's runtime grants or opening system dialogs.
 */
@RunWith(AndroidJUnit4::class)
class VoiceMessageUiInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun useRecordingStagesOnlyAndSendUploadsTheOriginalClipOnceWithSavedConsent() = withFixture { fixture, _ ->
        openAndStart(fixture)
        click("Stop")
        awaitText("Use recording")
        captureFixture("normal-review")
        click("Use recording")
        awaitText("Ready to send")
        captureFixture("normal-staged")
        assertTrue(fixture.files.uploads.isEmpty())
        assertTrue(fixture.queue.uploads.value.isEmpty())
        val staged = requireNotNull(fixture.store.load(USER, GROUP).draft)
        assertEquals(RecordingDraftPhase.Staged, staged.phase)
        click("Allow voice message downloads")
        await("Download consent must be durable before Send") { fixture.store.load(USER, GROUP).draft?.allowDownloads == false }
        click("Send message")
        await("Send must deliver one voice attachment") { fixture.files.uploads.size == 1 }
        val sent = fixture.files.uploads.single()
        val identity = requireNotNull(sent.identity)
        assertEquals(staged.id, identity.id)
        assertTrue(identity.isVoiceRecording)
        assertEquals(2_000L, identity.durationMillis)
        assertEquals("audio/mp4", sent.mimeType)
        assertFalse(sent.allowDownloads)
        assertArrayEquals(waveFixture(), sent.bytes)
        await("Confirmed metadata must remove the original queued recording") { !File(staged.localPath).exists() }
        assertNull(fixture.store.load(USER, GROUP).draft)
        assertTrue("Staging/voice Send must not create a text message", fixture.chat.sent.isEmpty())
        await("Sending must clear the staged composer") { visibleTexts().none { it == "Ready to send" } }
    }

    @Test fun cancelReviewDiscardsItsPrivateBytesAndNeverQueuesAnUpload() = withFixture { fixture, _ ->
        openAndStart(fixture)
        click("Stop")
        awaitText("Use recording")
        val review = requireNotNull(fixture.store.load(USER, GROUP).draft)
        click("Cancel")
        awaitText("Discard recording?")
        click("Discard")
        awaitText("Record voice message")
        assertNull(fixture.store.load(USER, GROUP).draft)
        assertFalse(File(review.localPath).exists())
        assertTrue(fixture.files.uploads.isEmpty())
        assertTrue(fixture.queue.uploads.value.isEmpty())
        assertEquals(1, fixture.devices.single().stops.get())
        assertEquals(1, fixture.devices.single().releases.get())
    }

    @Test fun backgroundAndActivityRecreationKeepAValidReviewAndStagedComposerDraft() = withFixture { fixture, scenario ->
        openAndStart(fixture)
        scenario.moveToState(Lifecycle.State.CREATED)
        await("ON_STOP must finalize the active capture") { fixture.controller.get()?.state?.value is RecordingState.Review }
        scenario.moveToState(Lifecycle.State.RESUMED)
        awaitText("Use recording")
        assertTrue(visibleTexts().any { it.contains("Recording stopped when the app left the foreground") })
        scenario.recreate()
        awaitText("Use recording")
        assertEquals(1, fixture.devices.size)
        click("Use recording")
        awaitText("Ready to send")
        val staged = requireNotNull(fixture.store.load(USER, GROUP).draft)
        scenario.recreate()
        awaitText("Ready to send")
        assertEquals(staged.id, fixture.store.load(USER, GROUP).draft?.id)
        assertTrue(File(staged.localPath).isFile)
        assertTrue(fixture.files.uploads.isEmpty())
        click("Remove recording")
        await("Removing the staged clip must discard its durable draft") { fixture.store.load(USER, GROUP).draft == null }
        assertFalse(File(staged.localPath).exists())
    }

    @Test fun deniedPermissionShowsRecoveryAndCannotCreateAClipOrUpload() = withFixture { fixture, _ ->
        click("Record voice message")
        awaitText("Start")
        instrumentation.runOnMainSync {
            val controller = requireNotNull(fixture.controller.get())
            val token = requireNotNull(controller.requestStart(false))
            controller.permissionResult(token, false)
        }
        awaitTextContaining("Microphone permission was denied")
        awaitText("Microphone settings")
        awaitText("Start")
        assertTrue(fixture.devices.isEmpty())
        assertNull(fixture.store.load(USER, GROUP).draft)
        assertTrue(fixture.files.uploads.isEmpty())
    }

    @Test fun revokedMicrophoneInputStopsCaptureAndOffersTheValidatedClipForReview() = withFixture { fixture, _ ->
        openAndStart(fixture)
        fixture.revokeInput.set(true)
        instrumentation.runOnMainSync { requireNotNull(fixture.controller.get()).sample() }
        awaitTextContaining("Microphone input stopped")
        awaitText("Use recording")
        assertEquals(1, fixture.devices.single().stops.get())
        assertEquals(1, fixture.devices.single().releases.get())
        assertTrue(File(requireNotNull(fixture.store.load(USER, GROUP).draft).localPath).isFile)
        assertTrue(fixture.files.uploads.isEmpty())
    }

    @Test fun compactLargeTextComposerKeepsStagedPlaybackRemovalAndSendReachable() = withFixture(largeText = true) { fixture, _ ->
        openAndStart(fixture)
        click("Stop")
        awaitText("Use recording")
        captureFixture("compact-font2-review")
        click("Use recording")
        awaitText("Send message")
        captureFixture("compact-font2-staged")
        ensureVisible("Remove recording")
        ensureVisible("Play")
        ensureVisible("Allow voice message downloads")
        click("Send message")
        await("Compact composer must still send exactly one voice clip") { fixture.files.uploads.size == 1 }
    }

    private fun openAndStart(fixture: Fixture) {
        click("Record voice message")
        awaitText("Start")
        // Exercise real controller/UI capture state without a permission popup or grant mutation.
        instrumentation.runOnMainSync { requireNotNull(fixture.controller.get()).requestStart(true) }
        awaitText("Stop")
    }

    private fun captureFixture(name: String) {
        // Large text can place Play below the scroll viewport. Allow native preparation to
        // settle without requiring that offscreen control to become an accessibility child.
        val deadline = SystemClock.elapsedRealtime() + 1_500
        while (SystemClock.elapsedRealtime() < deadline &&
            nodes().none { it.text?.toString() in listOf("Play", "Retry playback") }) SystemClock.sleep(50)
        instrumentation.waitForIdleSync()
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Fixture screenshot unavailable" }
        try {
            val directory = File(context.cacheDir, "voice-ui-layout-qa").apply { mkdirs() }
            FileOutputStream(File(directory, "$name.png")).use { output ->
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            screenshot.recycle()
        }
    }

    private fun click(text: String) {
        ensureVisible(text)
        var node = findNode { it.isVisibleToUser && matches(it, text) }
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("$text must expose a clickable action", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        instrumentation.waitForIdleSync()
    }

    private fun ensureVisible(text: String) {
        if (findNode { it.isVisibleToUser && matches(it, text) } != null) return
        repeat(12) {
            if (findNode { it.isVisibleToUser && matches(it, text) } != null) return
            nodes().filter { it.isVisibleToUser && it.isScrollable }
                .any { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            SystemClock.sleep(100)
            instrumentation.waitForIdleSync()
        }
        awaitText(text)
    }

    private fun awaitText(text: String) = await("Fixture must display $text") { visibleTexts().any { it == text } }
    private fun awaitTextContaining(text: String) = await("Fixture must display $text") { visibleTexts().any { it.contains(text) } }
    private fun visibleTexts() = nodes().filter { it.isVisibleToUser }
        .flatMap { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()) }
    private fun matches(node: AccessibilityNodeInfo, text: String) = node.text?.toString() == text || node.contentDescription?.toString() == text
    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean) = nodes().firstOrNull(predicate)
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        instrumentation.waitForIdleSync()
        assertTrue(message, condition())
    }

    private fun withFixture(largeText: Boolean = false, action: (Fixture, ActivityScenario<HomeRecreationTestActivity>) -> Unit) {
        val fixture = Fixture(File(context.cacheDir, "voice-ui-${UUID.randomUUID()}").apply { mkdirs() })
        val application = context.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is HomeRecreationTestActivity) return
                activity.setContent {
                    CampusCompanionTheme {
                        CompositionLocalProvider(LocalDensity provides Density(activity.resources.displayMetrics.density,
                            if (largeText) 2f else activity.resources.configuration.fontScale)) {
                            Box(if (largeText) Modifier.width(320.dp).height(520.dp) else Modifier) {
                                GroupChatScreen(group, USER, emptyList(), null, null,
                                    onPickDocuments = {}, onPendingDocumentRemoved = {}, onPendingDocumentsCleared = {},
                                    onTakePhoto = {}, onCapturedCameraPhotoConsumed = {},
                                    chatRepository = fixture.chat, fileRepository = fixture.files,
                                    attachmentUploadQueue = fixture.queue, messageSender = fixture.sender,
                                    recordingStore = fixture.store, recordingControllerFactory = fixture.controllerFactory)
                            }
                        }
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
            application.registerActivityLifecycleCallbacks(callbacks)
            scenario = ActivityScenario.launch(HomeRecreationTestActivity::class.java)
            awaitText("Record voice message")
            action(fixture, scenario)
        } finally {
            try { scenario?.close() } finally {
                application.unregisterActivityLifecycleCallbacks(callbacks)
                fixture.scope.cancel()
                fixture.directory.deleteRecursively()
            }
        }
    }

    private class Fixture(val directory: File) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val revokeInput = AtomicBoolean(false)
        val devices = CopyOnWriteArrayList<FixtureDevice>()
        val controller = AtomicReference<RecordingController>()
        val store = RecordingDraftStore(File(directory, "recordings")) { if (it.length() > 0) 2_000L else null }
        val files = FixtureFiles()
        val chat = FixtureChat()
        val sender = ChatMessageSender(chat, scope)
        val queue = AttachmentUploadQueue(files, scope, { USER }, { File(URI(it)).readBytes() },
            discardLocal = { task -> if (task.isVoiceRecording) store.discardQueued(task.userId, task.groupId, task.uploadId) })
        val controllerFactory: (CoroutineScope) -> RecordingController = { recordingScope ->
            RecordingController(USER, GROUP, store, RecordingDeviceFactory { output, _, _ ->
                FixtureDevice(output, revokeInput).also(devices::add)
            }, recordingScope, SystemClock::elapsedRealtime).also(controller::set)
        }
    }

    private class FixtureDevice(private val output: File, private val revoked: AtomicBoolean) : RecordingDevice {
        val stops = AtomicInteger()
        val releases = AtomicInteger()
        override fun start() { output.writeBytes(waveFixture()) }
        override fun amplitude(): Int {
            if (revoked.get()) throw SecurityException("Fixture microphone access revoked")
            return 8_000
        }
        override fun stop() { stops.incrementAndGet() }
        override fun release() { releases.incrementAndGet() }
    }

    private data class UploadCall(val mimeType: String, val bytes: ByteArray, val allowDownloads: Boolean, val identity: FileUploadIdentity?)
    private class FixtureFiles : FileRepository {
        val uploads = CopyOnWriteArrayList<UploadCall>()
        private val files = MutableStateFlow<List<SharedFile>>(emptyList())
        override fun observeFiles(groupId: String): Flow<List<SharedFile>> = files
        override fun uploadFile(groupId: String, fileName: String, mimeType: String, bytes: ByteArray,
            allowDownloads: Boolean, identity: FileUploadIdentity?): Flow<UploadState> = flow {
            uploads += UploadCall(mimeType, bytes.copyOf(), allowDownloads, identity)
            val file = SharedFile(requireNotNull(identity).id, groupId, USER, "Fixture student", fileName, mimeType,
                bytes.size.toLong(), Instant.now(), allowDownloads, identity.durationMillis, identity.isVoiceRecording)
            files.value = files.value + file
            emit(UploadState.Completed(file))
        }
        override suspend fun createPreviewUrl(fileId: String): Result<String> = Result.failure(DataError.Forbidden())
        override suspend fun createDownloadUrl(fileId: String): Result<String> = Result.failure(DataError.Forbidden())
        override suspend fun deleteFile(fileId: String): Result<Unit> = Result.failure(DataError.Forbidden())
    }

    private class FixtureChat : ChatRepository {
        override val connection = MutableStateFlow(ChatConnection.Live)
        val sent = CopyOnWriteArrayList<ChatMessage>()
        private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        override fun observeMessages(groupId: String): Flow<List<ChatMessage>> = messages
        override suspend fun loadOlderMessages(groupId: String, pageSize: Int) = Result.success(false)
        override suspend fun sendMessage(groupId: String, body: String): Result<ChatMessage> {
            val id = "message-${sent.size}"
            val message = ChatMessage(id, id, groupId, USER, "Fixture student", body, Instant.now(), MessageStatus.Sent)
            sent += message; messages.value = sent.toList(); return Result.success(message)
        }
        override suspend fun retryMessage(groupId: String, clientId: String): Result<ChatMessage> = Result.failure(DataError.NotFound())
        override suspend fun markAsRead(groupId: String) = Result.success(Unit)
    }

    companion object {
        private const val USER = "voice-fixture-user"
        private const val GROUP = "voice-fixture-group"
        private val group = CourseGroup(GROUP, "COMP90018", "Voice fixture group", 1, 0, "", null, GroupOrigin.Joined)
        private fun waveFixture(): ByteArray {
            val size = 8_000 * 2 * 2
            return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray())
                putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
                putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
                repeat(size / 2) { putShort(0) }
            }.array()
        }
    }
}
