package au.edu.unimelb.campuscompanion.sensing.orientation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CompassHeadingDetector(
    context: Context
) : SensorEventListener {

    companion object {

        /*
         * Low-pass filter coefficient.
         *
         * This helps reduce sensor noise so the compass
         * heading does not jump too much between samples.
         */
        private const val FILTER_ALPHA = 0.8f
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(
            Sensor.TYPE_ACCELEROMETER
        )

    private val magnetometer: Sensor? =
        sensorManager.getDefaultSensor(
            Sensor.TYPE_MAGNETIC_FIELD
        )

    // ---------------------------------------------------------
    // Filtered sensor values
    // ---------------------------------------------------------

    private val gravityValues =
        FloatArray(3)

    private val magneticValues =
        FloatArray(3)

    private var hasGravity =
        false

    private var hasMagnetic =
        false

    // ---------------------------------------------------------
    // Rotation / orientation arrays
    // ---------------------------------------------------------

    private val rotationMatrix =
        FloatArray(9)

    private val orientationValues =
        FloatArray(3)

    // ---------------------------------------------------------
    // Output state
    // ---------------------------------------------------------

    private val _sample =
        MutableStateFlow<CompassSample?>(null)

    val sample: StateFlow<CompassSample?> =
        _sample.asStateFlow()

    private val _headingDegrees =
        MutableStateFlow<Float?>(null)

    val headingDegrees: StateFlow<Float?> =
        _headingDegrees.asStateFlow()

    // ---------------------------------------------------------
    // Start
    // ---------------------------------------------------------

    fun start() {

        if (accelerometer == null) {

            Log.w(
                "CompassHeading",
                "Accelerometer is not available on this device"
            )

            return
        }

        if (magnetometer == null) {

            Log.w(
                "CompassHeading",
                "Magnetometer is not available on this device"
            )

            return
        }

        sensorManager.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_NORMAL
        )

        sensorManager.registerListener(
            this,
            magnetometer,
            SensorManager.SENSOR_DELAY_NORMAL
        )

        Log.d(
            "CompassHeading",
            "Compass heading detector started"
        )
    }

    // ---------------------------------------------------------
    // Stop
    // ---------------------------------------------------------

    fun stop() {

        sensorManager.unregisterListener(this)

        hasGravity =
            false

        hasMagnetic =
            false

        _headingDegrees.value =
            null

        _sample.value =
            null

        Log.d(
            "CompassHeading",
            "Compass heading detector stopped"
        )
    }

    // ---------------------------------------------------------
    // Sensor events
    // ---------------------------------------------------------

    override fun onSensorChanged(
        event: SensorEvent?
    ) {

        if (event == null) {
            return
        }

        when (event.sensor.type) {

            Sensor.TYPE_ACCELEROMETER -> {

                applyLowPassFilter(
                    input = event.values,
                    output = gravityValues
                )

                hasGravity =
                    true
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {

                applyLowPassFilter(
                    input = event.values,
                    output = magneticValues
                )

                hasMagnetic =
                    true
            }

            else -> {
                return
            }
        }

        calculateHeading(
            event.timestamp
        )
    }

    // ---------------------------------------------------------
    // Calculate heading
    // ---------------------------------------------------------

    private fun calculateHeading(
        timestampNanos: Long
    ) {

        if (
            !hasGravity ||
            !hasMagnetic
        ) {
            return
        }

        /*
         * Build the rotation matrix using:
         *
         * accelerometer gravity vector
         * +
         * Earth's magnetic field vector
         */
        val success =
            SensorManager.getRotationMatrix(
                rotationMatrix,
                null,
                gravityValues,
                magneticValues
            )

        if (!success) {

            Log.w(
                "CompassHeading",
                "Unable to calculate rotation matrix"
            )

            return
        }

        /*
         * orientationValues:
         *
         * [0] = azimuth
         * [1] = pitch
         * [2] = roll
         */
        SensorManager.getOrientation(
            rotationMatrix,
            orientationValues
        )

        val azimuthRadians =
            orientationValues[0]

        var heading =
            Math.toDegrees(
                azimuthRadians.toDouble()
            ).toFloat()

        /*
         * Android can return:
         *
         * -180° ... +180°
         *
         * Convert this into:
         *
         * 0° ... 360°
         */
        if (heading < 0f) {
            heading += 360f
        }

        _headingDegrees.value =
            heading

        _sample.value =
            CompassSample(
                headingDegrees = heading,
                timestampNanos = timestampNanos
            )

        Log.d(
            "CompassHeading",
            "heading=${heading.toInt()}°"
        )
    }

    // ---------------------------------------------------------
    // Low-pass filter
    // ---------------------------------------------------------

    private fun applyLowPassFilter(
        input: FloatArray,
        output: FloatArray
    ) {

        output[0] =
            FILTER_ALPHA * output[0] +
                    (1f - FILTER_ALPHA) * input[0]

        output[1] =
            FILTER_ALPHA * output[1] +
                    (1f - FILTER_ALPHA) * input[1]

        output[2] =
            FILTER_ALPHA * output[2] +
                    (1f - FILTER_ALPHA) * input[2]
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) {
        // No action required for the current prototype.
    }
}