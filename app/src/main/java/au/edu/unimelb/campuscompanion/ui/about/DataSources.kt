package au.edu.unimelb.campuscompanion.ui.about

import au.edu.unimelb.campuscompanion.ui.text.UiText
import au.edu.unimelb.campuscompanion.R

/** A page the user can open from a data source's credit. */
data class DataSourceLink(val label: UiText, val url: String)

/**
 * A service or data set the app depends on, with the credit its terms ask for. The About
 * section on the Profile screen lists all of them; screens that show the data carry the short
 * credit as well.
 */
data class DataSource(
    val name: UiText,
    /** The credit, worded as the provider asks for it. */
    val credit: UiText,
    /** What the app uses the data for. */
    val usage: UiText,
    val links: List<DataSourceLink> = emptyList()
)

/** The data sources the app shows or derives information from. */
object DataSources {
    val OSM_CREDIT = UiText(R.string.about_openstreetmap_contributors)
    const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
    const val OSM_FIX_THE_MAP_URL = "https://www.openstreetmap.org/fixthemap"
    const val OSRM_ABOUT_URL = "https://routing.openstreetmap.de/about.html"
    const val TRANSITOUS_SOURCES_URL = "https://transitous.org/sources/"
    val OPEN_METEO_CREDIT = UiText(R.string.about_weather_data_by_open_meteo_com)
    const val OPEN_METEO_URL = "https://open-meteo.com/"
    const val OPEN_METEO_LICENCE_URL = "https://open-meteo.com/en/licence"

    /** Map data and the walking and driving router. */
    val routing = DataSource(
        name = UiText(R.string.about_openstreetmap_and_osrm),
        credit = UiText(R.string.about_available_under_the_open_database_licence_walking_and, OSM_CREDIT),
        usage = UiText(R.string.about_the_distance_and_travel_time_to_your_next),
        links = listOf(
            DataSourceLink(UiText(R.string.about_copyright_and_licence), OSM_COPYRIGHT_URL),
            DataSourceLink(UiText(R.string.about_section_fix_the_map), OSM_FIX_THE_MAP_URL),
            DataSourceLink(UiText(R.string.about_about_the_routing_servers), OSRM_ABOUT_URL)
        )
    )

    /** The public transport router. */
    val transit = DataSource(
        name = UiText(R.string.about_transitous),
        credit = UiText(R.string.about_public_transport_journeys_are_planned_by_transitous_a),
        usage = UiText(R.string.about_public_transport_times_once_the_service_has_been),
        links = listOf(DataSourceLink(UiText(R.string.about_data_sources), TRANSITOUS_SOURCES_URL))
    )

    /** Weather at the class's building. */
    val weather = DataSource(
        name = UiText(R.string.about_open_meteo),
        credit = UiText(R.string.about_licensed_cc_by_4_0, OPEN_METEO_CREDIT),
        usage = UiText(R.string.about_rain_or_heat_at_your_class_s_building),
        links = listOf(
            DataSourceLink(UiText(R.string.about_open_meteo_com), OPEN_METEO_URL),
            DataSourceLink(UiText(R.string.about_licence), OPEN_METEO_LICENCE_URL)
        )
    )

    /** The building outlines bundled with the app. */
    val buildings = DataSource(
        name = UiText(R.string.about_campus_building_outlines),
        credit = UiText(R.string.about_outlines_and_location_codes_of_the_university_of),
        usage = UiText(R.string.about_finding_the_building_of_each_class_from_the)
    )

    val googleMaps = DataSource(
        name = UiText(R.string.class_navigation_google_maps),
        credit = UiText(R.string.about_maps_and_routes_provided_by_google_maps),
        usage = UiText(R.string.about_class_navigation_opens_google_maps_with_the_teaching),
        links = listOf(
            DataSourceLink(UiText(R.string.about_google_maps_terms), "https://maps.google.com/help/terms_maps/"),
            DataSourceLink(UiText(R.string.about_google_privacy_policy), "https://policies.google.com/privacy")
        )
    )

    val all: List<DataSource> = listOf(googleMaps, routing, transit, weather, buildings)
}
