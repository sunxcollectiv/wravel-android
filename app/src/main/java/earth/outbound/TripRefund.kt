//
//  TripRefund.kt
//  outbound
//

package earth.outbound

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import earth.outbound.networking.APIClient
import earth.outbound.networking.orJsonNull
import kotlinx.coroutines.CancellationException

/**
 * Credits a change to a trip gives back: what's refunded, or that nothing is because
 * the refund window has closed (a day after the trip starts).
 */
data class TripRefund(
    val cents: Int,
    /** Credits were paid for the trip, but it's too late to get them back. */
    val windowClosed: Boolean = false,
) {
    /** Whether there's anything to tell the user. */
    val isWorthShowing: Boolean get() = cents > 0 || windowClosed
}

/**
 * What deleting the trip as it stands gives back: everything paid for it, while
 * the refund window is open.
 */
val TripQuote.deleteRefund: TripRefund
    get() {
        val paid = paidCents ?: 0
        return if (refundWindowOpen == false) {
            TripRefund(cents = 0, windowClosed = paid > 0)
        } else {
            TripRefund(cents = paid)
        }
    }

/** What saving this edit gives back. */
val TripQuote.editRefund: TripRefund get() = TripRefund(cents = refundCents)

/**
 * What deleting the trip would refund, from a quote for the trip as it stands
 * (its own dates, not its group's span). Nothing is saved.
 */
suspend fun TripsAPI.deleteRefund(tripId: String, api: APIClient = APIClient.shared): TripRefund {
    val trip = api.send<Trip>(read(id = tripId))
    val body = TripsAPI.CreateRequest(
        title = trip.title,
        description = trip.description.orJsonNull(),
        startDate = trip.startDate,
        endDate = trip.endDate,
    )
    return api.send<TripQuote>(quote(id = tripId, body = body)).deleteRefund
}

/** Why a `TripRefundSection` is showing: the trip is being deleted, or edited. */
enum class TripRefundSectionReason {
    Delete, Edit
}

/**
 * The part of a popup that says what credits come back from deleting or shortening a
 * trip, and where they go. Shown in `DeleteTripPopup` and `RefundConfirmationPopup`.
 */
@Composable
fun TripRefundSection(
    refund: TripRefund,
    reason: TripRefundSectionReason,
    /**
     * The trip owner's name when they aren't the one making the change: refunds always
     * go to the owner.
     */
    refundsGoTo: String? = null,
    modifier: Modifier = Modifier,
) {
    val title = if (refund.cents > 0) "${formatCredits(cents = refund.cents)} credit refund" else "No credit refund"

    /** Whose balance the refund goes to, e.g. "your" or "Sam's". */
    val whose = refundsGoTo?.let { "$it's" } ?: "your"

    val explanation = if (refund.cents <= 0) {
        "Refunds end a day after a trip starts, so the credits paid for this trip aren't given back."
    } else {
        when (reason) {
            TripRefundSectionReason.Delete ->
                "Everything paid for this trip goes back to $whose credit balance when it's deleted."
            TripRefundSectionReason.Edit ->
                "The credits for the days and people this trip no longer needs go back to $whose credit balance."
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.appForeground.copy(alpha = 0.06f), RoundedCornerShape(appFieldCornerRadius))
            .padding(14.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val iconSize = scaledSymbolSize(AppFont.headline, scale = 1.25f)
            if (refund.cents > 0) {
                RefundIcon(iconSize)
            } else {
                Icon(Icons.Rounded.TimerOff, contentDescription = null, modifier = Modifier.size(iconSize))
            }
            Text(title, style = AppFont.headline)
        }
        Text(explanation, style = AppFont.footnote, color = Color.appForeground.copy(alpha = 0.7f))
    }
}

/** A turn-back arrow cut out of a filled disc, for credits coming back. */
@Composable
private fun RefundIcon(size: Dp) {
    Box(Modifier.size(size).background(LocalContentColor.current, CircleShape), contentAlignment = Alignment.Center) {
        Icon(
            Icons.AutoMirrored.Rounded.Undo,
            contentDescription = null,
            // The color of the card showing through.
            tint = Color.white(0.2f),
            modifier = Modifier.size(size * 0.7f),
        )
    }
}

/**
 * Asks before deleting a trip, saying what's lost and what credits come back. The
 * refund is looked up as the popup opens; deleting doesn't wait for it.
 */
@Composable
fun DeleteTripPopup(
    tripId: String,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    api: APIClient = APIClient.shared,
    modifier: Modifier = Modifier,
) {
    var refund by remember { mutableStateOf<TripRefund?>(null) }
    var isLoadingRefund by remember { mutableStateOf(true) }

    PopupCard(onClose = onClose, modifier = modifier) {
        // Each part brings the gap above it, so one that isn't showing leaves none.
        Column {
            PopupHeaderLabel("Delete Trip", Icons.Rounded.Delete)

            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Delete this trip?", style = AppFont.headline)
                Text(
                    "Everything on it is deleted for everyone on the trip. This can't be undone.",
                    style = AppFont.subheadline,
                    color = Color.appForeground.copy(alpha = 0.8f),
                )
            }

            AnimatedVisibility(
                visible = isLoadingRefund,
                enter = PopupCardMetrics.contentEnter,
                exit = PopupCardMetrics.contentExit,
            ) {
                Row(
                    modifier = Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProgressView(tint = Color.appForeground)
                    Text(
                        "Checking for a credit refund…",
                        style = AppFont.footnote,
                        color = Color.appForeground.copy(alpha = 0.7f),
                    )
                }
            }
            AnimatedVisibility(
                visible = !isLoadingRefund && refund?.isWorthShowing == true,
                enter = PopupCardMetrics.contentEnter,
                exit = PopupCardMetrics.contentExit,
            ) {
                refund?.let {
                    TripRefundSection(
                        refund = it,
                        reason = TripRefundSectionReason.Delete,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }

            CreditPopupButton(
                title = "Delete Trip",
                onClick = onDelete,
                background = Color.appError,
                modifier = Modifier.padding(top = 20.dp).requiresConnection(),
            )
        }
    }

    LaunchedEffect(Unit) {
        // No refund line if it can't be looked up; deleting still works.
        refund = try {
            TripsAPI.deleteRefund(tripId = tripId, api = api)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        isLoadingRefund = false
    }
}

/** Asks before saving an edit that gives credits back, e.g. shorter dates. */
@Composable
fun RefundConfirmationPopup(
    tripTitle: String,
    quote: TripQuote,
    /** The trip owner's name when someone else is editing (see `TripRefundSection`). */
    refundsGoTo: String? = null,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PopupCard(onClose = onClose, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PopupHeaderLabel("Confirm Changes", Icons.Rounded.CalendarMonth)

            Text("Save the changes to $tripTitle?", style = AppFont.headline)

            TripRefundSection(refund = quote.editRefund, reason = TripRefundSectionReason.Edit, refundsGoTo = refundsGoTo)

            CreditPopupButton(title = "Save Changes", onClick = onConfirm, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Preview(name = "Refund on edit")
@Composable
private fun RefundConfirmationPopupPreview() {
    AppTheme {
        Box(Modifier.fillMaxSize().background(Brush.tripsBackground), contentAlignment = Alignment.Center) {
            RefundConfirmationPopup(
                tripTitle = "Lisbon",
                quote = TripQuote(
                    days = 4, members = 2, extraMembers = 0, priceCents = 0, refundCents = 480,
                    balanceCents = 2_000, shortfallCents = 0, tripLines = emptyList(), memberLines = emptyList(),
                ),
                onConfirm = {},
                onClose = {},
            )
        }
    }
}
