package au.edu.unimelb.campuscompanion.data.building

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import au.edu.unimelb.campuscompanion.data.model.GeoPoint

class BuildingLocationRepository(
    private val context: Context
) : BuildingLookup {

    private val buildings: List<BuildingLocation> by lazy {
        loadBuildings()
    }

    override fun findByLocationCode(locationCode: String): BuildingLocation? {
        val normalizedCode = normalizeLocationCode(locationCode)

        return buildings.firstOrNull {
            it.locCode.equals(normalizedCode, ignoreCase = true)
        }
    }

    override fun getAllBuildings(): List<BuildingLocation> {
        return buildings
    }

    private fun loadBuildings(): List<BuildingLocation> {
        val jsonText =
            context.assets
                .open("uom_building_outlines.geojson")
                .bufferedReader()
                .use { it.readText() }

        val root = JSONObject(jsonText)
        val features = root.getJSONArray("features")

        val result = mutableListOf<BuildingLocation>()

        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)

            val properties = feature.getJSONObject("properties")
            val geometry = feature.getJSONObject("geometry")

            val locCode = properties.optString("loc_code")
            val buildingNumber = properties.optString("building_number")
            val campusCode = properties.optString("campus_code")
            val name = properties.optString("NAME")
            val address = properties.optString("address")

            if (locCode.isBlank()) {
                continue
            }

            val centroid = calculateCentroid(geometry) ?: continue

            result.add(
                BuildingLocation(
                    locCode = locCode,
                    buildingNumber = buildingNumber,
                    campusCode = campusCode,
                    name = name,
                    address = address,
                    location = GeoPoint(
                        latitude = centroid.first,
                        longitude = centroid.second
                    )
                )
            )
        }

        return result
    }

    private fun normalizeLocationCode(locationCode: String): String {
        return locationCode
            .trim()
            .replace("-", ";")
    }

    private fun calculateCentroid(
        geometry: JSONObject
    ): Pair<Double, Double>? {

        val type = geometry.optString("type")
        val coordinates = geometry.optJSONArray("coordinates") ?: return null

        return when (type) {
            "Polygon" -> {
                val ring = coordinates.optJSONArray(0) ?: return null
                calculateAveragePoint(ring)
            }

            "MultiPolygon" -> {
                val polygon = coordinates.optJSONArray(0) ?: return null
                val ring = polygon.optJSONArray(0) ?: return null
                calculateAveragePoint(ring)
            }

            else -> null
        }
    }

    private fun calculateAveragePoint(
        ring: JSONArray
    ): Pair<Double, Double>? {

        if (ring.length() == 0) {
            return null
        }

        var longitudeSum = 0.0
        var latitudeSum = 0.0
        var count = 0

        for (i in 0 until ring.length()) {
            val point = ring.optJSONArray(i) ?: continue

            if (point.length() < 2) {
                continue
            }

            val longitude = point.optDouble(0)
            val latitude = point.optDouble(1)

            longitudeSum += longitude
            latitudeSum += latitude
            count++
        }

        if (count == 0) {
            return null
        }

        return Pair(
            latitudeSum / count,
            longitudeSum / count
        )
    }
}