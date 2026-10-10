package au.edu.unimelb.campuscompanion.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSourcesTest {
    @Test
    fun everySourceIsListedOnceWithSecureLinks() {
        val sources = DataSources.all

        assertEquals(
            listOf("OpenStreetMap and OSRM", "Transitous", "Open-Meteo", "Campus building outlines"),
            sources.map { it.name }
        )
        val urls = sources.flatMap { source -> source.links.map { it.url } }
        assertEquals(urls.size, urls.toSet().size)
        urls.forEach { url -> assertTrue(url, url.startsWith("https://")) }
        sources.forEach { source ->
            assertTrue(source.name, source.credit.endsWith("."))
            assertTrue(source.name, source.usage.endsWith("."))
        }
    }

    @Test
    fun theRoutingCreditFollowsTheOpenStreetMapAndOsrmTerms() {
        val routing = DataSources.routing

        assertTrue(routing.credit.startsWith(DataSources.OSM_CREDIT))
        assertEquals(
            listOf(DataSources.OSM_COPYRIGHT_URL, DataSources.OSM_FIX_THE_MAP_URL, DataSources.OSRM_ABOUT_URL),
            routing.links.map { it.url }
        )
        assertEquals("Fix the map", routing.links[1].label)
    }

    @Test
    fun theWeatherCreditUsesTheWordingOpenMeteoAsksFor() {
        val weather = DataSources.weather

        assertTrue(weather.credit.startsWith("Weather data by Open-Meteo.com"))
        assertTrue(weather.credit.contains("CC BY 4.0"))
        assertEquals(listOf(DataSources.OPEN_METEO_URL, DataSources.OPEN_METEO_LICENCE_URL), weather.links.map { it.url })
    }

    @Test
    fun publicTransportLinksToTheTransitousSourcesPage() {
        assertEquals(listOf(DataSources.TRANSITOUS_SOURCES_URL), DataSources.transit.links.map { it.url })
    }
}
