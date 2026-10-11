package au.edu.unimelb.campuscompanion.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import au.edu.unimelb.campuscompanion.data.TravelMode

fun openGoogleMaps(context: Context, destination: ClassDestination, mode: TravelMode) {
    val uri = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
        .appendQueryParameter("api", "1")
        .appendQueryParameter("destination", "${destination.point.latitude},${destination.point.longitude}")
        .appendQueryParameter("travelmode", when (mode) {
            TravelMode.Walking -> "walking"
            TravelMode.Driving -> "driving"
            TravelMode.PublicTransport -> "transit"
        }).appendQueryParameter("dir_action", "navigate").build()
    openMapsUri(context, uri)
}

private fun openMapsUri(context: Context, uri: Uri) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps"))
    } catch (_: ActivityNotFoundException) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(context, "Install Google Maps or a web browser to continue.", Toast.LENGTH_LONG).show() }
    }
}
