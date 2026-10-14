package au.edu.unimelb.campuscompanion.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import java.net.URLEncoder

fun openGoogleMaps(context: Context, destination: ClassDestination, mode: TravelMode) {
    openMapsUri(context, Uri.parse(googleMapsDirectionsUrl(destination.point, mode)))
}

/** The explicit travelmode also applies when Maps is opened in a browser. */
internal fun googleMapsDirectionsUrl(destination: GeoPoint, mode: TravelMode): String {
    val point = URLEncoder.encode("${destination.latitude},${destination.longitude}", "UTF-8")
    val travelMode = when (mode) {
        TravelMode.Walking -> "walking"
        TravelMode.Driving -> "driving"
        TravelMode.PublicTransport -> "transit"
    }
    return "https://www.google.com/maps/dir/?api=1&destination=$point&travelmode=$travelMode&dir_action=navigate"
}

private fun openMapsUri(context: Context, uri: Uri) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps"))
    } catch (_: ActivityNotFoundException) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(context, "Install Google Maps or a web browser to continue.", Toast.LENGTH_LONG).show() }
    }
}
