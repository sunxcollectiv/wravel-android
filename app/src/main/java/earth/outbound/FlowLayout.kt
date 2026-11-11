//
//  FlowLayout.kt
//  outbound
//

package earth.outbound

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

/**
 * Lays its children out left to right, wrapping onto a new line when the next one
 * doesn't fit. Used for rows of chips.
 */
@Composable
fun FlowLayout(
    spacing: Dp = 8.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val spacingPx = spacing.roundToPx()
        // Each child at its own natural size, though never wider than a whole line.
        val childConstraints = Constraints(maxWidth = constraints.maxWidth)
        val placeables = measurables.map { it.measure(childConstraints) }
        val rows = arrange(placeables, maxWidth = constraints.maxWidth, spacing = spacingPx)

        val height = rows.lastOrNull()?.let { it.y + it.height } ?: 0
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else rows.maxOfOrNull { it.width } ?: 0
        layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
            for (row in rows) {
                var x = 0
                for (index in row.indices) {
                    val placeable = placeables[index]
                    placeable.placeRelative(x, row.y)
                    x += placeable.width + spacingPx
                }
            }
        }
    }
}

private class FlowRow(val y: Int = 0) {
    val indices = mutableListOf<Int>()
    var width = 0
    var height = 0
}

/** Groups children into rows that fit within `maxWidth`. */
private fun arrange(placeables: List<Placeable>, maxWidth: Int, spacing: Int): List<FlowRow> {
    val rows = mutableListOf<FlowRow>()
    var current = FlowRow()
    placeables.forEachIndexed { index, placeable ->
        val neededWidth = if (current.indices.isEmpty()) placeable.width else current.width + spacing + placeable.width
        if (neededWidth > maxWidth && current.indices.isNotEmpty()) {
            rows.add(current)
            current = FlowRow(y = current.y + current.height + spacing)
        }
        current.width = if (current.indices.isEmpty()) placeable.width else current.width + spacing + placeable.width
        current.height = maxOf(current.height, placeable.height)
        current.indices.add(index)
    }
    if (current.indices.isNotEmpty()) rows.add(current)
    return rows
}
