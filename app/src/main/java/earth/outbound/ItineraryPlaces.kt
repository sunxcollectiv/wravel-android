//
//  ItineraryPlaces.kt
//  outbound
//

package earth.outbound

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The itinerary's places, one record per place id, so every card showing a place (an
 * item's departure and arrival, a stay's check-in and check-out, a layover, the same
 * café on two days) reads the same copy.
 *
 * The itinerary payload embeds a copy of a place in each item that references it.
 * Cards look their place up here by id instead of reading that embedded copy, so
 * patching a place (e.g. from `POST /places/refresh`) updates every card at once:
 * `byId` is Compose state, so the cards reading it are redrawn.
 */
class ItineraryPlaces() {
    var byId by mutableStateOf(mapOf<String, Place>())
        private set

    /**
     * Indexes every place the days reference. If the payload carries one id more
     * than once, the first copy wins (they're the same server record).
     */
    constructor(days: List<ItineraryDay>) : this() {
        byId = days.flatMap { it.places }.reversed().associateBy { it.id }
    }

    /** Replaces the stored copy of each given place (keyed by id) with the newer one. */
    fun update(places: Map<String, Place>) {
        byId = byId + places
    }

    /** The stored copy of `place`, matched by id; `place` itself if it isn't stored. */
    fun current(place: Place?): Place? = place?.let { byId[it.id] ?: it }

    /** `item` with each of its places (and its legs' places) swapped for the stored copy. */
    fun resolving(item: TripItem): TripItem =
        item.copy(
            place = current(item.place),
            endPlace = current(item.endPlace),
            segments = item.segments.map { it.copy(place = current(it.place), endPlace = current(it.endPlace)) },
        )

    override fun equals(other: Any?): Boolean = other is ItineraryPlaces && other.byId == byId

    override fun hashCode(): Int = byId.hashCode()
}

/**
 * Every place this day references: its entries' items and their legs, layover
 * stops, and lodging. May repeat a place.
 */
val ItineraryDay.places: List<Place>
    get() {
        val items = entries.map { it.item } + lodging.map { it.item }
        val itemPlaces = items.flatMap { item ->
            listOf(item.place, item.endPlace) + item.segments.flatMap { listOf(it.place, it.endPlace) }
        }
        return (itemPlaces + entries.map { it.layoverPlace }).filterNotNull()
    }
