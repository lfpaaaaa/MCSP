package au.edu.unimelb.campuscompanion.sensing.location

import android.util.Log
import java.util.ArrayDeque

class DistanceTrendDetector {

    companion object {

        /*
         * Number of recent GPS distance samples
         * used to determine the trend.
         */
        private const val WINDOW_SIZE = 4

        /*
         * Ignore very small changes because GPS
         * measurements naturally contain noise.
         *
         * Temporary development value.
         * Real-device traces should be used later
         * to calibrate this threshold.
         */
        private const val MIN_DISTANCE_CHANGE_METERS = 8.0
    }

    private val distanceHistory =
        ArrayDeque<Double>()

    private var currentTrend =
        DistanceTrend.UNKNOWN

    fun update(
        distanceMeters: Double
    ): DistanceTrend {

        distanceHistory.addLast(
            distanceMeters
        )

        if (
            distanceHistory.size >
            WINDOW_SIZE
        ) {
            distanceHistory.removeFirst()
        }

        if (
            distanceHistory.size <
            WINDOW_SIZE
        ) {

            currentTrend =
                DistanceTrend.UNKNOWN

            return currentTrend
        }

        val firstDistance =
            distanceHistory.first()

        val lastDistance =
            distanceHistory.last()

        val netChange =
            lastDistance -
                    firstDistance

        currentTrend =
            when {

                netChange <=
                        -MIN_DISTANCE_CHANGE_METERS -> {

                    DistanceTrend.APPROACHING
                }

                netChange >=
                        MIN_DISTANCE_CHANGE_METERS -> {

                    DistanceTrend.MOVING_AWAY
                }

                else -> {

                    DistanceTrend.STABLE
                }
            }

        Log.d(
            "DistanceTrend",
            "history=$distanceHistory, " +
                    "netChange=${netChange.toInt()}m, " +
                    "trend=$currentTrend"
        )

        return currentTrend
    }

    fun getCurrentTrend():
            DistanceTrend {

        return currentTrend
    }

    fun reset() {

        distanceHistory.clear()

        currentTrend =
            DistanceTrend.UNKNOWN
    }
}