//
//  ItineraryZoneChanges.kt
//  outbound
//

package earth.outbound

import java.time.Instant
import java.time.ZoneId

/**
 * Where the clock changes along a trip, e.g. landing after a flight across zones.
 *
 * Cards show bare wall clocks, so a marker above the first entry in a new zone tells
 * you which clock the times from there on are in, until the next change. The trip is
 * walked in order across days, so a change carried overnight marks the top of the
 * next day. Zoneless entries take the current zone, so they never mark a change, and
 * zones that agree on the offset (New York and Toronto) count as the same clock.
 */
object ItineraryZoneChanges {
    /**
     * The zone each marked entry's clock changes to, keyed by entry id. The trip's
     * first zone isn't marked: there's no change to it.
     */
    fun changes(days: List<ItineraryDay>): Map<String, ZoneId> {
        val changes = mutableMapOf<String, ZoneId>()
        var current: ZoneId? = null
        for (entry in days.flatMap { it.entries }) {
            val zone = entry.timeZone ?: continue
            val previous = current
            if (previous != null && !sameClock(previous, zone, entry.displayInstant)) {
                changes[entry.id] = zone
            }
            current = zone
        }
        return changes
    }

    private fun sameClock(a: ZoneId, b: ZoneId, instant: Instant): Boolean =
        a.rules.getOffset(instant) == b.rules.getOffset(instant)

    /** A zone's label for its marker, e.g. "Los Angeles · PST". */
    fun label(zone: ZoneId, instant: Instant): String =
        "${TimeZoneSearch.city(zone)} · ${WallClock.zoneAbbreviation(zone, instant)}"
}
