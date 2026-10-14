package au.edu.unimelb.campuscompanion.sensing.orientation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min

class CompassHeadingDetector(
    context: Context,
    private val displayRotation: () -> Int = { Surface.ROTATION_0 }
) : SensorEventListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    val isAvailable: Boolean get() = accelerometer != null && magnetometer != null
    private var magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private val displayMatrix = FloatArray(9)
    private var started = false
    private var activationNanos = 0L
    private var lastGravityObservationNanos = 0L
    private var lastMagneticObservationNanos = 0L
    private var gravityTimestampNanos = 0L
    private var magneticTimestampNanos = 0L
    private var hasGravity = false
    private var hasMagnetic = false
    private val gravityValues = FloatArray(3)
    private val magneticValues = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)

    private val _sample = MutableStateFlow<CompassSample?>(null)
    val sample: StateFlow<CompassSample?> = _sample.asStateFlow()
    private val _headingDegrees = MutableStateFlow<Float?>(null)
    val headingDegrees: StateFlow<Float?> = _headingDegrees.asStateFlow()

    fun start(onStarted: (Boolean) -> Unit = {}) = onMain {
        if (started) {
            onStarted(true)
            return@onMain
        }
        resetSamples()
        val gravitySensor = accelerometer
        val magneticSensor = magnetometer
        if (gravitySensor == null || magneticSensor == null) {
            onStarted(false)
            return@onMain
        }
        activationNanos = SystemClock.elapsedRealtimeNanos()
        try {
            val gravityRegistered = sensorManager.registerListener(
                this, gravitySensor, SensorManager.SENSOR_DELAY_NORMAL, mainHandler
            )
            val magneticRegistered = sensorManager.registerListener(
                this, magneticSensor, SensorManager.SENSOR_DELAY_NORMAL, mainHandler
            )
            started = gravityRegistered && magneticRegistered
            if (!started) Log.w("CompassHeading", "Compass sensor registration failed")
        } catch (error: RuntimeException) {
            started = false
            Log.w("CompassHeading", "Unable to register compass sensors", error)
        }
        if (!started) {
            sensorManager.unregisterListener(this)
            resetSamples()
        }
        onStarted(started)
    }

    fun stop() = onMain {
        started = false
        sensorManager.unregisterListener(this)
        resetSamples()
        activationNanos = 0L
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val sensorType = event.sensor.type
        if (sensorType != Sensor.TYPE_ACCELEROMETER &&
            sensorType != Sensor.TYPE_MAGNETIC_FIELD
        ) return
        val values = event.values.copyOf()
        val timestampNanos = event.timestamp
        val accuracy = event.accuracy
        onMain { acceptSample(sensorType, values, timestampNanos, accuracy) }
    }

    private fun acceptSample(
        sensorType: Int,
        values: FloatArray,
        timestampNanos: Long,
        accuracy: Int
    ) {
        if (!started) return
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val isGravity = sensorType == Sensor.TYPE_ACCELEROMETER
        val previousTimestamp = if (isGravity) lastGravityObservationNanos
            else lastMagneticObservationNanos
        val counterpartTimestamp = if (isGravity) lastMagneticObservationNanos
            else lastGravityObservationNanos
        if (timestampNanos < activationNanos || timestampNanos <= previousTimestamp ||
            timestampNanos > nowNanos || nowNanos - timestampNanos > MAX_SAMPLE_GAP_NANOS
        ) return
        if (isGravity) lastGravityObservationNanos = timestampNanos
            else lastMagneticObservationNanos = timestampNanos
        if (values.size < 3 || values.take(3).any { !it.isFinite() } ||
            accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
        ) {
            resetFilters()
            return
        }
        if ((previousTimestamp != 0L &&
                    timestampNanos - previousTimestamp > MAX_SAMPLE_GAP_NANOS) ||
            (counterpartTimestamp != 0L &&
                    timestampNanos - counterpartTimestamp > MAX_SAMPLE_GAP_NANOS)
        ) resetFilters()

        if (isGravity) {
            applyLowPassFilter(values, gravityValues, hasGravity)
            hasGravity = true
            gravityTimestampNanos = timestampNanos
        } else {
            applyLowPassFilter(values, magneticValues, hasMagnetic)
            hasMagnetic = true
            magneticAccuracy = accuracy
            magneticTimestampNanos = timestampNanos
        }
        calculateHeading(nowNanos)
    }

    private fun calculateHeading(nowNanos: Long) {
        if (!hasGravity || !hasMagnetic ||
            nowNanos - gravityTimestampNanos > MAX_SAMPLE_GAP_NANOS ||
            nowNanos - magneticTimestampNanos > MAX_SAMPLE_GAP_NANOS
        ) {
            clearHeading()
            return
        }
        if (!SensorManager.getRotationMatrix(
                rotationMatrix, null, gravityValues, magneticValues
            )
        ) {
            clearHeading()
            return
        }
        val rotation = displayRotation()
        val (x, y) = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        if (!SensorManager.remapCoordinateSystem(rotationMatrix, x, y, displayMatrix)) {
            clearHeading()
            return
        }
        SensorManager.getOrientation(displayMatrix, orientationValues)
        val heading = ((Math.toDegrees(orientationValues[0].toDouble()) + 360.0) % 360.0)
            .toFloat()
        if (!heading.isFinite()) {
            clearHeading()
            return
        }
        _headingDegrees.value = heading
        // One live stream must not make an old counterpart appear newly measured.
        _sample.value = CompassSample(
            headingDegrees = heading,
            timestampNanos = min(gravityTimestampNanos, magneticTimestampNanos),
            accuracy = magneticAccuracy,
            tiltDegrees = Math.toDegrees(maxOf(abs(orientationValues[1]), abs(orientationValues[2])).toDouble()).toFloat(),
            displayRotation = rotation
        )
    }

    private fun applyLowPassFilter(input: FloatArray, output: FloatArray, initialized: Boolean) {
        for (index in 0..2) {
            output[index] = if (initialized) {
                FILTER_ALPHA * output[index] + (1f - FILTER_ALPHA) * input[index]
            } else input[index]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val sensorType = sensor?.type ?: return
        onMain {
            if (started && sensorType == Sensor.TYPE_MAGNETIC_FIELD) {
                magneticAccuracy = accuracy
                _sample.value = _sample.value?.copy(accuracy = accuracy)
            }
            if (started && accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE &&
                (sensorType == Sensor.TYPE_ACCELEROMETER ||
                        sensorType == Sensor.TYPE_MAGNETIC_FIELD)
            ) resetFilters()
        }
    }

    private fun resetSamples() {
        lastGravityObservationNanos = 0L
        lastMagneticObservationNanos = 0L
        resetFilters()
    }

    private fun resetFilters() {
        hasGravity = false
        hasMagnetic = false
        magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        gravityTimestampNanos = 0L
        magneticTimestampNanos = 0L
        gravityValues.fill(0f)
        magneticValues.fill(0f)
        rotationMatrix.fill(0f)
        orientationValues.fill(0f)
        clearHeading()
    }

    private fun clearHeading() {
        _headingDegrees.value = null
        _sample.value = null
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) action() else mainHandler.post(action)
    }

    private companion object {
        const val FILTER_ALPHA = 0.8f
        const val MAX_SAMPLE_GAP_NANOS = 5_000_000_000L
    }
}
