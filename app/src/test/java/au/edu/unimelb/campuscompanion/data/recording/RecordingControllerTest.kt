package au.edu.unimelb.campuscompanion.data.recording

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = 10_000L
    private var ids = 0
    private val devices = TestRecordingDevices()
    private lateinit var store: RecordingDraftStore

    private fun controller(scope: CoroutineScope): RecordingController {
        if (!::store.isInitialized) store = RecordingDraftStore(temporary.newFolder("recordings"), ::testAudioDuration)
        return RecordingController("account-a", "group-a", store, devices, scope, { now }, { "clip-${++ids}" })
    }

    @Test
    fun stalePermissionGrantsCannotStartAfterCancelOrDisposal() = runBlocking<Unit> {
        val controller = controller(this)
        val cancelled = controller.requestStart(hasPermission = false)!!
        assertTrue(controller.state.value is RecordingState.RequestingPermission)
        controller.cancel()
        controller.permissionResult(cancelled, granted = true)
        assertTrue(devices.created.isEmpty())
        val closed = controller.requestStart(hasPermission = false)!!
        controller.dispose()
        controller.permissionResult(closed, granted = true)
        controller.requestStart(hasPermission = true)
        assertTrue(devices.created.isEmpty())
    }

    @Test
    fun deniedPermissionCanBeRetriedAndDuplicateGrantsStartOnlyOnce() = runBlocking<Unit> {
        val controller = controller(this)
        val denied = controller.requestStart(false)!!
        controller.permissionResult(denied, false)
        assertTrue(controller.state.value is RecordingState.Failed)
        val granted = controller.requestStart(false)!!
        controller.permissionResult(denied, true)
        assertTrue(devices.created.isEmpty())
        controller.permissionResult(granted, true)
        controller.permissionResult(granted, true)
        assertEquals(1, devices.created.size)
        assertTrue(controller.state.value is RecordingState.Recording)
        controller.cancel()
        controller.dispose()
    }

    @Test
    fun theTimerUsesMonotonicTimeAndTheMeterUsesMeasuredInput() = runBlocking<Unit> {
        val controller = controller(this)
        controller.requestStart(true)
        val device = devices.created.single()
        device.peak = 0
        now += 200
        controller.sample()
        val silent = controller.state.value as RecordingState.Recording
        assertEquals(200L, silent.elapsedMillis)
        assertEquals(0f, silent.level, 0f)
        device.peak = 32_767
        now += 100
        controller.sample()
        val loud = controller.state.value as RecordingState.Recording
        assertEquals(300L, loud.elapsedMillis)
        assertTrue(loud.level > silent.level)
        assertTrue(loud.level in 0f..1f)
        device.peak = 0
        now += 100
        controller.sample()
        assertTrue((controller.state.value as RecordingState.Recording).level < loud.level)
        controller.dispose()
    }

    @Test
    fun stopLifecycleAndNativeCallbacksFinalizeExactlyOnce() = runBlocking<Unit> {
        val controller = controller(this)
        controller.requestStart(true)
        val device = devices.created.single()
        controller.stop()
        val draft = (controller.state.value as RecordingState.Review).draft
        controller.stop()
        controller.onBackground()
        device.onLimit()
        device.onError()
        controller.dispose()
        controller.dispose()
        assertEquals(1, device.stops)
        assertEquals(1, device.releases)
        assertEquals(draft, store.load("account-a", "group-a").draft)
        assertTrue(File(draft.localPath).isFile)
        assertFalse(device.output.exists())
    }

    @Test
    fun theTenMinuteBoundaryStopsCaptureAndKeepsAReviewDraft() = runBlocking<Unit> {
        devices.duration = MAX_RECORDING_DURATION_MILLIS
        val controller = controller(this)
        controller.requestStart(true)
        now += MAX_RECORDING_DURATION_MILLIS - 1
        controller.sample()
        assertTrue(controller.state.value is RecordingState.Recording)
        assertEquals(0, devices.created.single().stops)
        now++
        controller.sample()
        val review = controller.state.value as RecordingState.Review
        assertEquals(MAX_RECORDING_DURATION_MILLIS, review.draft.durationMillis)
        assertNotNull(review.warning)
        assertEquals(1, devices.created.single().stops)
        controller.sample()
        controller.dispose()
        assertEquals(1, devices.created.single().stops)
    }

    @Test
    fun backgroundStopsAndARecreatedControllerRestoresReviewWithoutRestarting() = runBlocking<Unit> {
        val first = controller(this)
        first.requestStart(true)
        first.onBackground()
        val review = first.state.value as RecordingState.Review
        assertNotNull(review.warning)
        first.dispose()
        val recreated = controller(this)
        assertEquals(review.draft, (recreated.state.value as RecordingState.Review).draft)
        recreated.requestStart(true)
        assertEquals(1, devices.created.size)
        recreated.dispose()
    }

    @Test
    fun aBackgroundPermissionGrantNeverStartsTheMicrophoneOnReturn() = runBlocking<Unit> {
        val controller = controller(this)
        val request = controller.requestStart(false)!!
        controller.onBackground()
        controller.permissionResult(request, true)
        assertTrue(controller.state.value is RecordingState.Ready)
        assertTrue(devices.created.isEmpty())
        controller.dispose()
    }

    @Test
    fun tooShortOrUnfinalizedFilesAreDiscardedAndCannotBeStaged() = runBlocking<Unit> {
        devices.duration = 250
        val controller = controller(this)
        controller.requestStart(true)
        controller.stop()
        assertTrue(controller.state.value is RecordingState.Failed)
        assertNull(controller.stage())
        assertNull(store.load("account-a", "group-a").draft)
        assertFalse(devices.created.single().output.exists())
        assertEquals(1, devices.created.single().releases)
        controller.dispose()
    }

    @Test
    fun aNativeStopExceptionDoesNotDiscardAnAlreadyFinalizedValidFile() = runBlocking<Unit> {
        devices.throwAfterValidStop = true
        val controller = controller(this)
        controller.requestStart(true)
        devices.created.single().onLimit()
        assertTrue(controller.state.value is RecordingState.Review)
        assertTrue(File((controller.state.value as RecordingState.Review).draft.localPath).isFile)
        assertEquals(1, devices.created.single().releases)
        controller.dispose()
    }

    @Test
    fun anOldRecorderCallbackCannotStopTheNextRecording() = runBlocking<Unit> {
        val controller = controller(this)
        controller.requestStart(true)
        val old = devices.created.single()
        controller.stop()
        controller.recordAgain()
        controller.requestStart(true)
        old.onLimit()
        old.onError()
        assertTrue(controller.state.value is RecordingState.Recording)
        assertEquals(0, devices.created.last().stops)
        controller.dispose()
    }

    @Test
    fun stageIsDurableAndCancelCannotDeleteAQueuedClipOrAnotherAccountDraft() = runBlocking<Unit> {
        val controller = controller(this)
        controller.requestStart(true)
        controller.stop()
        val staged = controller.stage()!!
        assertEquals(RecordingDraftPhase.Staged, store.load("account-a", "group-a").draft?.phase)
        val queued = store.markQueued(staged)
        controller.cancel()
        assertTrue(File(queued.localPath).isFile)
        assertFalse(store.discard(queued.copy(userId = "account-b")))
        controller.requestStart(true)
        controller.cancel()
        assertTrue(File(queued.localPath).isFile)
        assertTrue(store.discardQueued(queued.userId, queued.groupId, queued.id))
        assertFalse(File(queued.localPath).exists())
        controller.dispose()
    }

    @Test
    fun aMicrophoneSamplingErrorStopsAndPreservesTheValidSavedClip() = runBlocking<Unit> {
        val controller = controller(this)
        controller.requestStart(true)
        devices.created.single().amplitudeFails = true
        controller.sample()
        assertTrue(controller.state.value is RecordingState.Review)
        assertNotNull((controller.state.value as RecordingState.Review).warning)
        assertEquals(1, devices.created.single().releases)
        controller.dispose()
    }

    @Test
    fun failedDiscardKeepsReviewVisibleAndCanBeRetried() = runBlocking<Unit> {
        var failDeletion = true
        store = RecordingDraftStore(temporary.newFolder("recordings"), ::testAudioDuration) { file ->
            if (failDeletion && file.extension == "m4a") false else file.delete()
        }
        val controller = controller(this)
        controller.requestStart(true)
        controller.stop()
        val draft = (controller.state.value as RecordingState.Review).draft
        assertFalse(controller.cancel())
        val retained = controller.state.value as RecordingState.Review
        assertEquals(draft, retained.draft)
        assertNotNull(retained.warning)
        assertTrue(File(draft.localPath).isFile)
        assertEquals(draft, store.load(draft.userId, draft.groupId).draft)
        failDeletion = false
        assertTrue(controller.cancel())
        assertTrue(controller.state.value is RecordingState.Ready)
        assertFalse(File(draft.localPath).exists())
        controller.dispose()
    }

    @Test
    fun disposalFinalizesCaptureEvenAfterItsCoroutineScopeWasCancelled() = runBlocking<Unit> {
        val recordingScope = CoroutineScope(SupervisorJob())
        val controller = controller(recordingScope)
        controller.requestStart(true)
        recordingScope.cancel()
        controller.dispose()
        val draft = (controller.state.value as RecordingState.Review).draft
        assertTrue(File(draft.localPath).isFile)
        assertEquals(draft, store.load(draft.userId, draft.groupId).draft)
        assertEquals(1, devices.created.single().stops)
        assertEquals(1, devices.created.single().releases)
    }
}

