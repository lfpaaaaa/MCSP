package au.edu.unimelb.campuscompanion.ui.about

/** A page the user can open from a data source's credit. */
data class DataSourceLink(val label: String, val url: String)

/**
 * A service or data set the app depends on, with the credit its terms ask for. The About
 * section on the Profile screen lists all of them; screens that show the data carry the short
 * credit as well.
 */
data class DataSource(
    val name: String,
    /** The credit, worded as the provider asks for it. */
    val credit: String,
    /** What the app uses the data for. */
    val usage: String,
    val links: List<DataSourceLink> = emptyList()
)

/** The data sources the app shows or derives information from. */
object DataSources {
    const val OSM_CREDIT = "© OpenStreetMap contributors"
    const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
    const val OSM_FIX_THE_MAP_URL = "https://www.openstreetmap.org/fixthemap"
    const val OSRM_ABOUT_URL = "https://routing.openstreetmap.de/about.html"
    const val TRANSITOUS_SOURCES_URL = "https://transitous.org/sources/"
    const val OPEN_METEO_CREDIT = "Weather data by Open-Meteo.com"
    const val OPEN_METEO_URL = "https://open-meteo.com/"
    const val OPEN_METEO_LICENCE_URL = "https://open-meteo.com/en/licence"

    /** Map data and the walking and driving router. */
    val routing = DataSource(
        name = "OpenStreetMap and OSRM",
        credit = "$OSM_CREDIT, available under the Open Database Licence. Walking and driving " +
            "times are routed by the OSRM servers that FOSSGIS e.V. runs for the OpenStreetMap community.",
        usage = "The distance and travel time to your next class.",
        links = listOf(
            DataSourceLink("Copyright and licence", OSM_COPYRIGHT_URL),
            DataSourceLink("Fix the map", OSM_FIX_THE_MAP_URL),
            DataSourceLink("About the routing servers", OSRM_ABOUT_URL)
        )
    )

    /** The public transport router. */
    val transit = DataSource(
        name = "Transitous",
        credit = "Public transport journeys are planned by Transitous, a community-run router " +
            "working from open timetable data, including Transport Victoria's.",
        usage = "Public transport times, once the service has been switched on for this app.",
        links = listOf(DataSourceLink("Data sources", TRANSITOUS_SOURCES_URL))
    )

    /** Weather at the class's building. */
    val weather = DataSource(
        name = "Open-Meteo",
        credit = "$OPEN_METEO_CREDIT, licensed CC BY 4.0.",
        usage = "Rain or heat at your class's building adds a few minutes to the departure reminder.",
        links = listOf(
            DataSourceLink("open-meteo.com", OPEN_METEO_URL),
            DataSourceLink("Licence", OPEN_METEO_LICENCE_URL)
        )
    )

    /** The building outlines bundled with the app. */
    val buildings = DataSource(
        name = "Campus building outlines",
        credit = "Outlines and location codes of the University of Melbourne's campus buildings, " +
            "bundled with the app.",
        usage = "Finding the building of each class from the location code in your timetable " +
            "and noticing when you have arrived."
    )

    val all: List<DataSource> = listOf(routing, transit, weather, buildings)
}
