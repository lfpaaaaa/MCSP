package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.data.model.SharedFile
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AudioPlaybackControllerTest {
    private val coordinator = AudioPlaybackCoordinator()
    private val focus = FakeFocus()
    private val players = mutableListOf<FakeBackend>()
    private fun controller(focus: FakeFocus = this.focus) = AudioPlaybackController("fixture.m4a", {
        FakeBackend().also(players::add)
    }, focus, coordinator)

    @Test fun preparationDoesNotAutoplayAndPositionSeekAndReplayUseThePreparedClip() {
        val controller = controller()
        controller.prepare()
        val media = players.single()
        assertEquals(AudioPlaybackPhase.Preparing, controller.state.value.phase)
        media.prepared(10_000)
        assertEquals(AudioPlaybackPhase.Ready, controller.state.value.phase)
        assertEquals(0, media.starts)
        controller.play()
        assertEquals(1, media.starts)
        assertEquals(AudioPlaybackPhase.Playing, controller.state.value.phase)
        media.position = 1_234
        controller.pollPosition()
        assertEquals(1_234, controller.state.value.positionMillis)
        controller.seekTo(50_000)
        assertEquals(10_000, media.position)
        media.completed()
        assertEquals(AudioPlaybackPhase.Ready, controller.state.value.phase)
        assertEquals(10_000, controller.state.value.positionMillis)
        controller.play()
        assertEquals(0, media.position)
        assertEquals(2, media.starts)
        controller.dispose()
        assertEquals(1, media.releases)
    }

    @Test fun interruptionsPauseAndKeepPositionWithoutResumingOnFocusGainOrLaterPreparation() {
        val controller = controller()
        controller.prepare()
        val media = players.single()
        media.prepared(10_000)
        controller.play()
        media.position = 4_500
        focus.interrupted()
        assertEquals(1, media.pauses)
        assertEquals(4_500, controller.state.value.positionMillis)
        assertEquals(AudioPlaybackPhase.Ready, controller.state.value.phase)
        assertEquals(1, media.starts)
        controller.pause() // Lifecycle ON_STOP is safe when already interrupted.
        assertEquals(1, media.pauses)
        controller.play()
        assertEquals(2, media.starts)
        controller.dispose()
    }

    @Test fun onlyOnePreviewCanPlayAndDisposingAnotherPreviewCannotStopIt() {
        val first = controller()
        val second = controller(FakeFocus())
        first.prepare(); players[0].prepared(10_000)
        second.prepare(); players[1].prepared(10_000)
        first.play()
        second.play()
        assertEquals(AudioPlaybackPhase.Ready, first.state.value.phase)
        assertEquals(AudioPlaybackPhase.Playing, second.state.value.phase)
        assertEquals(1, players[0].pauses)
        first.dispose()
        assertEquals(AudioPlaybackPhase.Playing, second.state.value.phase)
        coordinator.pauseActive()
        assertEquals(AudioPlaybackPhase.Ready, second.state.value.phase)
        second.dispose()
    }

    @Test fun errorsReleaseMediaAndRetryIgnoresEveryCallbackFromThePreviousGeneration() {
        val controller = controller()
        controller.prepare()
        val old = players.single()
        old.failed()
        assertEquals(AudioPlaybackPhase.Failed, controller.state.value.phase)
        assertEquals(1, old.releases)
        controller.prepare()
        val current = players.last()
        old.prepared(999)
        old.completed()
        old.failed()
        assertEquals(AudioPlaybackPhase.Preparing, controller.state.value.phase)
        current.prepared(12_000)
        controller.play()
        old.failed()
        assertEquals(AudioPlaybackPhase.Playing, controller.state.value.phase)
        assertEquals(12_000, controller.state.value.durationMillis)
        controller.dispose()
        current.prepared(1)
        current.completed()
        current.failed()
        controller.play(); controller.prepare(); controller.pollPosition()
        assertEquals(AudioPlaybackPhase.Released, controller.state.value.phase)
        assertEquals(1, current.starts)
        assertEquals(1, current.releases)
    }

    @Test fun deniedAudioFocusNeverStartsTheClipAndCanBeRetried() {
        focus.granted = false
        val controller = controller()
        controller.prepare(); players.single().prepared(10_000)
        controller.play()
        assertEquals(AudioPlaybackPhase.Failed, controller.state.value.phase)
        assertEquals(0, players.single().starts)
        assertTrue(controller.state.value.error!!.contains("another app"))
        focus.granted = true
        controller.prepare(); players.last().prepared(10_000)
        controller.play()
        assertEquals(AudioPlaybackPhase.Playing, controller.state.value.phase)
        controller.dispose()
    }

    @Test fun invalidDurationAndBackendFailuresDoNotLeaveAnActiveOrUnreleasablePlayer() {
        val controller = controller()
        controller.prepare(); players.single().prepared(0)
        assertEquals(AudioPlaybackPhase.Failed, controller.state.value.phase)
        controller.prepare(); players.last().prepared(10_000)
        players.last().failStart = true
        controller.play()
        assertEquals(AudioPlaybackPhase.Failed, controller.state.value.phase)
        assertEquals(1, players.last().releases)
        controller.dispose()
    }

    @Test fun voiceLabelsAndDurationsPreserveOrdinaryAudioAndLegacyFiles() {
        val audio = SharedFile("id", "group", "uploader", "Student", "clip.m4a", "audio/mp4", 100,
            Instant.EPOCH)
        assertEquals("Audio", sharedAudioLabel(audio))
        assertEquals("Voice message", sharedAudioLabel(audio.copy(isVoiceRecording = true, durationMillis = 65_000)))
        assertNull(sharedAudioLabel(audio.copy(mimeType = "application/pdf")))
        assertEquals("0:00", formatAudioDuration(-1))
        assertEquals("1:05", formatAudioDuration(65_999))
        assertEquals("10:00", formatAudioDuration(600_000))
    }

    private class FakeFocus : PlaybackAudioFocus {
        var granted = true
        var interrupted: () -> Unit = {}
        override fun request(onInterrupted: () -> Unit): Boolean { interrupted = onInterrupted; return granted }
        override fun abandon() = Unit
    }

    private class FakeBackend : AudioPlaybackBackend {
        var prepared: (Int) -> Unit = {}
        var completed: () -> Unit = {}
        var failed: () -> Unit = {}
        var position = 0
        var starts = 0
        var pauses = 0
        var releases = 0
        var failStart = false
        override fun prepare(sourcePath: String, onPrepared: (Int) -> Unit, onCompleted: () -> Unit, onError: () -> Unit) {
            prepared = onPrepared; completed = onCompleted; failed = onError
        }
        override fun start() { if (failStart) error("Failed start"); starts++ }
        override fun pause() { pauses++ }
        override fun seekTo(positionMillis: Int) { position = positionMillis }
        override fun positionMillis(): Int = position
        override fun release() { releases++ }
    }
}
