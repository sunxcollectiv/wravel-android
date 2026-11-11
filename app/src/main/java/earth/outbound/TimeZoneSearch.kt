//
//  TimeZoneSearch.kt
//  outbound
//

package earth.outbound

import java.time.Instant
import java.time.ZoneId

/**
 * Finds time zones by what people type: a city ("tokyo", "new york"), a region
 * ("europe/lon") or an abbreviation ("JST"), and labels them for display.
 */
object TimeZoneSearch {
    /** The city part of a zone's identifier, e.g. "New York" for America/New_York. */
    fun city(zone: ZoneId): String = zone.id.substringAfterLast('/').replace("_", " ")

    /**
     * A friendly label for a zone, e.g. "Tokyo (JST)", using the abbreviation in
     * effect at `date` (so EST vs EDT is right for the item's day).
     */
    fun label(zone: ZoneId, date: Instant = Instant.now()): String =
        "${city(zone)} (${WallClock.zoneAbbreviation(zone, date)})"

    /**
     * Up to `limit` zones matching `query`, best first: cities starting with it, then
     * abbreviations equal to it, then identifiers containing it anywhere. Empty for a
     * blank query.
     */
    fun matches(
        query: String,
        date: Instant = Instant.now(),
        limit: Int = 4,
        identifiers: List<String> = knownTimeZoneIdentifiers,
    ): List<ZoneId> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val zones = identifiers.mapNotNull(::timeZone)

        fun rank(zone: ZoneId): Int? {
            val city = city(zone).lowercase()
            val identifier = zone.id.lowercase().replace("_", " ")
            if (city.startsWith(needle)) return 0
            if (WallClock.zoneAbbreviation(zone, date).lowercase() == needle) return 1
            if (identifier.contains(needle)) return 2
            return null
        }

        return zones
            .mapNotNull { zone -> rank(zone)?.let { zone to it } }
            .sortedWith(compareBy({ it.second }, { city(it.first) }))
            .take(limit)
            .map { it.first }
    }

    /**
     * The zones people live in, by identifier: the device's "Region/City" zones plus
     * GMT, leaving out its legacy aliases ("US/Eastern", "Etc/GMT+9", "EST5EDT"…).
     */
    val knownTimeZoneIdentifiers: List<String> by lazy {
        ZoneId.getAvailableZoneIds()
            .filter { it == "GMT" || it.substringBefore('/', missingDelimiterValue = "") in regions }
            .sorted()
    }

    private val regions = setOf(
        "Africa", "America", "Antarctica", "Arctic", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific",
    )
}
