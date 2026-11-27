//
//  ItineraryPages.kt
//  wravel
//

package app.wravel

import java.time.LocalDate

/** The side of the trip's dates a page collects, for the Before and After pages. */
enum class ItinerarySide(val label: String) {
    Before("Before"),
    After("After"),
}

/**
 * The itinerary as the trip screen pages through it: a Before page, the trip's own
 * days, then an After page.
 *
 * The trip's days run from the first date one of the group's trips covers to the
 * last (gap days and uncovered days between trips included, as the server sends
 * them). Everything the server puts on a date before or after that, whatever the
 * date, is gathered onto the Before or After page instead of a page per day. A
 * transit that touches one of those dates is shown there in full, every leg and end
 * of it, even the end that lands within the trip. Before's lodging is the stay for
 * the night before the trip; After has none.
 */
class ItineraryPages(days: List<ItineraryDay>) {
    val before: ItineraryDay
    val tripDays: List<ItineraryDay>
    val after: ItineraryDay

    /** Every page in order. */
    val all: List<ItineraryDay> get() = listOf(before) + tripDays + listOf(after)

    init {
        val first = days.indexOfFirst { it.tripId != null }
        val last = days.indexOfLast { it.tripId != null }
        if (first < 0) {
            // A server that doesn't say which trip a date is on: every date is a trip day.
            tripDays = days
            before = page(ItinerarySide.Before, outside = emptyList(), all = days, date = days.firstOrNull()?.let { shift(it.date, -1) })
            after = page(ItinerarySide.After, outside = emptyList(), all = days, date = days.lastOrNull()?.let { shift(it.date, 1) })
        } else {
            tripDays = days.subList(first, last + 1)
            val leading = days.subList(0, first)
            val eve = shift(days[first].date, -1)
            before = page(
                ItinerarySide.Before, outside = leading, all = days, date = eve,
                // The night before the trip, if the server sent that date.
                lodging = leading.lastOrNull { it.date == eve }?.lodging ?: emptyList(),
            )
            after = page(ItinerarySide.After, outside = days.subList(last + 1, days.size), all = days, date = shift(days[last].date, 1))
        }
    }

    private companion object {
        /**
         * One side's page: each item on the `outside` dates, in the order they come up,
         * a transit with all its entries from `all`, anything else with just its entries
         * on those dates. `date` is the date next to the trip on that side, for an item
         * added from the page, and `lodging` what its lodging bar shows.
         */
        fun page(
            side: ItinerarySide,
            outside: List<ItineraryDay>,
            all: List<ItineraryDay>,
            date: String?,
            lodging: List<LodgingStay> = emptyList(),
        ): ItineraryDay {
            val order = mutableListOf<String>()
            val entriesByItem = mutableMapOf<String, MutableList<Pair<String, ItineraryEntry>>>()
            for (day in outside) {
                for (entry in day.entries) {
                    val id = entry.item.id
                    val seen = id in entriesByItem
                    if (!seen) order += id
                    if (entry.item.kind != TripItemKind.Transit) {
                        entriesByItem.getOrPut(id) { mutableListOf() } += day.date to entry
                    } else if (!seen) {
                        // The whole journey, from wherever its entries fall.
                        entriesByItem[id] = all.flatMap { d ->
                            d.entries.filter { it.item.id == id }.map { d.date to it }
                        }.toMutableList()
                    }
                }
            }
            val flattened = order.flatMap { entriesByItem[it] ?: emptyList() }
            val entryDates = mutableMapOf<String, String>()
            for ((entryDate, entry) in flattened) entryDates.putIfAbsent(entry.id, entryDate)
            return ItineraryDay(
                date = date ?: outside.firstOrNull()?.date ?: "",
                dayNumber = if (side == ItinerarySide.Before) ItineraryDay.beforePageNumber else ItineraryDay.afterPageNumber,
                lodging = lodging,
                entries = flattened.map { it.second },
                side = side,
                entryDates = entryDates,
            )
        }

        /** The "yyyy-MM-dd" date `days` away from `date`. */
        fun shift(date: String, days: Long): String? = isoDate(date)?.plusDays(days)?.toString()
    }
}

/**
 * The calendar date an entry on a Before or After page falls on, which the page itself
 * doesn't have one of.
 */
fun ItineraryDay.dateOf(entry: ItineraryEntry): LocalDate? = entryDates[entry.id]?.let { isoDate(it) }

