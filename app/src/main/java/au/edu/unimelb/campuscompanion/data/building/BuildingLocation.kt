package au.edu.unimelb.campuscompanion.data.building

data class BuildingLocation(
    val locCode: String,
    val buildingNumber: String,
    val campusCode: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double
)