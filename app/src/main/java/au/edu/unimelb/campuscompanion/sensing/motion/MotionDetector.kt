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

class MotionDetector(context: Context) : SensorEventListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var started = false
    private var activationNanos = 0L
    private var lastObservationNanos = 0L
    private var hasGravity = false
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var lastStepTimestampNanos = 0L
    private val linearWindow = ArrayDeque<Double>()
    private val movementHistory = ArrayDeque<Boolean>()
    private val stepTimestamps = ArrayDeque<Long>()

    private val _motionSample = MutableStateFlow<MotionSample?>(null)
    val motionSample: StateFlow<MotionSample?> = _motionSample.asStateFlow()
    private val _isMoving = MutableStateFlow(false)
    val isMoving: StateFlow<Boolean> = _isMoving.asStateFlow()
    private val _classification = MutableStateFlow<MotionClassification?>(null)
    val classification: StateFlow<MotionClassification?> = _classification.asStateFlow()

    fun start() = onMain {
        if (started) return@onMain
        resetSamples()
        val sensor = accelerometer ?: return@onMain
        activationNanos = SystemClock.elapsedRealtimeNanos()
        try {
            started = sensorManager.registerListener(
                this, sensor, SensorManager.SENSOR_DELAY_NORMAL, mainHandler
            )
            if (!started) Log.w("MotionDetector", "Accelerometer registration failed")
        } catch (error: RuntimeException) {
            started = false
            Log.w("MotionDetector", "Unable to register accelerometer", error)
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
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
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
        if (!hasGravity) {
            gravityX = x
            gravityY = y
            gravityZ = z
            hasGravity = true
        } else {
            gravityX = ALPHA * gravityX + (1 - ALPHA) * x
            gravityY = ALPHA * gravityY + (1 - ALPHA) * y
            gravityZ = ALPHA * gravityZ + (1 - ALPHA) * z
        }
        val linearX = x - gravityX
        val linearY = y - gravityY
        val linearZ = z - gravityZ
        val linearMagnitude = sqrt(
            (linearX * linearX + linearY * linearY + linearZ * linearZ).toDouble()
        )
        detectStep(linearMagnitude, timestampNanos)
        val stepFrequency = stepTimestamps.size / (STEP_WINDOW_NANOS / 1_000_000_000.0)
        linearWindow.addLast(linearMagnitude)
        if (linearWindow.size > WINDOW_SIZE) linearWindow.removeFirst()
        _motionSample.value = MotionSample(
            x, y, z,
            sqrt((x * x + y * y + z * z).toDouble()),
            timestampNanos
        )
        if (linearWindow.size < WINDOW_SIZE) return

        val mean = linearWindow.average()
        val variance = linearWindow.sumOf { value -> (value - mean) * (value - mean) } /
            linearWindow.size
        val rawMoving = variance > VARIANCE_THRESHOLD &&
            stepFrequency in MIN_WALKING_STEP_FREQUENCY..MAX_WALKING_STEP_FREQUENCY
        movementHistory.addLast(rawMoving)
        if (movementHistory.size > MEDIAN_WINDOW_SIZE) movementHistory.removeFirst()
        if (movementHistory.size == MEDIAN_WINDOW_SIZE) {
            val moving = movementHistory.count { it } > movementHistory.size / 2
            _isMoving.value = moving
            // Timestamp every complete window, including an unchanged classification.
            _classification.value = MotionClassification(moving, timestampNanos)
        }
    }

    private fun detectStep(linearMagnitude: Double, timestampNanos: Long) {
        if (linearMagnitude >= STEP_THRESHOLD &&
            timestampNanos - lastStepTimestampNanos >= MIN_STEP_INTERVAL_NANOS
        ) {
            stepTimestamps.addLast(timestampNanos)
            lastStepTimestampNanos = timestampNanos
        }
        while (stepTimestamps.isNotEmpty() &&
            timestampNanos - stepTimestamps.first() > STEP_WINDOW_NANOS
        ) stepTimestamps.removeFirst()
    }

    private fun resetSamples() {
        linearWindow.clear()
        movementHistory.clear()
        stepTimestamps.clear()
        lastStepTimestampNanos = 0L
        lastObservationNanos = 0L
        hasGravity = false
        gravityX = 0f
        gravityY = 0f
        gravityZ = 0f
        _motionSample.value = null
        _classification.value = null
        _isMoving.value = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) action() else mainHandler.post(action)
    }

    private companion object {
        const val ALPHA = 0.8f
        const val WINDOW_SIZE = 25
        const val VARIANCE_THRESHOLD = 1e-8
        const val MEDIAN_WINDOW_SIZE = 5
        const val STEP_THRESHOLD = 0.8
        const val MIN_STEP_INTERVAL_NANOS = 250_000_000L
        const val STEP_WINDOW_NANOS = 3_000_000_000L
        const val MIN_WALKING_STEP_FREQUENCY = 1.0
        const val MAX_WALKING_STEP_FREQUENCY = 3.5
        const val MAX_SAMPLE_GAP_NANOS = 5_000_000_000L
    }
}
