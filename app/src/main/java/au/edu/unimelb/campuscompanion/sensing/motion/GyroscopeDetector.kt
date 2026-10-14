package au.edu.unimelb.campuscompanion.sensing.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

class GyroscopeDetector(context: Context) : SensorEventListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var started = false
    private var activationNanos = 0L
    private var lastObservationNanos = 0L
    private val rotationHistory = ArrayDeque<Boolean>()

    private val _sample = MutableStateFlow<GyroscopeSample?>(null)
    val sample: StateFlow<GyroscopeSample?> = _sample.asStateFlow()
    private val _isRotating = MutableStateFlow(false)
    val isRotating: StateFlow<Boolean> = _isRotating.asStateFlow()
    private val _classification = MutableStateFlow<RotationClassification?>(null)
    val classification: StateFlow<RotationClassification?> = _classification.asStateFlow()

    fun start() = onMain {
        if (started) return@onMain
        resetSamples()
        val sensor = gyroscope ?: return@onMain
        activationNanos = SystemClock.elapsedRealtimeNanos()
        try {
            started = sensorManager.registerListener(
                this, sensor, SensorManager.SENSOR_DELAY_NORMAL, mainHandler
            )
            if (!started) Log.w("GyroscopeDetector", "Gyroscope registration failed")
        } catch (error: RuntimeException) {
            started = false
            Log.w("GyroscopeDetector", "Unable to register gyroscope", error)
        }
        if (!started) {
            sensorManager.unregisterListener(this)
            resetSamples()
        }
    }

    fun stop() = onMain {
        started = false
        sensorManager.unregisterListener(this)
        resetSamples()
        activationNanos = 0L
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        val values = event.values.copyOf()
        val timestampNanos = event.timestamp
        onMain { acceptSample(values, timestampNanos) }
    }

    private fun acceptSample(values: FloatArray, timestampNanos: Long) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        if (!started || values.size < 3 || values.take(3).any { !it.isFinite() } ||
            timestampNanos < activationNanos || timestampNanos <= lastObservationNanos ||
            timestampNanos > nowNanos || nowNanos - timestampNanos > MAX_SAMPLE_GAP_NANOS
        ) return
        if (lastObservationNanos != 0L &&
            timestampNanos - lastObservationNanos > MAX_SAMPLE_GAP_NANOS
        ) resetSamples()
        lastObservationNanos = timestampNanos
        val x = values[0]
        val y = values[1]
        val z = values[2]
        val rotationMagnitude = sqrt((x * x + y * y + z * z).toDouble())
        _sample.value = GyroscopeSample(x, y, z, rotationMagnitude, timestampNanos)
        rotationHistory.addLast(rotationMagnitude > ROTATION_THRESHOLD)
        if (rotationHistory.size > FILTER_WINDOW_SIZE) rotationHistory.removeFirst()
        if (rotationHistory.size == FILTER_WINDOW_SIZE) {
            val rotating = rotationHistory.count { it } > FILTER_WINDOW_SIZE / 2
            _isRotating.value = rotating
            _classification.value = RotationClassification(rotating, timestampNanos)
        }
    }

    private fun resetSamples() {
        rotationHistory.clear()
        lastObservationNanos = 0L
        _sample.value = null
        _classification.value = null
        _isRotating.value = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) action() else mainHandler.post(action)
    }

    private companion object {
        // Provisional rad/s threshold; physical-device traces still need to calibrate it.
        const val ROTATION_THRESHOLD = 1.5
        const val FILTER_WINDOW_SIZE = 5
        const val MAX_SAMPLE_GAP_NANOS = 5_000_000_000L
    }
}
