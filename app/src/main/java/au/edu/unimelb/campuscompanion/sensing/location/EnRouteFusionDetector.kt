package au.edu.unimelb.campuscompanion.sensing.location

import android.util.Log

class EnRouteFusionDetector {

    companion object {

        /*
         * User direction can differ from the exact building
         * bearing because walking paths are not perfectly straight.
         */
        private const val MAX_HEADING_DIFFERENCE_DEGREES = 60.0

        /*
         * Number of consecutive positive decisions required
         * before entering EN_ROUTE.
         */
        private const val ENTER_CONFIRMATION_COUNT = 3

        /*
         * Number of consecutive negative decisions required
         * before leaving EN_ROUTE.
         */
        private const val EXIT_CONFIRMATION_COUNT = 3
    }

    private var stableEnRoute =
        false

    private var positiveCount =
        0

    private var negativeCount =
        0

    fun update(
        isMoving: Boolean,
        isRotating: Boolean,
        distanceTrend: DistanceTrend,
        headingDifferenceDegrees: Double?
    ): EnRouteFusionResult {

        val headingMatches =
            headingDifferenceDegrees != null &&
                    headingDifferenceDegrees <=
                    MAX_HEADING_DIFFERENCE_DEGREES

        val approaching =
            distanceTrend ==
                    DistanceTrend.APPROACHING

        /*
         * Raw fusion rule.
         *
         * User should:
         * - be moving
         * - be getting closer to the building
         * - generally face the destination direction
         * - not be in a strong phone-rotation event
         */
        val rawEnRoute =
            isMoving &&
                    approaching &&
                    headingMatches &&
                    !isRotating

        // ---------------------------------------------------------
        // Hysteresis
        // ---------------------------------------------------------

        if (rawEnRoute) {

            positiveCount += 1

            negativeCount =
                0

            if (
                !stableEnRoute &&
                positiveCount >= ENTER_CONFIRMATION_COUNT
            ) {

                stableEnRoute =
                    true

                positiveCount =
                    0
            }

        } else {

            negativeCount += 1

            positiveCount =
                0

            if (
                stableEnRoute &&
                negativeCount >= EXIT_CONFIRMATION_COUNT
            ) {

                stableEnRoute =
                    false

                negativeCount =
                    0
            }
        }

        val result =
            EnRouteFusionResult(
                rawEnRoute =
                    rawEnRoute,
                stableEnRoute =
                    stableEnRoute,
                isMoving =
                    isMoving,
                isRotating =
                    isRotating,
                distanceTrend =
                    distanceTrend,
                headingDifferenceDegrees =
                    headingDifferenceDegrees
            )

        Log.d(
            "EnRouteFusion",
            "rawEnRoute=${result.rawEnRoute}, " +
                    "stableEnRoute=${result.stableEnRoute}, " +
                    "moving=$isMoving, " +
                    "rotating=$isRotating, " +
                    "distanceTrend=$distanceTrend, " +
                    "headingDifference=" +
                    "${headingDifferenceDegrees?.toInt() ?: "unknown"}°, " +
                    "positiveCount=$positiveCount, " +
                    "negativeCount=$negativeCount"
        )

        return result
    }

    fun isEnRoute(): Boolean {
        return stableEnRoute
    }

    fun reset() {

        stableEnRoute =
            false

        positiveCount =
            0

        negativeCount =
            0
    }
}