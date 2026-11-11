//
//  LastVisitedDay.kt
//  outbound
//

package earth.outbound

import java.time.Duration
import java.time.Instant

/**
 * Remembers the day last viewed in each trip's itinerary, so reopening a trip soon
 * after lands back on that day. Kept in memory only, and forgotten an hour after the
 * trip was left.
 */
object LastVisitedDay {
    private val lifetime = Duration.ofHours(1)

    private class Entry(val dayNumber: Int, val recordedAt: Instant)

    private val entries = mutableMapOf<String, Entry>()

    /** The day last viewed in the trip, unless it was recorded over an hour ago. */
    fun dayNumber(tripId: String): Int? {
        val entry = entries[tripId] ?: return null
        if (Duration.between(entry.recordedAt, Instant.now()) >= lifetime) {
            entries.remove(tripId)
            return null
        }
        return entry.dayNumber
    }

    fun record(dayNumber: Int, tripId: String) {
        entries[tripId] = Entry(dayNumber, Instant.now())
    }
}
