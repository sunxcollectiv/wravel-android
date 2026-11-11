//
//  ActivityFormView.kt
//  outbound
//

package earth.outbound

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The form for adding or editing an activity: one place, a start, and an optional end.
 * `updateDraft` applies a change to the draft, as for `TripItemFormScaffold`.
 */
@Composable
fun ActivityFormView(
    draft: TripItemDraft,
    updateDraft: ((TripItemDraft) -> TripItemDraft) -> Unit,
    onSaved: (TripItem) -> Unit = {},
    onDeleted: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TripItemFormScaffold(
        draft = draft,
        updateDraft = updateDraft,
        timeLabels = TripItemTimeLabels(
            start = "Starts", end = "Ends", missingStart = "Add a start time.",
            endIsOptional = true, offersEndDurations = true,
        ),
        onSaved = onSaved,
        onDeleted = onDeleted,
        onDismiss = onDismiss,
        modifier = modifier,
        places = {
            TripItemPlaceField(
                placeholder = "Search a place",
                text = draft.startPlaceText,
                onTextChange = { text -> updateDraft { it.copy(startPlaceText = text) } },
                place = draft.startPlace,
                onPlaceChange = { place -> updateDraft { it.copy(startPlace = place) } },
                onTimeZoneChange = { zone -> updateDraft { it.withStartPlaceZone(zone) } },
            )
        },
    )
}
