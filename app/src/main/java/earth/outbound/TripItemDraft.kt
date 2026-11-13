//
//  TripItemDraft.kt
//  outbound
//

package earth.outbound

import earth.outbound.networking.APIClient
import earth.outbound.networking.GooglePlacesClient
import earth.outbound.networking.localizedDescription
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The editable state behind every trip item form, whatever its kind: who it's for,
 * its title, times, places and notes, plus saving and deleting it.
 *
 * Times are held as wall clocks on a neutral canvas (the device zone), so the
 * numbers the user sees are what gets sent as the naive `starts_at`/`ends_at`.
 * The zone is chosen separately, so changing it keeps the wall time and only moves
 * the underlying instant, matching the server's "right time, wrong zone" fix.
 *
 * Built with an existing `item` to edit it, or without one to create a new item of
 * `kind`. A new item starts with no time zones; each side gets one only when it's
 * picked (or comes from a place).
 *
 * A value: a form holds the current draft as state and replaces it with a changed
 * copy (`draft = draft.copy(title = …)`, or what a method like `addLeg()` returns).
 * The suspending `save`, `delete` and `prefillPlaceNames` report their changes
 * through an `update` callback, which applies each to the form's current draft
 * (`{ change -> draft = change(draft) }`), so nothing typed meanwhile is lost.
 * Make one with `TripItemDraft(tripId = …, kind = …)`.
 */
