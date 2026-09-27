package au.edu.unimelb.campuscompanion.data.fake

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.geo.StraightLineEstimator
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
import kotlinx.coroutines.delay
import java.time.Instant

/** [EtaRepository] that always uses the offline straight-line estimate. */
class FakeEtaRepository(
    private val latencyMillis: Long = FakeData.DEFAULT_LATENCY_MILLIS,
    private val clock: () -> Instant = Instant::now
) : EtaRepository {

    override suspend fun estimate(
        origin: GeoPoint,
        destination: GeoPoint,
        mode: TravelMode
    ): Result<TravelEstimate> {
        delay(latencyMillis)
        return Result.success(StraightLineEstimator.estimate(origin, destination, clock()))
    }
}
