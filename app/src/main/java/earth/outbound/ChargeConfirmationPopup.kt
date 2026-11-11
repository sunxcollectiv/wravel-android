//
//  ChargeConfirmationPopup.kt
//  outbound
//

package earth.outbound

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Asks before a trip save that costs credits the user has: how much it takes, what
 * they have and will have left, and how the price was worked out. Shown from the trip
 * form over a dimmed screen (see `DimmedPopup`).
 */
@Composable
fun ChargeConfirmationPopup(
    tripTitle: String,
    quote: TripQuote,
    /**
     * The trip owner's name when someone else is paying (an organizer editing the
     * trip): refunds only ever go to the owner, so the payer is told up front.
     */
    refundsGoTo: String? = null,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PopupCard(onClose = onClose, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PopupHeaderLabel("Confirm Credits", Icons.Rounded.CreditCard)

            Text(
                "Saving $tripTitle uses ${formatCredits(cents = quote.priceCents)} of your credits.",
                style = AppFont.headline,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.appForeground.copy(alpha = 0.06f), RoundedCornerShape(appFieldCornerRadius))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AmountRow("Your credits", cents = quote.balanceCents)
                AmountRow("This trip", cents = -quote.priceCents, signed = true)
                Hairline()
                AmountRow("Left after", cents = quote.balanceAfterCents, style = AppFont.subheadline.semibold)
            }

            if (refundsGoTo != null) {
                OwnerRefundNote(owner = refundsGoTo)
            }

            PriceBreakdown(quote = quote)

            CreditPopupButton(
                title = "Use ${formatCredits(cents = quote.priceCents)}",
                onClick = onConfirm,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun AmountRow(title: String, cents: Int, signed: Boolean = false, style: TextStyle = AppFont.subheadline) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = style, modifier = Modifier.weight(1f))
        Text(formatCredits(cents = cents, signed = signed), style = style.monospacedDigit)
    }
}

/**
 * Warns a payer who isn't the trip's owner that credits spent on it don't come back
 * to them: refunds for a shortened or cancelled trip always go to the owner.
 */
@Composable
fun OwnerRefundNote(
    /** The owner's name. */
    owner: String,
    modifier: Modifier = Modifier,
) {
    ForegroundStyle(Color.appForeground.copy(alpha = 0.8f)) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.padding(top = 1.dp).size(scaledSymbolSize(AppFont.footnote, scale = 1.25f)),
            )
            Text(
                "You're not this trip's owner. If the trip is later shortened or cancelled, any refunded credits go to $owner, not back to you.",
                style = AppFont.footnote,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Preview
@Composable
private fun ChargeConfirmationPopupPreview() {
    AppTheme {
        Box(Modifier.fillMaxSize().background(Brush.tripsBackground), contentAlignment = Alignment.Center) {
            ChargeConfirmationPopup(
                tripTitle = "Before",
                // 3 extra people × 5 days = 15 member-days, on the "2026-11" rates.
                quote = TripQuote(
                    days = 5, members = 8, extraMembers = 3, priceCents = 915, refundCents = 0,
                    balanceCents = 2_000, shortfallCents = 0,
                    tripLines = listOf(
                        TripPricing.Line(firstDay = 1, lastDay = 3, centsPerDay = 160),
                        TripPricing.Line(firstDay = 4, lastDay = 5, centsPerDay = 80),
                    ),
                    memberLines = listOf(
                        TripPricing.Line(firstDay = 1, lastDay = 10, centsPerDay = 20),
                        TripPricing.Line(firstDay = 11, lastDay = 15, centsPerDay = 15),
                    ),
                ),
                onConfirm = {},
                onClose = {},
            )
        }
    }
}