data class TripItemDraft(
    val kind: TripItemKind,

    /** The item being edited, or `null` when creating a new one. */
    val item: TripItem?,

    /**
     * The trip an edited item is on, or the one a new item goes to when its date
     * isn't in `tripsByDate`.
     */
    private val fallbackTripId: String,
    /** The roster for `fallbackTripId`. */
    private val fallbackMembers: List<TripMember>,
    /**
     * For a new item: the trip owning each itinerary day ("yyyy-MM-dd"), so the item
     * is saved to whichever of the user's trips its start date falls in. Empty for
     * an edit, which stays on the item's trip.
     */
    private val tripsByDate: Map<String, Trip>,

    /** The trip's first through last day, shown in the date popup's calendar. */
    val tripDates: ClosedRange<Instant>?,

    /** The itinerary day the form was opened from, marked in the date popup's calendar. */
    val viewedDay: Instant?,

    /** What people who aren't going see of the item. */
    val visibility: TripItemVisibility,
    /** Who's selected, by email: see `selectedMemberIds`. */
    private val selectedEmails: Set<String>? = null,
    /**
     * The selection the item was opened with, so an edit only re-sends who's in when
     * it changed.
     */
    private val originalMemberIds: Set<String>? = null,
    val title: String,
    /** The start wall clock; nil once cleared, which blocks saving (a start is required). */
    val startsAt: Instant?,
    /** The end wall clock; nil means the item has no end time. */
    val endsAt: Instant?,
    /**
     * The start zone, when one is known (the item's, a picked place's, or the trip's
     * default). nil shows no zone and saves the item without one.
     */
    val startZone: ZoneId?,
    /** The end zone; `null` means "follow the start zone" until the user or a place sets it. */
    val endZone: ZoneId?,
    val notes: String,
    /** Whether saving tells the trip's other members about the new or changed item. */
    val notifyMembers: Boolean = false,

    /** The place for this item, picked via the location search → sent as `place_id`. */
    val startPlace: Place?,
    /** The text the location field searches on, kept separate from the item's title. */
    val startPlaceText: String,
    /** The start place's time zone from Google. Set it with `withStartPlaceZone`. */
    val startPlaceZone: ZoneId? = null,
    /**
     * A transit's legs, in order; empty for other kinds. Each has its own places,
     * times and mode, and the item's own start, end and place fields go unused.
     */
    val legs: List<TransitLeg> = emptyList(),

    /**
     * A stay's property type (hotel, vacation rental, …); nil when not picked.
     * Unused for other kinds.
     */
    val propertyType: LodgingType?,
    /**
     * A stay's rooms, in order; empty until one is added. Saved with the item when
     * they've changed.
     */
    val rooms: List<LodgingRoom> = emptyList(),

    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val errorMessage: String? = null,
    /** Bumped on every failed save so the error scrolls into view even when unchanged. */
    val errorScrollTrigger: Int = 0,

    /**
     * For `namedByPlaceSearch` kinds, the name taken as typed in the place search,
     * with no place behind it; saved as the title. Nil while a place is picked or
     * nothing is.
     */
    val typedName: String? = null,
) {
    /**
     * The trip this item saves to: an edited item's own, or for a new one the trip
     * owning its start date.
     */
    val tripId: String get() = targetTrip?.id ?: fallbackTripId

    /** Everyone on that trip, offered in the "who's going" popup. */
    val members: List<TripMember> get() = targetTrip?.members ?: fallbackMembers

    /** The trip owning a new item's start date, when it isn't the fallback. */
    private val targetTrip: Trip?
        get() {
            if (item != null) return null
            val start = (if (hasLegs) legs.firstOrNull()?.startsAt else startsAt) ?: return null
            return tripsByDate[dayKey(start)]
        }

    /**
     * Ids of the trip members this item is for, toggled in the popup (set them with
     * `withSelectedMemberIds`). Nil until seeded on appear, once the signed-in user is
     * known. Kept by email underneath, so when a new item's date moves it to another
     * trip, everyone also on that trip stays selected (as their member row there).
     */
    val selectedMemberIds: Set<String>?
        get() = selectedEmails?.let { emails -> members.filter { it.email in emails }.map { it.id }.toSet() }

    /**
     * The draft for exactly these members. Anyone taken off the item is taken out of
     * their room too.
     */
    fun withSelectedMemberIds(newValue: Set<String>?): TripItemDraft {
        val removed = (selectedMemberIds ?: emptySet()) - (newValue ?: emptySet())
        return copy(
            selectedEmails = newValue?.let { ids -> members.filter { it.id in ids }.map { it.email }.toSet() },
            rooms = if (removed.isEmpty()) {
                rooms
            } else {
                rooms.map { room -> room.copy(occupants = room.occupants.filterNot { it in removed }) }
            },
        )
    }

    /** The draft with the start place's zone from Google; a picked place's zone becomes `startZone`. */
    fun withStartPlaceZone(zone: ZoneId?): TripItemDraft =
        copy(startPlaceZone = zone, startZone = zone ?: startZone)

    val isEditing: Boolean get() = item != null

    /** Whether this kind is made of legs, each with its own places and times (a transit). */
    val hasLegs: Boolean get() = kind == TripItemKind.Transit

    /**
     * Whether a title or a place is enough to save this kind, the server calling it
     * by its place's name when it has no title. An edit then sends both, nulls
     * included, so removing either one clears it.
     */
    val titleOrPlace: Boolean get() = kind == TripItemKind.Lodging || kind == TripItemKind.Activity

    /**
     * Whether this kind is named by its place search alone: a picked place, or a
     * name taken as typed (`typedName`), never both. A lodging is called what the
     * hotel is, so its form leaves out the title field.
     */
    val namedByPlaceSearch: Boolean get() = kind == TripItemKind.Lodging

    /**
     * Whether the server names this kind itself (a transit, from its route), so its
     * form has no title field and saving needs no name.
     */
    val namedByServer: Boolean get() = kind == TripItemKind.Transit

    /** Whether the form has a title field to type a name in. */
    val hasTitleField: Boolean get() = !namedByPlaceSearch && !namedByServer

    // MARK: - Legs

    /** Adds a leg after the last, picking up where it arrives. */
    fun addLeg(): TripItemDraft =
        copy(legs = legs + (legs.lastOrNull()?.next() ?: TransitLeg(startsAt = startsAt)))

    /** Removes a leg, as long as another is left. */
    fun removeLeg(leg: TransitLeg): TripItemDraft {
        if (legs.size <= 1) return this
        return copy(legs = legs.filterNot { it.id == leg.id })
    }

    /** Puts an edited leg back in place of the one with its id. */
    fun updateLeg(leg: TransitLeg): TripItemDraft =
        copy(legs = legs.map { if (it.id == leg.id) leg else it })

    /**
     * The title to save: the typed name for `namedByPlaceSearch` kinds (nil when
     * they have a place instead), the title field for kinds that have one, else nil
     * (left out, so an edit keeps the server's name).
     */
    private val titleToSave: String?
        get() {
            val name = (if (namedByPlaceSearch) typedName ?: "" else if (hasTitleField) title else "")
                .trimmingWhitespaces()
            return name.ifEmpty { null }
        }

    /**
     * Whether the item is named enough to save: a title, or for `titleOrPlace`
     * kinds, a picked place instead. Kinds the server names need neither.
     */
    val hasName: Boolean
        get() = namedByServer || titleToSave != null || (titleOrPlace && startPlace != null)

    // MARK: - Members

    fun memberSelection(currentUserId: String?): ItemMemberSelection =
        ItemMemberSelection(members = members, currentUserId = currentUserId)

    fun seedSelectedMembers(currentUserId: String?): TripItemDraft {
        if (selectedMemberIds != null) return this
        val seeded = memberSelection(currentUserId).initialSelection(item)
        return withSelectedMemberIds(seeded).copy(originalMemberIds = seeded)
    }

    /**
     * The `participants` to send: exactly who's selected. Nil leaves them to the
     * server: on an edit when the selection is unchanged, and when there's no roster
     * to pick from (a new item is then just for its creator).
     */
    private fun participantsToSave(currentUserId: String?): List<TripItemsAPI.ParticipantRequest>? {
        val selected = selectedMemberIds
        if (members.isEmpty() || selected == null) return null
        if (item != null && selected == originalMemberIds) return null
        return memberSelection(currentUserId).participants(selected)
    }

    /** Only offered when someone else on the trip has an account to be notified. */
    fun canNotifyMembers(currentUserId: String?): Boolean =
        members.any { it.user != null && it.user?.id != currentUserId }

    // MARK: - Saving

    /**
     * Validates and saves the item, returning it once the server has it. Returns nil
     * when validation fails or the request errors, with `errorMessage` set.
     * `missingStartMessage` is shown when there's no start, which the API requires.
     * `update` applies each change (saving, failed…) to the form's current draft.
     */
    suspend fun save(
        currentUserId: String?,
        missingStartMessage: String,
        update: ((TripItemDraft) -> TripItemDraft) -> Unit,
    ): TripItem? {
        if (!hasName) return null
        val fail = { message: String -> update { it.failed(message) } }
        // A transit's legs carry its times; other kinds have their own.
        var segments: List<TripItemsAPI.SegmentRequest>? = null
        if (hasLegs) {
            segments = legRequests(missingStartMessage, fail) ?: return null
        } else {
            val startsAt = startsAt
            if (startsAt == null) {
                fail(missingStartMessage)
                return null
            }
            // Validate ordering on instants, not wall clocks, so cross-zone items are allowed.
            if (endsAt != null &&
                // An end without a zone is taken to be in the start's zone, just for this check.
                instant(endsAt, endZone ?: startZone ?: ZoneOffset.UTC) < instant(startsAt, startZone ?: ZoneOffset.UTC)
            ) {
                fail("End time can't be before the start time.")
                return null
            }
        }
        update { it.copy(errorMessage = null, isSaving = true) }
        try {
            val resolvedNotes = notes.trim().ifEmpty { null }
            // Left out alongside legs, whose span the server takes as the item's.
            val span = segments == null
            val participants = participantsToSave(currentUserId)
            // Sent only when ticked, so saves work on servers that don't take it.
            val notify = if (canNotifyMembers(currentUserId) && notifyMembers) true else null

            val body = TripItemsAPI.CreateRequest(
                kind = kind,
                visibility = visibility,
                title = titleToSave,
                notes = resolvedNotes,
                startsAt = if (span) startsAt?.let(::naiveString) else null,
                startTimezone = if (span) startZone?.id else null,
                endsAt = if (span) endsAt?.let(::naiveString) else null,
                endTimezone = if (span && endsAt != null) endZone?.id else null,
                placeId = if (span) startPlace?.id else null,
                endPlaceId = null,
                segments = segments,
                lodging = lodgingToSave,
                rooms = roomsToSave,
                participants = participants,
                notifyMembers = notify,
            )
            // On an edit, place ids are omitted when nil, so not touching the place search
            // leaves the existing places unchanged; same for participants when the member
            // selection didn't change. A `titleOrPlace` kind sends both, nulls included,
            // so whichever it has replaces the other.
            val endpoint = if (item != null) {
                TripItemsAPI.update(
                    tripId = tripId,
                    itemId = item.id,
                    body = TripItemsAPI.UpdateRequest(
                        body,
                        nullsTitleAndPlace = titleOrPlace,
                        sendsLodging = lodgingChanged,
                        sendsRooms = roomsChanged,
                    ),
                )
            } else {
                TripItemsAPI.create(tripId = tripId, body = body)
            }
            return APIClient.shared.send<TripItem>(endpoint)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error.localizedDescription)
            return null
        } finally {
            update { it.copy(isSaving = false) }
        }
    }

    // MARK: - Lodging details

    /**
     * The stay's details with the form's property type, keeping whatever else the
     * server has (the phone and free-cancellation deadline an imported booking fills
     * in), since a PATCH replaces them whole. Rooms go separately (`roomsToSave`).
     * Nil for other kinds, and when nothing is known.
     */
    val lodgingToSave: LodgingDetails?
        get() {
            if (kind != TripItemKind.Lodging) return null
            val details = (item?.lodging ?: LodgingDetails()).copy(propertyType = propertyType)
            return if (details.isEmpty) null else details
        }

    /**
     * Whether an edit changes the stay's details, so they're sent (a nil one as
     * `null`, clearing them). Untouched, they're left out and stay as stored.
     */
    val lodgingChanged: Boolean
        get() = kind == TripItemKind.Lodging && propertyType != item?.lodging?.propertyType

    /**
     * The stay's rooms as sent: all of them, in order, since the server replaces the
     * list whole. Nil for other kinds, and for a new stay with none.
     */
    val roomsToSave: List<TripItemsAPI.RoomRequest>?
        get() {
            if (kind != TripItemKind.Lodging) return null
            if (item == null && rooms.isEmpty()) return null
            return rooms.map(TripItemsAPI::RoomRequest)
        }

    /**
     * Whether an edit changes the rooms or who's in them, so they're sent (an empty
     * list clearing them). Untouched, they're left out and stay as stored.
     */
    val roomsChanged: Boolean
        get() = kind == TripItemKind.Lodging &&
            rooms.map(TripItemsAPI::RoomRequest) != (item?.rooms ?: emptyList()).map(TripItemsAPI::RoomRequest)

    /**
     * Puts an edited room back: in place of the one with its id, else added at the
     * end. A new room left blank isn't added. Anyone put in it who wasn't on the stay
     * is added to it, as the server would.
     */
    fun updateRoom(room: LodgingRoom): TripItemDraft {
        val tidied = room.tidied
        val draft = when {
            rooms.any { it.id == tidied.id } -> copy(rooms = rooms.map { if (it.id == tidied.id) tidied else it })
            !tidied.isBlank -> copy(rooms = rooms + tidied.copy(position = rooms.size))
            else -> return this
        }
        if (tidied.occupants.isEmpty()) return draft
        return draft.withSelectedMemberIds((draft.selectedMemberIds ?: emptySet()) + tidied.occupants)
    }

    /**
     * Who's in `room`, for its row: "You and Sam", or for more than three the first
     * two and a count ("You, Sam and 2 others"). Nil when nobody is. Members go by
     * their name, else the start of their email.
     */
    fun occupantNames(room: LodgingRoom, currentUserId: String?): String? {
        val names = members.filter { it.id in room.occupants }.map { member ->
            val user = member.user
            if (user != null && user.id == currentUserId) "You" else user?.displayName ?: member.email.substringBefore('@')
        }
        return when (names.size) {
            0 -> null
            1 -> names[0]
            2 -> "${names[0]} and ${names[1]}"
            3 -> "${names[0]}, ${names[1]}, and ${names[2]}"
            else -> "${names[0]}, ${names[1]} and ${names.size - 2} others"
        }
    }

    /** Takes a room off the stay; whoever was in it stays on the stay, in no room. */
    fun removeRoom(id: String): TripItemDraft =
        copy(rooms = rooms.filterNot { it.id == id }.mapIndexed { index, room -> room.copy(position = index) })

    /**
     * The trip's members as the room popup lists them for the room with `roomId`: the
     * stay's own people first, then everyone else on the trip, then whoever is
     * already in another of its rooms. Each keeps the trip's member order.
     */
    fun roomAssignments(roomId: String): RoomAssignments {
        val otherRooms = mutableMapOf<String, String>()
        rooms.forEachIndexed { index, room ->
            if (room.id != roomId) {
                for (memberId in room.occupants) {
                    otherRooms[memberId] = room.title(number = index + 1)
                }
            }
        }
        val onStay = selectedMemberIds ?: emptySet()
        val free = members.filter { it.id !in otherRooms }
        return RoomAssignments(
            participants = free.filter { it.id in onStay },
            others = free.filter { it.id !in onStay },
            inOtherRooms = members.mapNotNull { member ->
                otherRooms[member.id]?.let { RoomAssignments.Placed(member = member, roomTitle = it) }
            },
        )
    }

    // MARK: - Real bookings

    /**
     * Whether this is a stay with a real booking behind it: any lodging but one
     * marked Other. Moving or deleting it here doesn't change the booking, so the
     * form asks first.
     */
    private val isBookedStay: Boolean
        get() = kind == TripItemKind.Lodging && propertyType != LodgingType.Other

    /**
     * Whether saving moves a booked stay to other days: its check-in or check-out
     * date changed (adding or clearing a check-out counts; a new time on the same
     * day doesn't).
     */
    val changesBookedDates: Boolean
        get() {
            if (!isBookedStay || item == null) return false
            fun day(canvas: Instant?): String? = canvas?.let(::dayKey)
            return day(startsAt) != day(wallClock(item.startsAtLocal, ZoneOffset.UTC)) ||
                day(endsAt) != day(wallClock(item.endsAtLocal, ZoneOffset.UTC))
        }

    /**
     * Whether saving changes how many rooms a booked stay has. Who's in which room
     * doesn't count: that's ours to arrange, not the property's.
     */
    val changesBookedRoomCount: Boolean
        get() = isBookedStay && item != null && rooms.size != item.rooms.size

    /** Whether deleting removes a booked stay, so the form asks first. */
    val deletesBookedStay: Boolean
        get() = isBookedStay && item != null

    /**
     * Every leg as sent, or nil (after a `fail` with the message) when one has no
     * departure or arrives before it departs. With one leg, the messages don't number it.
     */
    private fun legRequests(missingStartMessage: String, fail: (String) -> Unit): List<TripItemsAPI.SegmentRequest>? {
        val requests = mutableListOf<TripItemsAPI.SegmentRequest>()
        legs.forEachIndexed { index, leg ->
            val segment = if (legs.size > 1) "segment ${index + 1}" else null
            val request = leg.request
            if (request == null) {
                fail(segment?.let { "Add a departure time for $it." } ?: missingStartMessage)
                return null
            }
            if (leg.endsBeforeStart) {
                fail(segment?.let { "The arrival for $it can't be before its departure." }
                    ?: "End time can't be before the start time.")
                return null
            }
            requests.add(request)
        }
        return requests
    }

    /**
     * Deletes the item being edited, returning whether it worked (with
     * `errorMessage` set when it didn't). `update` applies each change to the form's
     * current draft.
     */
    suspend fun delete(update: ((TripItemDraft) -> TripItemDraft) -> Unit): Boolean {
        if (item == null) return false
        update { it.copy(errorMessage = null, isDeleting = true) }
        try {
            APIClient.shared.sendIgnoringResponse(TripItemsAPI.delete(tripId = tripId, itemId = item.id))
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            update { it.failed(error.localizedDescription) }
            return false
        } finally {
            update { it.copy(isDeleting = false) }
        }
    }

    private fun failed(message: String): TripItemDraft =
        copy(errorMessage = message, errorScrollTrigger = errorScrollTrigger + 1)

    /**
     * Fills the location fields with the place names from Google when editing. The
     * backend enriches its own place name asynchronously, so `item.place?.name` is
     * often still null here; Google's copy is reliably available. Only fills a field
     * left empty by the backend, and leaves it untouched on failure. Safe against
     * re-triggering a search: the fields aren't focused when this runs. `update`
     * applies each name to the form's current draft, where the field is still empty
     * (a name can take a moment to come, and the user may have typed one meanwhile).
     */
    suspend fun prefillPlaceNames(update: ((TripItemDraft) -> TripItemDraft) -> Unit) {
        if (!hasLegs && startPlaceText.isEmpty()) {
            googleName(item?.place)?.let { name ->
                update { if (it.startPlaceText.isEmpty()) it.copy(startPlaceText = name) else it }
            }
        }
        for (leg in legs) {
            fun fill(change: (TransitLeg) -> TransitLeg) = update { draft ->
                draft.copy(legs = draft.legs.map { if (it.id == leg.id) change(it) else it })
            }
            if (leg.startPlaceText.isEmpty()) {
                googleName(leg.startPlace)?.let { name ->
                    fill { if (it.startPlaceText.isEmpty()) it.copy(startPlaceText = name) else it }
                }
            }
            if (leg.endPlaceText.isEmpty()) {
                googleName(leg.endPlace)?.let { name ->
                    fill { if (it.endPlaceText.isEmpty()) it.copy(endPlaceText = name) else it }
                }
            }
        }
    }

    /**
     * The hour an empty end opens on in the picker: check-out time for a stay. Nil
     * opens on an hour after the start.
     */
    val defaultEndHour: Int?
        get() = if (kind == TripItemKind.Lodging) 11 else null

    companion object {
        /**
         * A draft for a new item of `kind` on the trip with `tripId`, or for editing
         * `item` (whose kind it then takes).
         */
        operator fun invoke(
            tripId: String,
            kind: TripItemKind,
            members: List<TripMember> = emptyList(),
            tripsByDate: Map<String, Trip> = emptyMap(),
            tripDates: ClosedRange<Instant>? = null,
            item: TripItem? = null,
            defaultDate: Instant? = null,
            viewedDay: Instant? = null,
        ): TripItemDraft {
            val itemKind = item?.kind ?: kind
            val goesByTitleOrPlace = itemKind == TripItemKind.Lodging || itemKind == TripItemKind.Activity
            // A lodging with no place goes by its title, shown as the typed name.
            val typedName = item?.title?.takeIf { itemKind == TripItemKind.Lodging && item.place == null && it.isNotEmpty() }
            // Load the item's wall clock onto the device-zone canvas (so the rows show
            // the same numbers), or for a new item start on the day being viewed at the
            // current time-of-day (falling back to "now" when no day was supplied).
            // `startsAtLocal` is a floating UTC date, so reading it in UTC gives the wall clock.
            val startsAt = wallClock(item?.startsAtLocal, ZoneOffset.UTC)
                ?: canvasStart(defaultDate, defaultStartHour(itemKind))
                ?: roundedToMinuteInterval(Instant.now())
            val endsAt = wallClock(item?.endsAtLocal, ZoneOffset.UTC)
            val startZone = item?.startTimezone
            val endZone = item?.endTimezone

            val segments = item?.segments.orEmpty()
            val legs = when {
                itemKind != TripItemKind.Transit -> emptyList()
                segments.isNotEmpty() -> segments.map(::TransitLeg)
                // A new transit (or one saved without legs) starts as one leg from the
                // item's own ends. A new one starts as a flight, the most common case.
                else -> listOf(
                    TransitLeg(
                        transitMode = if (item == null) TransitMode.Flight else item.transitMode.takeUnless { it == TransitMode.Mixed },
                        startPlace = item?.place,
                        startPlaceText = item?.place?.name ?: "",
                        endPlace = item?.endPlace,
                        endPlaceText = item?.endPlace?.name ?: "",
                        startsAt = startsAt,
                        endsAt = endsAt,
                        startZone = startZone,
                        endZone = endZone,
                    ),
                )
            }

            return TripItemDraft(
                kind = itemKind,
                item = item,
                fallbackTripId = tripId,
                fallbackMembers = members,
                tripsByDate = tripsByDate,
                tripDates = tripDates,
                viewedDay = viewedDay ?: defaultDate,
                visibility = item?.visibility ?: TripItemVisibility.Open,
                title = item?.title ?: "",
                startsAt = startsAt,
                endsAt = endsAt,
                startZone = startZone,
                endZone = endZone,
                notes = item?.notes ?: "",
                // The item's place counts as picked, so an untouched edit still has one
                // (an edit sends it, and a missing one clears it). Other kinds leave it
                // nil so an untouched edit keeps the current places.
                startPlace = if (goesByTitleOrPlace) item?.place else null,
                // Prefill the location field with the existing place name (if the backend
                // has enriched it). A name that's still null gets filled from Google on appear.
                startPlaceText = typedName ?: item?.place?.name ?: "",
                legs = legs,
                propertyType = item?.lodging?.propertyType,
                rooms = item?.rooms ?: emptyList(),
                typedName = typedName,
            )
        }

        /** The day a wall clock on the device-zone canvas falls on, as itinerary days name it. */
        private fun dayKey(canvasDate: Instant): String =
            DateTimeFormatter.ISO_LOCAL_DATE.format(canvasDate.atZone(ZoneId.systemDefault()))

        private suspend fun googleName(place: Place?): String? {
            val gid = place?.googlePlaceId ?: return null
            return try {
                GooglePlacesClient.shared.placeDetails(placeId = gid).name
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }

        // MARK: - Wall-clock helpers
        //
        // The pickers hold wall clocks on a neutral canvas: the device zone. Reading or
        // writing the wall-clock components in that same zone leaves the printed numbers
        // untouched regardless of which zone the item is actually in.

        /** Produces naive ISO 8601 (no offset) from a canvas date's wall clock. */
        private val naiveFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ROOT)

        /** A canvas date's wall clock, to the second. */
        private fun canvasWallClock(canvasDate: Instant): LocalDateTime =
            LocalDateTime.ofInstant(canvasDate, ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS)

        /** Naive wall clock (no offset) for a canvas date, e.g. "2026-04-08T16:00:00". */
        fun naiveString(canvasDate: Instant): String =
            naiveFormatter.format(canvasWallClock(canvasDate))

        /**
         * A new item's default canvas start: `day`'s calendar date at `hour` o'clock.
         * Returns nil when no day is given, so callers can fall back to now.
         */
        private fun canvasStart(day: Instant?, hour: Int): Instant? {
            if (day == null) return null
            val canvas = ZoneId.systemDefault()
            return day.atZone(canvas).toLocalDate().atTime(LocalTime.of(hour, 0)).atZone(canvas).toInstant()
        }

        /** The hour a new item starts at on its day: check-in time for a stay, else noon. */
        private fun defaultStartHour(kind: TripItemKind): Int =
            if (kind == TripItemKind.Lodging) 15 else 12

        /** Minute step of the start/end time pickers. */
        private const val minuteInterval = 15

        /**
         * `date` rounded to the nearest `minuteInterval` mark, so a default value matches
         * what the picker displays.
         */
        private fun roundedToMinuteInterval(date: Instant): Instant {
            val step = minuteInterval * 60
            return Instant.ofEpochSecond((date.toEpochMilli() / 1000.0 / step).roundToLong() * step)
        }

        /**
         * Places an item's wall clock (its instant read in `zone`) onto the device-zone
         * canvas, so a picker bound to the result shows the same numbers.
         */
        fun wallClock(instant: Instant?, zone: ZoneId?): Instant? {
            if (instant == null || zone == null) return null
            return LocalDateTime.ofInstant(instant, zone).truncatedTo(ChronoUnit.SECONDS)
                .atZone(ZoneId.systemDefault()).toInstant()
        }

        /** The true instant for a canvas wall clock reinterpreted in `zone`. */
        fun instant(canvasDate: Instant, zone: ZoneId): Instant =
            canvasWallClock(canvasDate).atZone(zone).toInstant()
    }
}

/** Who can go in a room, grouped the way the room popup lists them. */
data class RoomAssignments(
    /** On the stay and in no other room. */
    val participants: List<TripMember>,
    /** On the trip but not the stay; putting one in the room adds them to it. */
    val others: List<TripMember>,
    /** Already in another of the stay's rooms, with that room's name. */
    val inOtherRooms: List<Placed>,
) {
    data class Placed(
        val member: TripMember,
        val roomTitle: String,
    ) {
        val id: String get() = member.id
    }
}
