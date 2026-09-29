package au.edu.unimelb.campuscompanion.sensing.location

object TravelStateResolver {

    const val DEFAULT_BUFFER_MINUTES = 5

    fun resolve(
        currentState: TravelState,
        distanceMeters: Double,
        minutesUntilClass: Int? = null,
        estimatedTravelMinutes: Int? = null,
        bufferMinutes: Int = DEFAULT_BUFFER_MINUTES,
        isMoving: Boolean = false
    ): TravelState {

        // Arrival has highest priority
        if (ArrivalDetector.hasArrived(distanceMeters)) {
            return TravelState.ARRIVED
        }

        if (
            currentState == TravelState.UPCOMING_CLASS &&
            minutesUntilClass != null &&
            estimatedTravelMinutes != null
        ) {
            val leaveThreshold =
                estimatedTravelMinutes + bufferMinutes

            if (minutesUntilClass <= leaveThreshold) {
                return TravelState.SHOULD_LEAVE_SOON
            }
        }
        if (
            currentState == TravelState.SHOULD_LEAVE_SOON &&
            isMoving
        ) {
            return TravelState.EN_ROUTE
        }

        return currentState
    }
}