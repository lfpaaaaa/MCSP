package au.edu.unimelb.campuscompanion.data.building

import au.edu.unimelb.campuscompanion.data.model.GeoPoint

data class BuildingLocation(
    val locCode: String,
    val buildingNumber: String,
    val campusCode: String,
    val name: String,
    val address: String,
    val location: GeoPoint
)