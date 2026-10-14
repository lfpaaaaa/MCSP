package au.edu.unimelb.campuscompanion.data.recording

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.nio.ByteBuffer

object AndroidRecording {
    fun createDraftStore(context: Context): RecordingDraftStore =
        RecordingDraftStore(File(context.applicationContext.filesDir, "recordings"), ::audioDuration)

    fun createController(
        context: Context,
        userId: String,
        groupId: String,
        scope: CoroutineScope,
        store: RecordingDraftStore = createDraftStore(context)
    ): RecordingController = RecordingController(userId, groupId, store,
        RecordingDeviceFactory { output, onLimit, onError ->
            AndroidRecordingDevice(context.applicationContext, output, onLimit, onError)
        }, scope, SystemClock::elapsedRealtime)

    /** Check an AAC track with readable packets and its actual M4A duration before restoring/sending. */
    private fun audioDuration(file: File): Long? {
        val metadata = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        return try {
            metadata.setDataSource(file.path)
            if (metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes") return null
            val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return null
            if (duration !in MIN_RECORDING_DURATION_MILLIS..MAX_RECORDING_DURATION_MILLIS + MAX_RECORDING_CODEC_PADDING_MILLIS) return null
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm"
            } ?: return null
            extractor.selectTrack(track)
            // Metadata alone may remain in a damaged file. Require a real encoded AAC sample.
            if (extractor.readSampleData(ByteBuffer.allocate(64 * 1024), 0) <= 0) return null
            duration
        } catch (_: Exception) { null }
        finally {
            runCatching { extractor.release() }
            runCatching { metadata.release() }
        }
    }
}

private class AndroidRecordingDevice(
    context: Context,
    output: File,
    onDurationLimit: () -> Unit,
    onError: () -> Unit
) : RecordingDevice {
    private val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()
    private var released = false

    init {
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            recorder.setAudioEncodingBitRate(96_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setMaxDuration(MAX_RECORDING_DURATION_MILLIS.toInt())
            recorder.setOutputFile(output.path)
            recorder.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onDurationLimit()
            }
            recorder.setOnErrorListener { _, _, _ -> onError() }
            recorder.prepare()
        } catch (error: Exception) {
            runCatching { recorder.release() }
            released = true
            throw error
        }
    }

    override fun start() = recorder.start()
    override fun amplitude(): Int = recorder.maxAmplitude
    override fun stop() = recorder.stop()
    override fun release() {
        if (released) return
        released = true
        recorder.setOnErrorListener(null)
        recorder.setOnInfoListener(null)
        recorder.release()
    }
}
