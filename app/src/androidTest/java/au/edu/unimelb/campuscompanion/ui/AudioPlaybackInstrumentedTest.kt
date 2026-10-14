package au.edu.unimelb.campuscompanion.ui

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.edu.unimelb.campuscompanion.ui.model.AudioPlaybackController
import au.edu.unimelb.campuscompanion.ui.model.AudioPlaybackPhase
import au.edu.unimelb.campuscompanion.ui.screens.AndroidAudioPlaybackBackend
import au.edu.unimelb.campuscompanion.ui.screens.AndroidPlaybackAudioFocus
import au.edu.unimelb.campuscompanion.testing.HomeRecreationTestActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Real platform decode/playback, with a generated app-local fixture and no microphone access. */
@RunWith(AndroidJUnit4::class)
class AudioPlaybackInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun nativeClipPreparesSeeksPausesAndReleasesWithoutAutomaticResume() {
        val source = File.createTempFile("audio-playback-", ".wav", context.cacheDir)
        source.writeBytes(waveFixture())
        lateinit var controller: AudioPlaybackController
        var ownedController: AudioPlaybackController? = null
        lateinit var backend: AndroidAudioPlaybackBackend
        // Android 15 grants focus only to a foreground app or media foreground service.
        val scenario = ActivityScenario.launch(HomeRecreationTestActivity::class.java)
        try {
            instrumentation.runOnMainSync {
                controller = AudioPlaybackController(source.absolutePath,
                    createBackend = { AndroidAudioPlaybackBackend().also { backend = it } },
                    audioFocus = AndroidPlaybackAudioFocus(context))
                ownedController = controller
                controller.prepare()
            }
            await { controller.state.value.phase == AudioPlaybackPhase.Ready }
            assertTrue(controller.state.value.durationMillis in 1_900..2_100)
            assertEquals(0, controller.state.value.positionMillis)
            instrumentation.runOnMainSync { controller.seekTo(500); controller.play() }
            await {
                instrumentation.runOnMainSync { controller.pollPosition() }
                controller.state.value.positionMillis > 600
            }
            instrumentation.runOnMainSync { controller.pause() }
            assertEquals(AudioPlaybackPhase.Ready, controller.state.value.phase)
            var before = 0
            var after = 0
            instrumentation.runOnMainSync { before = backend.positionMillis() }
            SystemClock.sleep(200)
            instrumentation.runOnMainSync { after = backend.positionMillis(); controller.pause() }
            assertTrue("The native media player must stop advancing on interruption", abs(after - before) < 100)
            assertEquals(AudioPlaybackPhase.Ready, controller.state.value.phase)
            instrumentation.runOnMainSync { controller.dispose(); controller.play(); controller.prepare() }
            assertEquals(AudioPlaybackPhase.Released, controller.state.value.phase)
        } finally {
            ownedController?.let { owner -> instrumentation.runOnMainSync { owner.dispose() } }
            scenario.close()
            source.delete()
        }
    }

    @Test fun undecodableNativeClipShowsFailureAndRetryCanPrepareReplacementBytes() {
        val source = File.createTempFile("audio-retry-", ".wav", context.cacheDir)
        source.writeText("This is not an audio file.")
        lateinit var controller: AudioPlaybackController
        var ownedController: AudioPlaybackController? = null
        try {
            instrumentation.runOnMainSync {
                controller = AudioPlaybackController(source.absolutePath,
                    createBackend = { AndroidAudioPlaybackBackend() }, audioFocus = AndroidPlaybackAudioFocus(context))
                ownedController = controller
                controller.prepare()
            }
            await { controller.state.value.phase == AudioPlaybackPhase.Failed }
            assertNotNull(controller.state.value.error)
            source.writeBytes(waveFixture())
            instrumentation.runOnMainSync { controller.prepare() }
            await { controller.state.value.phase == AudioPlaybackPhase.Ready }
            assertEquals(0, controller.state.value.positionMillis)
            assertNull(controller.state.value.error)
        } finally {
            ownedController?.let { owner -> instrumentation.runOnMainSync { owner.dispose() } }
            source.delete()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue("The native playback callback must complete", condition())
    }

    private fun waveFixture(): ByteArray {
        val sampleRate = 8_000
        val samples = sampleRate * 2
        val size = samples * 2
        return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
            repeat(samples) { putShort((sin(2 * PI * 440 * it / sampleRate) * 800).toInt().toShort()) }
        }.array()
    }
}
