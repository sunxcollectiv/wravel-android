//
//  TripStore.kt
//  outbound
//

package earth.outbound

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import earth.outbound.networking.APIClient
import earth.outbound.networking.PlaceImageCache
import earth.outbound.networking.ResponseCache
import earth.outbound.networking.localizedDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toKotlinDuration

/**
 * Loads and holds the signed-in user's trips for the list screen.
 *
 * Starts from the copy saved by the last load, so the list shows at once and still
 * shows offline; `load()` then replaces it with the server's.
 */
class TripStore(
    private val api: APIClient = APIClient.shared,
    /** Where saved trips and their photos are kept, for clearing out unused ones. */
    private val cache: ResponseCache = ResponseCache.shared,
) {
    var trips by mutableStateOf(api.cached<List<Trip>>(TripsAPI.list) ?: emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)

    /**
     * Whether `trips` is the saved copy and the last try to refresh it failed: the
     * server couldn't be reached, so what's showing may be out of date.
     */
    var isShowingSavedCopy by mutableStateOf(false)
        private set

    /** When the saved copy now showing was fetched, for telling how out of date it is. */
    var savedDate by mutableStateOf(if (trips.isEmpty()) null else api.cachedDate(TripsAPI.list))
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The background fetch of missing area photos, replaced by each load. */
    private var photoRefresh: Job? = null

    /** The background save of upcoming trips' itineraries, replaced by each load. */
    private var itinerarySave: Job? = null

    /** Fetches the trip list from `GET /trips`. */
    suspend fun load() {
        isLoading = true
        try {
            trips = api.send<List<Trip>>(TripsAPI.list)
            errorMessage = null
            isShowingSavedCopy = false
            savedDate = null
            // The list is already showing; photos patch in when they arrive, and the
            // load (and pull-to-refresh) doesn't wait for them.
            refreshAreaPhotos(trips)
            removeUnusedSavedCopies()
            saveUpcomingItineraries()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = error.localizedDescription
            isShowingSavedCopy = savedDate != null
        } finally {
            isLoading = false
        }
    }

    /** Swaps in an edited trip so its card updates without a full reload. */
    fun update(trip: Trip) {
        if (trips.none { it.id == trip.id }) return
        trips = trips.map { if (it.id == trip.id) trip else it }
        saveTrips()
        refreshAreaPhotos(listOf(trip))
    }

    /** Drops a just-deleted trip so its card goes without a full reload. */
    fun remove(id: String) {
        trips = trips.filterNot { it.id == id }
        saveTrips()
    }

    /** Inserts a just-created trip at the top so it shows without a full reload. */
    fun add(trip: Trip) {
        trips = listOf(trip) + trips
        saveTrips()
        refreshAreaPhotos(listOf(trip))
    }

    /**
     * Saves the list as changed here over the server's last copy, so an edit made
     * just before losing the connection is what shows offline.
     */
    private fun saveTrips() {
        api.storeCached(trips, TripsAPI.list)
    }

    /**
     * Deletes what's saved that no trip on the list uses any more: the itineraries of
     * trips that were deleted or that the user was taken off, and cover photos an
     * area has since replaced. Run after a load, when the list is the server's own.
     */
    private fun removeUnusedSavedCopies() {
        val kept = mutableSetOf(TripsAPI.list.cacheKey)
        for (trip in trips) {
            kept.add(TripsAPI.itinerary(tripId = trip.id).cacheKey)
            trip.areaCoverPhoto?.let { kept.add(PlaceImageCache.diskKey(it.url)) }
        }
        cache.removeAll(except = kept)
    }

    /**
     * Downloads the itineraries of upcoming and in-progress trips in the background,
     * one at a time, so each opens offline even if it hasn't been opened lately (the
     * itinerary endpoint saves its response). Ones saved in the last
     * `itineraryFreshness` are skipped. Best-effort: a failed fetch keeps the older copy.
     */
    private fun saveUpcomingItineraries() {
        val upcoming = Trip.upcomingAndPast(trips).upcoming
        itinerarySave?.cancel()
        itinerarySave = scope.launch {
            for (trip in upcoming) {
                val endpoint = TripsAPI.itinerary(tripId = trip.id)
                val saved = api.cachedDate(endpoint)
                if (saved != null && Duration.between(saved, Instant.now()).toKotlinDuration() < itineraryFreshness) {
                    continue
                }
                try {
                    api.sendIgnoringResponse(endpoint)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                }
                ensureActive()
            }
        }
    }

    /**
     * Asks the server for photos of the areas these trips flagged as missing them (in
     * batches of `AreasAPI.refreshPhotosBatchLimit`), patching each batch in as it
     * lands. Only each trip's first area is asked for, since that's the only one a
     * card shows. Runs in the background so callers don't wait on it. Best-effort: a
     * failed batch leaves its areas without photos.
     */
    private fun refreshAreaPhotos(trips: List<Trip>) {
        val ids = trips
            .mapNotNull { trip ->
                trip.areas?.firstOrNull()?.id?.takeIf { trip.areasMissingPhotos?.contains(it) == true }
            }
            .distinct()
        if (ids.isEmpty()) return

        photoRefresh?.cancel()
        photoRefresh = scope.launch {
            for (batch in ids.chunked(AreasAPI.refreshPhotosBatchLimit)) {
                val response = try {
                    api.send<AreasAPI.RefreshPhotosResponse>(AreasAPI.refreshPhotos(areaIds = batch))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    continue
                }
                ensureActive()
                patchAreas(response.areas)
            }
        }
    }

    /**
     * Swaps refreshed areas into every trip that has them, and drops those that now
     * have photos from the trip's missing list.
     */
    private fun patchAreas(refreshed: Map<String, Area>) {
        trips = trips.map { trip ->
            val areas = trip.areas ?: return@map trip
            trip.copy(
                areas = areas.map { refreshed[it.id] ?: it },
                areasMissingPhotos = trip.areasMissingPhotos?.filterNot { refreshed[it]?.coverPhoto != null },
            )
        }
        saveTrips()
    }

    companion object {
        /**
         * How long a saved itinerary counts as fresh, so reloading the list doesn't
         * re-download every upcoming trip each time.
         */
        private val itineraryFreshness = 15.minutes
    }
}
