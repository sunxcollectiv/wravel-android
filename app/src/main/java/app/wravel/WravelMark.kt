//
//  WravelMark.kt
//  wravel
//

package app.wravel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * The wravel W ("Fold"): a flat ribbon folded into a W, its four faces alternating
 * full and half opacity so the fold reads in any tint. Same geometry as the launcher
 * foreground and wravel-ios's `WravelMark`. Drawn 2:1 (width:height) in `color`.
 */
@Composable
fun WravelMark(modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    Canvas(
        modifier
            .aspectRatio(2f)
            .semantics { contentDescription = "wravel" },
    ) {
        val scale = size.width / 54f
        foldFaces.forEachIndexed { index, face ->
            val path = Path().apply {
                face.forEachIndexed { i, point ->
                    val x = point.x * scale
                    val y = point.y * scale
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(path, if (index % 2 == 0) color.copy(alpha = color.alpha * 0.5f) else color)
        }
    }
}

/** The faces of the fold, back to front, in a 54 x 27 unit box. */
private val foldFaces: List<List<Offset>> = listOf(
    listOf(Offset(33.75f, 27f), Offset(42.75f, 27f), Offset(54f, 0f), Offset(45f, 0f)),
    listOf(Offset(22.5f, 0f), Offset(31.5f, 0f), Offset(42.75f, 27f), Offset(33.75f, 27f)),
    listOf(Offset(11.25f, 27f), Offset(20.25f, 27f), Offset(31.5f, 0f), Offset(22.5f, 0f)),
    listOf(Offset(0f, 0f), Offset(9f, 0f), Offset(20.25f, 27f), Offset(11.25f, 27f)),
)
