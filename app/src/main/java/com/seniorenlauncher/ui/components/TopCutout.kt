package com.seniorenlauncher.ui.components

import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where the camera hole / notch sits along the top edge.
 *
 * The system status bar is hidden, so screens draw all the way to the top. Rather than leaving
 * an empty band across the whole width, only elements that would land under the cutout move.
 *
 * @param left   width taken from the left edge by a cutout in the left third (0 if none)
 * @param right  width taken from the right edge by a cutout in the right third (0 if none)
 * @param center height of a cutout in the middle third (0 if none)
 * @param height total height of the top cutout area
 */
data class TopCutout(val left: Dp = 0.dp, val right: Dp = 0.dp, val center: Dp = 0.dp, val height: Dp = 0.dp)

@Composable
fun rememberTopCutout(): TopCutout {
    val density = LocalDensity.current
    val view = LocalView.current
    // Reading the inset subscribes to changes, so this recomposes once the window has its insets.
    val topPx = WindowInsets.displayCutout.getTop(density)
    if (topPx == 0) return TopCutout()
    val height = with(density) { topPx.toDp() }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return TopCutout(center = height, height = height)
    val cutout = view.rootWindowInsets?.displayCutout ?: return TopCutout(center = height, height = height)
    val w = view.width.takeIf { it > 0 } ?: view.resources.displayMetrics.widthPixels
    var left = 0
    var right = 0
    var center = 0
    for (r in cutout.boundingRects) {
        if (r.isEmpty || r.top > 0) continue // only cutouts on the top edge
        when {
            r.right <= w / 3 -> left = maxOf(left, r.right)
            r.left >= w * 2 / 3 -> right = maxOf(right, w - r.left)
            else -> center = maxOf(center, r.bottom)
        }
    }
    // Px → dp with the app's own density (it scales with the text size), so the values line up with layout dp.
    return with(density) { TopCutout(left.toDp(), right.toDp(), center.toDp(), height) }
}
