package com.indian.iptv.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * One focus recipe for rows, tabs and chips: a crisp white outline plus a subtle scale, with
 * no glow. White matches the app's primary text and reads cleanly on every background, while
 * the pink accent stays reserved for "selected" state — so "selected" and "selected + focused"
 * are always visually distinct. Keeping the ring in a Modifier (not a shadow) avoids the
 * glossy halo tv-material otherwise paints over focused surfaces.
 */
object FocusStyle {
    val Fill = Color(0xFF3B3F4C)
    /** Crisp white focus ring — matches the app's primary text. */
    val Border = Color.White
    const val RowScale = 1.02f
    const val ChipScale = 1.06f
}

fun Modifier.tvFocus(focused: Boolean, shape: Shape, focusedScale: Float = FocusStyle.RowScale): Modifier =
    composed {
        val scale by animateFloatAsState(
            targetValue = if (focused) focusedScale else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "tv-focus-scale",
        )
        this
            .scale(scale)
            .then(if (focused) Modifier.border(2.dp, FocusStyle.Border, shape) else Modifier)
    }