internal fun testAudioDuration(file: File): Long? =
    file.readText().takeIf { it.startsWith("audio:") }?.removePrefix("audio:")?.toLongOrNull()

private class TestRecordingDevices : RecordingDeviceFactory {
    var duration = 2_000L
    var throwAfterValidStop = false
    val created = mutableListOf<TestRecordingDevice>()
    override fun create(output: File, onDurationLimit: () -> Unit, onError: () -> Unit): RecordingDevice =
        TestRecordingDevice(output, { duration }, { throwAfterValidStop }, onDurationLimit, onError)
            .also { created += it }
}

private class TestRecordingDevice(
    val output: File,
    val duration: () -> Long,
    val throwAfterValidStop: () -> Boolean,
    val onLimit: () -> Unit,
    val onError: () -> Unit
) : RecordingDevice {
    var peak = 0
    var amplitudeFails = false
    var stops = 0
    var releases = 0
    override fun start() { output.writeText("unfinished") }
    override fun amplitude(): Int {
        if (amplitudeFails) throw IllegalStateException("Microphone disconnected")
        return peak
    }
    override fun stop() {
        stops++
        output.writeText("audio:${duration()}")
        if (throwAfterValidStop()) throw IllegalStateException("Native recorder already stopped")
    }
    override fun release() { releases++ }
}
