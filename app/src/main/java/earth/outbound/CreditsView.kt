//
//  CreditsView.kt
//  outbound
//

@file:UseSerializers(InstantSerializer::class)

package earth.outbound

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import earth.outbound.networking.InstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * One line in the user's credit ledger (`CreditEntryRead`): credits added, or spent
 * on a trip.
 */
@Serializable
data class CreditLedgerEntry(
    val id: String,
    /** What the line is for, e.g. "Credits purchased" or "Unlocked Tokyo 2026". */
    val title: String,
    /**
     * Whole cents, positive for credits added and negative for credits spent, so
     * amounts add up exactly.
     */
    val amountCents: Int,
    @SerialName("created_at")
    val date: Instant,
)

/** Formats cents as dollars, e.g. 1050 → "$10.50"; with `signed`, "+$10.50" or "−$4.99". */
fun formatCredits(cents: Int, signed: Boolean = false): String {
    val amount = abs(cents.toLong())
    val dollars = String.format(Locale.US, "$%,d.%02d", amount / 100, amount % 100)
    if (!signed) return if (cents < 0) "-$dollars" else dollars
    return (if (cents < 0) "−" else "+") + dollars
}

/**
 * The user's credits, pushed from the drawer: the balance, a button to buy more, and
 * the activity behind the balance, newest first.
 */
@Composable
fun CreditsView(
    /** Goes back to the trips screen. */
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val credits = LocalCreditStore.current

    var showingPurchase by remember { mutableStateOf(false) }

    val entries = credits.entries

    ForegroundStyle(Color.appForeground) {
        Column(
            modifier
                .fillMaxSize()
                .offlinePillAtBottom()
        ) {
            PushedScreenBar(title = "Credits", onBack = onBack)
            Refreshable(onRefresh = { credits.load() }, modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                ) {
                    item(key = "balance") {
                        BalanceHeader(
                            balanceCents = credits.balanceCents,
                            onAddCredits = { showingPurchase = true },
                            modifier = Modifier.padding(horizontal = rowInset),
                        )
                    }
                    item(key = "activity") {
                        Text(
                            "Activity",
                            style = AppFont.subheadline.semibold,
                            color = Color.appForeground.copy(alpha = 0.7f),
                            modifier = Modifier
                                .padding(horizontal = rowInset)
                                .padding(top = 16.dp, bottom = 6.dp)
                                .semantics { heading() },
                        )
                    }
                    if (entries.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                if (credits.credits == null) credits.errorMessage ?: "Loading…" else "No activity yet",
                                style = AppFont.subheadline,
                                color = Color.appForeground.copy(alpha = 0.7f),
                                modifier = Modifier.padding(PaddingValues(horizontal = rowInset, vertical = 11.dp)),
                            )
                        }
                    } else {
                        items(entries, key = { it.id }) { entry ->
                            Column(Modifier.padding(start = rowInset)) {
                                CreditLedgerRow(
                                    entry = entry,
                                    modifier = Modifier.padding(end = rowInset).padding(vertical = 11.dp),
                                )
                                ListSeparator(Modifier.padding(start = 48.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // The open buy popup over a dimmed page; tapping outside closes it.
    DimmedPopup(isPresented = showingPurchase, onClose = { showingPurchase = false }) {
        PurchasePopup(onClose = { showingPurchase = false }, onPurchased = { showingPurchase = false })
    }

    LaunchedEffect(Unit) { credits.load() }
}

/** The margin either side of the page's rows. */
private val rowInset = 20.dp

@Composable
private fun BalanceHeader(balanceCents: Int?, onAddCredits: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Balance", style = AppFont.subheadline, color = Color.appForeground.copy(alpha = 0.7f))
        Text(
            balanceCents?.let { formatCredits(cents = it) } ?: "–",
            style = AppFont.system(44f, FontWeight.Bold).monospacedDigit,
            maxLines = 1,
        )
        Row(
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
                .background(Color.appBackground, RoundedCornerShape(appFieldCornerRadius))
                .plainClickable(onClick = onAddCredits)
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.AddCircle, contentDescription = null, modifier = Modifier.size(22.dp))
            Text("Add Credits", style = AppFont.body.semibold)
        }
    }
}

/** One ledger line: what it was for, when, and the amount in or out. */
@Composable
fun CreditLedgerRow(entry: CreditLedgerEntry, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = 6.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).background(Color.White.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                if (entry.amountCents >= 0) Icons.Rounded.Add else Icons.Outlined.LockOpen,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.title, style = AppFont.subheadline.semibold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                WallClock.monthDayYear.format(entry.date.atZone(ZoneId.systemDefault())),
                style = AppFont.caption,
                color = Color.appForeground.copy(alpha = 0.5f),
            )
        }
        Text(
            formatCredits(cents = entry.amountCents, signed = true),
            style = AppFont.subheadline.semibold.monospacedDigit,
            color = if (entry.amountCents >= 0) Color.systemGreen else Color.appForeground,
            maxLines = 1,
        )
    }
}

