//
//  TimeZoneSearchTests.kt
//  outboundTests
//

package earth.outbound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TimeZoneSearchTests {
    /** A winter day, so northern zones use their standard abbreviations. */
    private val winter = Calendar.sundayFirst.date(LocalDate.of(2026, 12, 1))

    private fun ids(query: String): List<String> =
        TimeZoneSearch.matches(query, date = winter).map { it.id }

    @Test
    fun findsCityByPrefixIgnoringCaseAndUnderscores() {
        assertEquals("Asia/Tokyo", ids("tokyo").firstOrNull())
        assertEquals("America/New_York", ids("New Y").firstOrNull())
    }

    @Test
    fun findsByRegionPath() {
        assertTrue(ids("europe/lond").contains("Europe/London"))
    }

    @Test
    fun cityPrefixRanksAboveOtherMatches() {
        // "ma" starts cities like Madrid and also sits inside others like Panama; every
        // city-prefix match should come before any match that only contains it.
        val results = TimeZoneSearch.matches("ma", date = winter, limit = 100)
        val startsWith = results.map { TimeZoneSearch.city(it).lowercase().startsWith("ma") }
        assertTrue(startsWith.contains(false))
        assertEquals(startsWith.sortedByDescending { it }, startsWith)
    }

    @Test
    fun blankQueryHasNoResults() {
        assertTrue(ids("   ").isEmpty())
    }

    @Test
    fun limitsResults() {
        assertEquals(3, TimeZoneSearch.matches("a", date = winter, limit = 3).size)
    }

    @Test
    fun labelsWithCityAndAbbreviation() {
        val zone = ZoneId.of("America/New_York")
        assertEquals("New York (EST)", TimeZoneSearch.label(zone, date = winter))
    }
}
