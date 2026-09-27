package au.edu.unimelb.campuscompanion.sensing.location

object ArrivalDetector {

    const val DEFAULT_ARRIVAL_RADIUS_METERS = 75.0

    fun hasArrived(
        distanceMeters: Double,
        arrivalRadiusMeters: Double = DEFAULT_ARRIVAL_RADIUS_METERS
    ): Boolean {
        return distanceMeters <= arrivalRadiusMeters
    }
}