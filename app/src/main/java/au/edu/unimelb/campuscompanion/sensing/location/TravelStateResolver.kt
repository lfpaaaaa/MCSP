package au.edu.unimelb.campuscompanion.sensing.location

object TravelStateResolver {

    fun resolve(
        currentState: TravelState,
        distanceMeters: Double
    ): TravelState {

        val arrived = ArrivalDetector.hasArrived(distanceMeters)

        if (arrived) {
            return TravelState.ARRIVED
        }

        return currentState
    }
}