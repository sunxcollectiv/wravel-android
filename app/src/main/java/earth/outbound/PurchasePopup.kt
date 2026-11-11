//
//  PurchasePopup.kt
//  outbound
//

package earth.outbound

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import earth.outbound.auth.LocalAuthStore
import kotlinx.coroutines.launch

/**
 * A centered card for buying credits: a choice of set amounts, a button to confirm
 * the one picked, and how trips are priced. Shown over the credits page, or from the
 * trip form when a save costs more than the user has (`shortfall`), with the amount
 * that covers it picked.
 */
@Composable
fun PurchasePopup(
    onClose: () -> Unit,
    /**
     * How many people the "usable for" line prices a trip for; null for the schedule's
     * included members.
     */
    members: Int? = null,
    /** The save that needs more credits, when opened from one. */
    shortfall: TripQuote? = null,
    /** The trip owner's name when the buyer isn't the owner (see `OwnerRefundNote`). */
    refundsGoTo: String? = null,
    /** Called once bought credits are on the balance. */
    onPurchased: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val credits = LocalCreditStore.current
    val auth = LocalAuthStore.current
    val pricingStore = LocalPricingStore.current
    val activity = LocalContext.current.findActivity()

    var selectedCents by remember {
        mutableIntStateOf(shortfall?.let { PurchasePopupMetrics.recommendedCents(it.shortfallCents) } ?: 1_500)
    }
    var showingPricing by remember { mutableStateOf(false) }
    /** Why the last purchase didn't put credits on the balance, if it didn't. */
    var notice by remember { mutableStateOf<String?>(null) }

    /**
     * The key of the schedule to show: the trip's, when opened from a save that's short
     * (its quote names it); null for the current one, which new trips get.
     */
    val scheduleKey = shortfall?.schedule

    /** The rates to show, once loaded. */
    val schedule = pricingStore.schedule(scheduleKey)

    /** What to say in place of the rates while they load, or when they can't. */
    val scheduleStatus = if (pricingStore.isUnavailable(scheduleKey)) {
        "Pricing couldn't be loaded. Check your connection and try again."
    } else {
        "Loading pricing…"
    }

    val isPurchasing = credits.purchasingCents != null

    /**
     * Buys the picked amount through Google Play; once it's credited, hands over to
     * `onPurchased`, and otherwise says what happened.
     */
    fun purchase() {
        val cents = selectedCents
        notice = null
        // Not tied to the popup, so a purchase under way is seen through if it closes.
        OutboundApp.scope.launch {
            val outcome = if (activity == null) {
                CreditPurchaseOutcome.Failed
            } else {
                credits.purchase(cents = cents, accountId = auth.currentUser?.id, activity = activity)
            }
            notice = when (outcome) {
                CreditPurchaseOutcome.Credited, CreditPurchaseOutcome.Cancelled ->
                    null
                CreditPurchaseOutcome.AwaitingCredit ->
                    "Your payment went through. Your credits will be added as soon as we can confirm it."
                CreditPurchaseOutcome.Pending ->
                    "Your purchase is waiting for approval. Your credits will be added once it's approved."
                CreditPurchaseOutcome.Refused ->
                    credits.errorMessage ?: "Your purchase couldn't be added to this account."
                CreditPurchaseOutcome.Failed ->
                    credits.errorMessage ?: "The purchase couldn't be completed. Please try again."
            }
            if (outcome == CreditPurchaseOutcome.Credited) onPurchased()
        }
    }

    PopupCard(onClose = onClose, modifier = modifier) {
        // Each part brings the gap above it, so one that isn't showing leaves none.
        Column {
            PopupHeaderLabel("Purchase Credits", Icons.Rounded.CreditCard)

            // One column per amount, so they all sit in a single row.
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (cents in PurchasePopupMetrics.amountsCents) {
                    AmountOption(
                        cents = cents,
                        isSelected = cents == selectedCents,
                        onSelect = { selectedCents = cents },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth()
                    .background(Color.appBackground, RoundedCornerShape(appFieldCornerRadius))
                    .plainClickable(enabled = !isPurchasing, onClick = ::purchase)
                    .padding(vertical = 14.dp)
                    .semantics { if (isPurchasing) stateDescription = "Purchasing" },
                contentAlignment = Alignment.Center,
            ) {
                // Kept for its size while the spinner shows, so the button doesn't jump.
                Text("Purchase Credits", style = AppFont.body.semibold, modifier = Modifier.alpha(if (isPurchasing) 0f else 1f))
                if (isPurchasing) {
                    ProgressView(tint = Color.appForeground)
                }
            }

            var shownNotice by remember { mutableStateOf("") }
            notice?.let { if (shownNotice != it) shownNotice = it }
            AnimatedVisibility(
                visible = notice != null,
                enter = PopupCardMetrics.contentEnter,
                exit = PopupCardMetrics.contentExit,
            ) {
                Text(
                    shownNotice,
                    style = AppFont.footnote,
                    color = Color.appForeground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
                )
            }

            AnimatedContent(
                targetState = schedule?.let {
                    PurchasePopupMetrics.usableText(cents = selectedCents, members = members ?: it.includedMembers, schedule = it)
                } ?: " ",
                transitionSpec = { fadeIn(AppAnimation.snappy(0.2)) togetherWith fadeOut(AppAnimation.snappy(0.2)) },
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                label = "usable",
            ) { text ->
                Text(
                    text,
                    style = AppFont.footnote,
                    color = Color.appForeground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (shortfall != null) {
                ShortfallNote(quote = shortfall, refundsGoTo = refundsGoTo, modifier = Modifier.padding(top = 16.dp))
            }

            Hairline(Modifier.padding(top = 16.dp))

            Pricing(
                isShowing = showingPricing,
                onToggle = { showingPricing = !showingPricing },
                schedule = schedule,
                scheduleStatus = scheduleStatus,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }

    LaunchedEffect(Unit) { credits.loadProducts() }
    LaunchedEffect(scheduleKey) { pricingStore.load(scheduleKey) }
}

/**
 * The purchase popup's amounts and wording. They live here rather than on
 * `PurchasePopup`, which is a composable function and so can't hold them itself.
 */
object PurchasePopupMetrics {
    /** The amounts on offer, in cents (see `CreditProducts`). */
    val amountsCents = listOf(500, 1_500, 2_500, 5_000)

    /** The smallest amount on offer that covers `cents`, or the largest if none does. */
    fun recommendedCents(cents: Int): Int = amountsCents.firstOrNull { it >= cents } ?: amountsCents.last()

    /**
     * How many equal trips the larger amounts are described as covering, since one
     * very long trip says little about what they're good for. Others cover one.
     */
    val tripsPerAmount = mapOf(2_500 to 3, 5_000 to 6)

    /**
     * What an amount pays for, e.g. "Usable for a 17-day trip with up to 5 members"
     * or "Usable for 3 separate 7-day trips with up to 5 members".
     */
    fun usableText(cents: Int, members: Int, schedule: PricingSchedule): String {
        val trips = tripsPerAmount[cents] ?: 1
        val days = schedule.maxDays(budgetCents = cents / trips, members = members)
        val who = "$members ${if (members == 1) "member" else "members"}"
        if (days <= 0) return "Not enough for a 1-day trip with $who"
        if (trips <= 1) return "Usable for a $days-day trip with up to $who"
        return "Usable for $trips separate $days-day trips with up to $who"
    }

    /** A bracket's days, e.g. "Days 1–3" or "Day 31 and beyond". */
    fun daysText(bracket: TripPricing.Bracket): String {
        val lastDay = bracket.lastDay ?: return "Day ${bracket.firstDay} and beyond"
        return "Days ${bracket.firstDay}–$lastDay"
    }

    /** A bracket's days as just numbers, e.g. "1–7" or "31+". */
    fun rangeText(bracket: TripPricing.Bracket): String {
        val lastDay = bracket.lastDay ?: return "${bracket.firstDay}+"
        return "${bracket.firstDay}–$lastDay"
    }
}

/**
 * Why the picked amount was picked: what the save costs, what the user has, and the
 * difference it covers, with how the price adds up.
 */
@Composable
private fun ShortfallNote(quote: TripQuote, refundsGoTo: String?, modifier: Modifier = Modifier) {
    val recommended = PurchasePopupMetrics.recommendedCents(quote.shortfallCents)
    val price = formatCredits(cents = quote.priceCents)
    val balance = formatCredits(cents = quote.balanceCents)
    val shortfall = formatCredits(cents = quote.shortfallCents)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.appForeground.copy(alpha = 0.06f), RoundedCornerShape(appFieldCornerRadius))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("You need $shortfall more credits", style = AppFont.headline)
        Text(
            "This trip costs $price and you have $balance, so $price − $balance = $shortfall. " +
                "We picked ${formatCredits(cents = recommended)}, the smallest amount that covers it.",
            style = AppFont.footnote,
            color = Color.appForeground.copy(alpha = 0.7f),
        )
        PriceBreakdown(quote = quote, modifier = Modifier.padding(top = 4.dp))
        if (refundsGoTo != null) {
            OwnerRefundNote(owner = refundsGoTo, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * How credits are spent, collapsed until asked for: each trip day at its bracket's
 * rate, plus each member beyond the included ones at theirs, from the schedule.
 */
@Composable
private fun Pricing(
    isShowing: Boolean,
    onToggle: () -> Unit,
    schedule: PricingSchedule?,
    scheduleStatus: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        DisclosureHeader(title = "How trip pricing works", isExpanded = isShowing, onToggle = onToggle)

        // The rates last shown, kept while they fade away.
        var shownSchedule by remember { mutableStateOf(schedule) }
        if (schedule != null && shownSchedule != schedule) shownSchedule = schedule
        AnimatedVisibility(
            visible = isShowing && schedule != null,
            enter = PopupCardMetrics.contentEnter,
            exit = PopupCardMetrics.contentExit,
        ) {
            shownSchedule?.let { rates ->
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "You pay once per trip, based on how many days it is. Each day is priced at the rate for its range, so longer trips cost less per day.",
                        style = AppFont.footnote,
                        color = Color.appForeground.copy(alpha = 0.7f),
                    )
                    RatesTable(rates.tripBrackets, rateHeader = "Per day")

                    Text(
                        "A standard trip includes unlimited collaboration between ${rates.includedMembers} people. Additional member costs are calculated per day and decrease with trip length and group size. (example: 3 extra members on a 3-day trip costs the same as 1 extra member on a 9 day trip",
                        style = AppFont.footnote,
                        color = Color.appForeground.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    RatesTable(
                        rates.memberBrackets,
                        daysHeader = "Member-days",
                        rateHeader = "Per person, per day",
                        rangesOnly = true,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = isShowing && schedule == null,
            enter = PopupCardMetrics.contentEnter,
            exit = PopupCardMetrics.contentExit,
        ) {
            Text(
                scheduleStatus,
                style = AppFont.footnote,
                color = Color.appForeground.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * Brackets as a bordered two-column table: the days, then the rate. With
 * `rangesOnly`, rows show just the numbers, e.g. "1–7" or "31+".
 */
@Composable
private fun RatesTable(
    brackets: List<TripPricing.Bracket>,
    daysHeader: String = "Trip days",
    rateHeader: String,
    rangesOnly: Boolean = false,
) {
    val shape = RoundedCornerShape(appFieldCornerRadius)
    Column(Modifier.clip(shape).border(1.dp, Color.White.copy(alpha = 0.1f), shape)) {
        TableRow(
            days = daysHeader,
            rate = rateHeader,
            fill = Color.White.copy(alpha = 0.08f),
            style = AppFont.caption.semibold,
            color = Color.appForeground.copy(alpha = 0.6f),
        )
        brackets.forEachIndexed { index, bracket ->
            // Every other row shaded, so a rate is easy to follow across.
            TableRow(
                days = if (rangesOnly) PurchasePopupMetrics.rangeText(bracket) else PurchasePopupMetrics.daysText(bracket),
                rate = formatCredits(cents = bracket.centsPerDay),
                fill = if (index % 2 == 0) Color.Transparent else Color.White.copy(alpha = 0.04f),
                style = AppFont.footnote,
                rateStyle = AppFont.footnote.monospacedDigit,
            )
        }
    }
}

@Composable
private fun TableRow(
    days: String,
    rate: String,
    fill: Color,
    style: TextStyle,
    rateStyle: TextStyle = style,
    color: Color = Color.appForeground,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(fill)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(days, style = style, color = color, modifier = Modifier.weight(1f))
        Text(rate, style = rateStyle, color = color)
    }
}

@Composable
private fun AmountOption(cents: Int, isSelected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(appFieldCornerRadius)
    Box(
        modifier = modifier
            .background(Color.appForeground.copy(alpha = if (isSelected) 0.16f else 0.06f), shape)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) Color.appBackground else Color.White.copy(alpha = 0.08f),
                shape = shape,
            )
            .clip(shape)
            .plainClickable(onClick = onSelect)
            .padding(vertical = 14.dp)
            .semantics {
                role = Role.RadioButton
                selected = isSelected
            },
        contentAlignment = Alignment.Center,
    ) {
        // Shrinks, down to 70%, where it's too wide for its column.
        ShrinkingText(
            formatCredits(cents = cents).replace(".00", ""),
            style = AppFont.title3.bold.monospacedDigit,
            minimumScaleFactor = 0.7f,
        )
    }
}

// MARK: - Shared by the credits popups

/** The full-width button a popup about credits acts with, filled with `background`. */
@Composable
internal fun CreditPopupButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = Color.appBackground,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(appFieldCornerRadius))
            .plainClickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = AppFont.body.semibold, color = Color.appForeground)
    }
}

/**
 * A row that opens and closes what's under it: its title, and a chevron that turns
 * over when it's open.
 */
@Composable
internal fun DisclosureHeader(title: String, isExpanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val turn by animateFloatAsState(if (isExpanded) 180f else 0f, PopupCardMetrics.resizeAnimation(), label = "chevron")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .plainClickable(onClick = onToggle)
            .semantics { stateDescription = if (isExpanded) "Expanded" else "Collapsed" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = AppFont.subheadline.semibold, modifier = Modifier.weight(1f))
        Icon(
            Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = Color.appForeground.copy(alpha = 0.6f),
            modifier = Modifier.size(scaledSymbolSize(AppFont.footnote, scale = 1.5f)).rotate(turn),
        )
    }
}

/** The activity this context belongs to, which Google Play shows its purchase sheet over. */
private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

@Preview
@Composable
private fun RatesTablePreview() {
    AppTheme {
        Box(Modifier.fillMaxSize().background(Brush.tripsBackground).padding(16.dp)) {
            RatesTable(
                listOf(
                    TripPricing.Bracket(firstDay = 1, lastDay = 3, centsPerDay = 160),
                    TripPricing.Bracket(firstDay = 4, lastDay = 30, centsPerDay = 80),
                    TripPricing.Bracket(firstDay = 31, centsPerDay = 40),
                ),
                rateHeader = "Per day",
            )
        }
    }
}
