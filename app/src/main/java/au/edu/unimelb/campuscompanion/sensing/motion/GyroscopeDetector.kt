package au.edu.unimelb.campuscompanion.sensing.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import kotlin.math.sqrt

class GyroscopeDetector(
    context: Context
) : SensorEventListener {

    companion object {

        /*
         * Temporary development threshold.
         *
         * Unit: rad/s
         *
         * This value should be calibrated later using
         * recorded traces from a physical Android device.
         */
        private const val ROTATION_THRESHOLD = 1.5

        /*
         * We do not immediately trust one sensor sample.
         *
         * Instead, the latest five rotation decisions are
         * stored and a majority vote is used.
         */
        private const val FILTER_WINDOW_SIZE = 5
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gyroscope: Sensor? =
        sensorManager.getDefaultSensor(
            Sensor.TYPE_GYROSCOPE
        )

    // ---------------------------------------------------------
    // Raw gyroscope sample
    // ---------------------------------------------------------

    private val _sample =
        MutableStateFlow<GyroscopeSample?>(null)

    val sample: StateFlow<GyroscopeSample?> =
        _sample.asStateFlow()

    // ---------------------------------------------------------
    // Stable rotation state
    // ---------------------------------------------------------

    private val _isRotating =
        MutableStateFlow(false)

    val isRotating: StateFlow<Boolean> =
        _isRotating.asStateFlow()

    /*
     * Stores recent raw rotation decisions.
     *
     * Example:
     *
     * false
     * true
     * true
     * true
     * false
     *
     * Majority = true
     */
    private val rotationHistory =
        ArrayDeque<Boolean>()

    // ---------------------------------------------------------
    // Start
    // ---------------------------------------------------------

    fun start() {

        if (gyroscope == null) {

            Log.w(
                "Gyroscope",
                "Gyroscope sensor is not available on this device"
            )

            return
        }

        sensorManager.registerListener(
            this,
            gyroscope,
            SensorManager.SENSOR_DELAY_NORMAL
        )

        Log.d(
            "Gyroscope",
            "Gyroscope detector started"
        )
    }

    // ---------------------------------------------------------
    // Stop
    // ---------------------------------------------------------

    fun stop() {

        sensorManager.unregisterListener(this)

        rotationHistory.clear()

        _isRotating.value = false

        Log.d(
            "Gyroscope",
            "Gyroscope detector stopped"
        )
    }

    // ---------------------------------------------------------
    // Sensor update
    // ---------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent?) {

        if (event == null) {
            return
        }

        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) {
            return
        }

        val x =
            event.values[0]

        val y =
            event.values[1]

        val z =
            event.values[2]

        /*
         * Overall angular velocity:
         *
         * sqrt(x² + y² + z²)
         *
         * Unit: rad/s
         */
        val rotationMagnitude =
            sqrt(
                (
                        x * x +
                                y * y +
                                z * z
                        ).toDouble()
            )

        // ---------------------------------------------------------
        // Publish raw sample
        // ---------------------------------------------------------

        _sample.value =
            GyroscopeSample(
                x = x,
                y = y,
                z = z,
                rotationMagnitude = rotationMagnitude,
                timestampNanos = event.timestamp
            )

        // ---------------------------------------------------------
        // Raw posture-change decision
        // ---------------------------------------------------------

        val rawRotating =
            rotationMagnitude > ROTATION_THRESHOLD

        // ---------------------------------------------------------
        // Short-window filtering
        // ---------------------------------------------------------

        rotationHistory.addLast(
            rawRotating
        )

        if (
            rotationHistory.size >
            FILTER_WINDOW_SIZE
        ) {
            rotationHistory.removeFirst()
        }

        /*
         * Wait until the window is full before updating
         * the stable state.
         */
        if (
            rotationHistory.size ==
            FILTER_WINDOW_SIZE
        ) {

            val rotatingVotes =
                rotationHistory.count {
                    it
                }

            val stableRotating =
                rotatingVotes >
                        FILTER_WINDOW_SIZE / 2

            _isRotating.value =
                stableRotating
        }

        Log.d(
            "GyroscopeDetector",
            "rotationMagnitude=$rotationMagnitude, " +
                    "rawRotating=$rawRotating, " +
                    "isRotating=${_isRotating.value}"
        )
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) {
        // No action required for the current prototype.
    }
}