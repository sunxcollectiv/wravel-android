//
//  TripItemFormView.kt
//  outbound
//

package earth.outbound

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.Instant

/**
 * Modal for manually adding or editing a trip item: opens the form for the item's
 * kind (`ActivityFormView`, `LodgingFormView` or `TransitFormView`), which share
 * their state through a `TripItemDraft` and their layout through `TripItemFormScaffold`.
 *
 * Pass an existing `item` to edit it (its own kind wins); omit it to create a new
 * item of `kind`. A kind is fixed once the form is open. Show it in an `AppSheet`,
 * which it fills; `onDismiss` closes that sheet.
 *
 * @param defaultDate The calendar day a new item's start defaults to (the day being
 *   viewed when add was tapped); the time-of-day defaults to noon. `null` falls back to now.
 * @param viewedDay The itinerary day the form was opened from, marked in the date
 *   popup's calendar. For a new item it defaults to `defaultDate`.
 * @param tripsByDate For a new item, the trip owning each itinerary day, so it saves
 *   to the one its date falls in rather than `tripId`.
 * @param onSaved Called with the created or updated item after a successful save.
 * @param onDeleted Called after the item being edited is deleted.
 */
@Composable
fun TripItemFormView(
    tripId: String,
    kind: TripItemKind = TripItemKind.Activity,
    members: List<TripMember> = emptyList(),
    tripsByDate: Map<String, Trip> = emptyMap(),
    tripDates: ClosedRange<Instant>? = null,
    item: TripItem? = null,
    defaultDate: Instant? = null,
    viewedDay: Instant? = null,
    onSaved: (TripItem) -> Unit = {},
    onDeleted: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One draft for as long as the form is open on the same item (or new item of a kind).
    var draft by remember(tripId, kind, item?.id) {
        mutableStateOf(
            TripItemDraft(
                tripId = tripId,
                kind = kind,
                members = members,
                tripsByDate = tripsByDate,
                tripDates = tripDates,
                item = item,
                defaultDate = defaultDate,
                viewedDay = viewedDay,
            )
        )
    }
    // Applied to the draft as it is now, so nothing changed meanwhile is lost.
    val updateDraft: ((TripItemDraft) -> TripItemDraft) -> Unit = remember(tripId, kind, item?.id) {
        { change -> draft = change(draft) }
    }

    // A form of its own for each draft, so nothing of the last one (an open popup, a
    // search under way) carries over when the sheet is handed another item.
    key(tripId, kind, item?.id) {
        when (draft.kind) {
            TripItemKind.Activity ->
                ActivityFormView(draft = draft, updateDraft = updateDraft, onSaved = onSaved, onDeleted = onDeleted, onDismiss = onDismiss, modifier = modifier)
            TripItemKind.Lodging ->
                LodgingFormView(draft = draft, updateDraft = updateDraft, onSaved = onSaved, onDeleted = onDeleted, onDismiss = onDismiss, modifier = modifier)
            TripItemKind.Transit ->
                TransitFormView(draft = draft, updateDraft = updateDraft, onSaved = onSaved, onDeleted = onDeleted, onDismiss = onDismiss, modifier = modifier)
        }
    }
}
