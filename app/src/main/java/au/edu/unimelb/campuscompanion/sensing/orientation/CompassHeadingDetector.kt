package au.edu.unimelb.campuscompanion.sensing.orientation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** Accelerometer + magnetometer heading. The caller owns start/stop with its lifecycle. */
class CompassHeadingDetector(
    context: Context,
    private val displayRotation: () -> Int = { Surface.ROTATION_0 }
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    val isAvailable: Boolean get() = accelerometer != null && magnetometer != null

    private val gravity = FloatArray(3)
    private val magnetic = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val displayMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var gravityAt = 0L
    private var magneticAt = 0L
    private var magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var running = false
    private val mutableSample = MutableStateFlow<CompassSample?>(null)
    val sample: StateFlow<CompassSample?> = mutableSample.asStateFlow()
    private val mutableHeading = MutableStateFlow<Float?>(null)
    val headingDegrees: StateFlow<Float?> = mutableHeading.asStateFlow()

    fun start(): Boolean {
        if (running) return true
        if (!isAvailable) return false
        val gravityStarted = sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        val magneticStarted = sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL)
        running = gravityStarted && magneticStarted
        if (!running) stop()
        return running
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        running = false
        gravityAt = 0L
        magneticAt = 0L
        magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        mutableHeading.value = null
        mutableSample.value = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !running) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                filter(event.values, gravity, gravityAt == 0L)
                gravityAt = event.timestamp
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                filter(event.values, magnetic, magneticAt == 0L)
                magneticAt = event.timestamp
                magneticAccuracy = event.accuracy
            }
            else -> return
        }
        if (gravityAt == 0L || magneticAt == 0L) return
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, gravity, magnetic)) {
            mutableSample.value = null
            mutableHeading.value = null
            return
        }
        val rotation = displayRotation()
        val (x, y) = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rotationMatrix, x, y, displayMatrix)
        SensorManager.getOrientation(displayMatrix, orientation)
        val heading = ((Math.toDegrees(orientation[0].toDouble()).toFloat() % 360f) + 360f) % 360f
        mutableHeading.value = heading
        mutableSample.value = CompassSample(
            headingDegrees = heading,
            // A stream from just one sensor must not keep an old reading looking fresh.
            timestampNanos = minOf(gravityAt, magneticAt),
            accuracy = magneticAccuracy,
            tiltDegrees = Math.toDegrees(maxOf(abs(orientation[1]), abs(orientation[2])).toDouble()).toFloat(),
            displayRotation = rotation
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magneticAccuracy = accuracy
            mutableSample.value = mutableSample.value?.copy(accuracy = accuracy)
        }
    }

    private fun filter(input: FloatArray, output: FloatArray, first: Boolean) {
        for (index in 0..2) {
            output[index] = if (first) input[index] else FILTER_ALPHA * output[index] + (1f - FILTER_ALPHA) * input[index]
        }
    }

    private companion object {
        const val FILTER_ALPHA = 0.8f
    }
}
