//
//  BookingChangePopup.kt
//  outbound
//

package earth.outbound

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** What a `BookingChangePopup` asks about. */
sealed interface BookingChangePopupChange {
    /** The check-in or check-out date moved, the number of rooms changed, or both. */
    data class Edit(val dates: Boolean, val rooms: Boolean) : BookingChangePopupChange

    data object Delete : BookingChangePopupChange

    val question: String
        get() = when (this) {
            is Edit -> when {
                dates && !rooms -> "Change this stay's dates?"
                !dates && rooms -> "Change the number of rooms?"
                else -> "Change this stay's dates and rooms?"
            }
            Delete -> "Delete this stay?"
        }

    val explanation: String
        get() = when (this) {
            is Edit -> {
                val what = when {
                    dates && rooms -> "new dates and rooms have"
                    dates -> "new dates have"
                    else -> "new number of rooms has"
                }
                "This only updates your itinerary. It won't change your actual booking, so make sure the $what already been confirmed with the property."
            }
            Delete ->
                "This only removes it from your itinerary. It won't cancel your actual booking, so make sure it's already been cancelled with the property."
        }

    val confirmTitle: String
        get() = when (this) {
            is Edit -> "Changed with Property"
            Delete -> "Cancelled with Property"
        }

    companion object {
        /** What an edit to a stay asks about, or nil when it changes neither. */
        fun edit(changingDates: Boolean, rooms: Boolean): BookingChangePopupChange? =
            if (changingDates || rooms) Edit(dates = changingDates, rooms = rooms) else null
    }
}

/**
 * Asks before changing a booked stay's dates or number of rooms, or deleting it:
 * the itinerary changes, but the real booking with the property doesn't, so the
 * user confirms they've already made the change there. Not asked for stays marked
 * Other, which have no booking behind them.
 */
@Composable
fun BookingChangePopup(
    change: BookingChangePopupChange,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PopupCard(onClose = onClose, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PopupHeaderLabel("Your Booking", Icons.Rounded.Warning)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(change.question, style = AppFont.headline)
                Text(change.explanation, style = AppFont.subheadline, color = Color.appForeground.copy(alpha = 0.8f))
            }

            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .requiresConnection()
                    .fillMaxWidth()
                    .plainClickable(onClick = onConfirm)
                    .background(
                        if (change == BookingChangePopupChange.Delete) Color.appError else Color.appBackground,
                        RoundedCornerShape(appFieldCornerRadius),
                    )
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(change.confirmTitle, style = AppFont.body.semibold)
            }
        }
    }
}

@Preview(name = "Dates")
@Composable
private fun BookingChangePopupDatesPreview() {
    AppTheme {
        DimmedPopup(isPresented = true, onClose = {}) {
            BookingChangePopup(change = BookingChangePopupChange.Edit(dates = true, rooms = false), onConfirm = {}, onClose = {})
        }
    }
}

@Preview(name = "Delete")
@Composable
private fun BookingChangePopupDeletePreview() {
    AppTheme {
        DimmedPopup(isPresented = true, onClose = {}) {
            BookingChangePopup(change = BookingChangePopupChange.Delete, onConfirm = {}, onClose = {})
        }
    }
}
