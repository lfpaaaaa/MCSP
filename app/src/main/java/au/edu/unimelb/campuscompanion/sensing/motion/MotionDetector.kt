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
import kotlin.math.sqrt

class MotionDetector(context: Context) : SensorEventListener {

    companion object {
        private const val ALPHA = 0.8f

        //private const val GRAVITY = 9.81
        //private const val MOVEMENT_THRESHOLD = 1.2

        private const val WINDOW_SIZE = 25

        private const val VARIANCE_THRESHOLD = 1e-8

        private const val MEDIAN_WINDOW_SIZE = 5

        private const val STEP_THRESHOLD = 0.8 // linearMagnitude 超过这个值，才可能算一步
        private const val MIN_STEP_INTERVAL_NANOS = 250_000_000L // 两个 step peak 至少间隔 250ms
        private const val STEP_WINDOW_NANOS = 3_000_000_000L // 用最近 3 秒的数据估算步频

        // 暂时认为这是合理的 walking step frequency 范围
        private const val MIN_WALKING_STEP_FREQUENCY = 1.0
        private const val MAX_WALKING_STEP_FREQUENCY = 3.5
    }

    private val linearWindow =
        ArrayDeque<Double>()

    private val movementHistory =
        ArrayDeque<Boolean>()

    private val stepTimestamps =
        ArrayDeque<Long>()

    private var lastStepTimestampNanos: Long = 0L

    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _motionSample =
        MutableStateFlow<MotionSample?>(null)

    val motionSample: StateFlow<MotionSample?> =
        _motionSample.asStateFlow()

    private val _isMoving =
        MutableStateFlow(false)

    val isMoving: StateFlow<Boolean> =
        _isMoving.asStateFlow()

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Low-pass filter to estimate gravity.
        gravityX =
            ALPHA * gravityX +
                    (1 - ALPHA) * x

        gravityY =
            ALPHA * gravityY +
                    (1 - ALPHA) * y

        gravityZ =
            ALPHA * gravityZ +
                    (1 - ALPHA) * z

        // Remove gravity from raw acceleration.
        val linearX = x - gravityX
        val linearY = y - gravityY
        val linearZ = z - gravityZ

        val linearMagnitude =
            sqrt(
                (
                        linearX * linearX +
                                linearY * linearY +
                                linearZ * linearZ
                        ).toDouble()
            )

        detectStep(
            linearMagnitude = linearMagnitude,
            timestampNanos = event.timestamp
        )

        val stepFrequency =
            calculateStepFrequency(event.timestamp)

        linearWindow.addLast(linearMagnitude)

        if (linearWindow.size > WINDOW_SIZE) {
            linearWindow.removeFirst()
        }

        val variance = calculateVariance(linearWindow)

        val magnitude =
            sqrt(
                (x * x + y * y + z * z).toDouble()
            )

        _motionSample.value =
            MotionSample(
                x = x,
                y = y,
                z = z,
                magnitude = magnitude,
                timestampNanos = event.timestamp
            )

        // Temporary old movement classifier.
        //val movementAmount =
        //    abs(magnitude - GRAVITY)

        //_isMoving.value =
        //    movementAmount > MOVEMENT_THRESHOLD

        if (linearWindow.size == WINDOW_SIZE) {

            val hasMovement =
                variance > VARIANCE_THRESHOLD

            val hasWalkingFrequency =
                stepFrequency in
                        MIN_WALKING_STEP_FREQUENCY..MAX_WALKING_STEP_FREQUENCY

            val rawMoving =
                hasMovement && hasWalkingFrequency

            movementHistory.addLast(rawMoving)

            if (movementHistory.size > MEDIAN_WINDOW_SIZE) {
                movementHistory.removeFirst()
            }

            if (movementHistory.size == MEDIAN_WINDOW_SIZE) {
                _isMoving.value =
                    medianBoolean(movementHistory)
            }

            Log.d(
                "MotionDetector",
                "variance=$variance, " +
                        "stepFrequency=$stepFrequency, " +
                        "rawMoving=$rawMoving, " +
                        "isMoving=${_isMoving.value}"
            )
        }
    }

    private fun calculateVariance(
        values: Collection<Double>
    ): Double {

        if (values.isEmpty()) {
            return 0.0
        }

        val mean =
            values.average()

        return values
            .map { value ->
                val diff = value - mean
                diff * diff
            }
            .average()
    }

    private fun medianBoolean(
        values: Collection<Boolean>
    ): Boolean {

        val trueCount =
            values.count { it }

        return trueCount > values.size / 2
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) {
    }

    private fun detectStep(
        linearMagnitude: Double,
        timestampNanos: Long
    ) {

        val enoughTimeSinceLastStep =
            timestampNanos - lastStepTimestampNanos >=
                    MIN_STEP_INTERVAL_NANOS

        if (
            linearMagnitude >= STEP_THRESHOLD &&
            enoughTimeSinceLastStep
        ) {
            stepTimestamps.addLast(timestampNanos)
            lastStepTimestampNanos = timestampNanos
        }

        while (
            stepTimestamps.isNotEmpty() &&
            timestampNanos - stepTimestamps.first() >
            STEP_WINDOW_NANOS
        ) {
            stepTimestamps.removeFirst()
        }
    }

    private fun calculateStepFrequency(
        currentTimestampNanos: Long
    ): Double {

        while (
            stepTimestamps.isNotEmpty() &&
            currentTimestampNanos - stepTimestamps.first() >
            STEP_WINDOW_NANOS
        ) {
            stepTimestamps.removeFirst()
        }

        val windowSeconds =
            STEP_WINDOW_NANOS / 1_000_000_000.0

        return stepTimestamps.size / windowSeconds
    }

}