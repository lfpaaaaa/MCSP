package au.edu.unimelb.campuscompanion.sensing.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TravelStateManager(
    initialState: TravelState = TravelState.UPCOMING_CLASS
) {

    private val _state = MutableStateFlow(initialState)

    val state: StateFlow<TravelState> =
        _state.asStateFlow()

    fun update(
        distanceMeters: Double,
        minutesUntilClass: Int? = null,
        estimatedTravelMinutes: Int? = null,
        isMoving: Boolean = false,
        bufferMinutes: Int = TravelStateResolver.DEFAULT_BUFFER_MINUTES
    ) {
        _state.value = TravelStateResolver.resolve(
            currentState = _state.value,
            distanceMeters = distanceMeters,
            minutesUntilClass = minutesUntilClass,
            estimatedTravelMinutes = estimatedTravelMinutes,
            bufferMinutes = bufferMinutes,
            isMoving = isMoving
        )
    }
}