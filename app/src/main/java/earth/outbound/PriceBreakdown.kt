//
//  PriceBreakdown.kt
//  outbound
//

package earth.outbound

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * How a quoted trip price adds up, collapsed until asked for: each trip-day bracket,
 * then the extra people's member-day brackets, the total, and for an edit, what's
 * already been paid and what's due now. Shown in `ChargeConfirmationPopup` and in
 * `PurchasePopup` when a save is short of credits.
 */
@Composable
fun PriceBreakdown(quote: TripQuote, modifier: Modifier = Modifier) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(modifier) {
        DisclosureHeader(title = "How we got this", isExpanded = isExpanded, onToggle = { isExpanded = !isExpanded })

        AnimatedVisibility(
            visible = isExpanded,
            enter = PopupCardMetrics.contentEnter,
            exit = PopupCardMetrics.contentExit,
        ) {
            Lines(quote, Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun Lines(quote: TripQuote, modifier: Modifier = Modifier) {
    /** The trip's whole price as it would stand: what the lines add up to. */
    val tripPriceCents = (quote.tripLines + quote.memberLines).sumOf { it.cents }

    /** What's been paid for the trip already, e.g. before an edit that adds days. */
    val paidCents = quote.paidCents ?: 0

    ForegroundStyle(Color.appForeground.copy(alpha = 0.8f)) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${quote.days} ${if (quote.days == 1) "day" else "days"} for ${quote.members} ${if (quote.members == 1) "person" else "people"}",
                style = AppFont.footnote.semibold,
            )
            for (line in quote.tripLines) {
                LineRow(line, label = PriceBreakdownMetrics.daysText(line))
            }
            if (quote.extraMembers > 0) {
                Text(
                    PriceBreakdownMetrics.memberDaysText(extraMembers = quote.extraMembers, days = quote.days),
                    style = AppFont.footnote.semibold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // Each line already covers everyone extra: the brackets run over all
                // their member-days together.
                for (line in quote.memberLines) {
                    LineRow(line, label = PriceBreakdownMetrics.memberDaysRangeText(line))
                }
            }

            Hairline(Modifier.padding(vertical = 2.dp))
            TotalRow("Trip price", cents = tripPriceCents)
            if (paidCents > 0) {
                TotalRow("Already paid", cents = -paidCents)
                TotalRow("Due now", cents = quote.priceCents, style = totalStyle.semibold)
            }
        }
    }
}

/**
 * The wording of a price's lines. It lives here rather than on `PriceBreakdown`,
 * which is a composable function and so can't hold it itself.
 */
object PriceBreakdownMetrics {
    /** A bracket's days, e.g. "Day 1" or "Days 4–9". */
    fun daysText(line: TripPricing.Line): String =
        if (line.firstDay == line.lastDay) "Day ${line.firstDay}" else "Days ${line.firstDay}–${line.lastDay}"

    /** How the extra member-days add up, e.g. "3 extra people × 5 days = 15 member-days". */
    fun memberDaysText(extraMembers: Int, days: Int): String {
        val people = if (extraMembers == 1) "1 extra person" else "$extraMembers extra people"
        val total = extraMembers * days
        return "$people × $days ${if (days == 1) "day" else "days"} = $total ${if (total == 1) "member-day" else "member-days"}"
    }

    /** A member bracket's member-days, e.g. "Member-day 1" or "Member-days 1–10". */
    fun memberDaysRangeText(line: TripPricing.Line): String =
        if (line.firstDay == line.lastDay) "Member-day ${line.firstDay}" else "Member-days ${line.firstDay}–${line.lastDay}"
}

/** The font of the breakdown's rows, with digits lining up down the amounts. */
private val totalStyle: TextStyle get() = AppFont.footnote.monospacedDigit

/** One bracket of the price, e.g. "Days 1–3   3 × $1.60   $4.80". */
@Composable
private fun LineRow(line: TripPricing.Line, label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = totalStyle, modifier = Modifier.weight(1f))
        Text(
            "${line.days} × ${formatCredits(cents = line.centsPerDay)}",
            style = totalStyle,
            color = Color.appForeground.copy(alpha = 0.6f),
        )
        Text(
            formatCredits(cents = line.cents),
            style = totalStyle,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 56.dp),
        )
    }
}

@Composable
private fun TotalRow(title: String, cents: Int, style: TextStyle = totalStyle) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = style, modifier = Modifier.weight(1f))
        Text(
            formatCredits(cents = cents, signed = cents < 0),
            style = style,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 56.dp),
        )
    }
}
