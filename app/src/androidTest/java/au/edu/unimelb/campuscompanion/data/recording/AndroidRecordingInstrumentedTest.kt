package au.edu.unimelb.campuscompanion.data.recording

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.edu.unimelb.campuscompanion.testing.HomeRecreationTestActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Native MIC → AAC/M4A → container validation → platform decoder. No synthetic input is used. */
@RunWith(AndroidJUnit4::class)
class AndroidRecordingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun foregroundNativeCaptureStopsOnBackgroundAndItsPlayableDraftSurvivesRecreation() {
        requireMicrophonePermission()
        val user = "native-recording-${UUID.randomUUID()}"
        val group = "native-group-${UUID.randomUUID()}"
        val store = AndroidRecording.createDraftStore(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val scenario = ActivityScenario.launch(HomeRecreationTestActivity::class.java)
        var controller: RecordingController? = null
        var draft: RecordingDraft? = null
        try {
            instrumentation.runOnMainSync {
                controller = AndroidRecording.createController(context, user, group, scope, store)
                requireNotNull(controller).requestStart(true)
            }
            assertTrue("The real microphone must start", controller?.state?.value is RecordingState.Recording)
            SystemClock.sleep(2_500)
            instrumentation.runOnMainSync {
                requireNotNull(controller).sample()
                val recording = requireNotNull(controller).state.value as RecordingState.Recording
                assertTrue(recording.elapsedMillis in 2_000..5_000)
                assertTrue(recording.level.isFinite() && recording.level in 0f..1f)
                requireNotNull(controller).onBackground()
                draft = (requireNotNull(controller).state.value as RecordingState.Review).draft
                assertNotNull((requireNotNull(controller).state.value as RecordingState.Review).warning)
                scope.cancel()
                requireNotNull(controller).dispose()
            }
            val captured = requireNotNull(draft)
            assertTrue(captured.durationMillis in 2_000..5_000)
            assertTrue(File(captured.localPath).length() in 1..100_000)
            assertEquals(captured, AndroidRecording.createDraftStore(context).load(user, group).draft)
            val player = MediaPlayer()
            try {
                player.setDataSource(captured.localPath)
                player.prepare()
                assertTrue("The captured AAC must be decodable", player.duration in 2_000..5_000)
            } finally { player.release() }
            val restricted = store.setDownloadAllowed(store.stage(captured), false)
            assertFalse(AndroidRecording.createDraftStore(context).load(user, group).draft!!.allowDownloads)
            assertEquals(restricted, AndroidRecording.createDraftStore(context).load(user, group).draft)
            draft = restricted
        } finally {
            instrumentation.runOnMainSync { controller?.dispose() }
            scope.cancel()
            scenario.close()
            draft?.let { store.discard(it) }
        }
    }

    @Test
    fun anImmediateNativeStopCannotStageAnUnusableClip() {
        requireMicrophonePermission()
        val user = "native-short-${UUID.randomUUID()}"
        val group = "native-group-${UUID.randomUUID()}"
        val store = AndroidRecording.createDraftStore(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val scenario = ActivityScenario.launch(HomeRecreationTestActivity::class.java)
        var controller: RecordingController? = null
        try {
            instrumentation.runOnMainSync {
                controller = AndroidRecording.createController(context, user, group, scope, store)
                requireNotNull(controller).requestStart(true)
                assertTrue(requireNotNull(controller).state.value is RecordingState.Recording)
                requireNotNull(controller).stop()
                assertTrue(requireNotNull(controller).state.value is RecordingState.Failed)
                assertEquals(null, requireNotNull(controller).stage())
            }
            assertEquals(null, store.load(user, group).draft)
        } finally {
            instrumentation.runOnMainSync { controller?.dispose() }
            scope.cancel()
            scenario.close()
        }
    }

    private fun requireMicrophonePermission() {
        // The runner may temporarily grant this before the suite and restore it afterward.
        // Tests never change the user's runtime grants themselves.
        assumeTrue("Native recording tests require a temporarily granted microphone permission",
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
}
