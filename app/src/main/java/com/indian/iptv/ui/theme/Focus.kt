package com.indian.iptv.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * One focus recipe for rows, tabs and chips: a lighter fill (via [FocusStyle.Fill] as the
 * Surface's focusedContainerColor), a 2dp light outline and a subtle scale. A selected item
 * keeps its accent fill; focus adds the outline and scale on top, so "selected" and
 * "selected + focused" always look different.
 */
object FocusStyle {
    val Fill = Color(0xFF3B3F4C)
    val Border = IptvPalette.TextPrimary
    /** Soft accent halo drawn around focused items so focus stays visible over dark artwork. */
    val Glow = IptvPalette.AccentSoft
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
            // An accent-tinted elevation shadow reads as a glow on the dark background and
            // keeps focus visible even when the item sits on near-black poster art.
            .then(
                if (focused) Modifier.shadow(
                    elevation = 10.dp,
                    shape = shape,
                    ambientColor = FocusStyle.Glow.copy(alpha = 0.30f),
                    spotColor = FocusStyle.Glow.copy(alpha = 0.85f),
                ) else Modifier,
            )
            .scale(scale)
            .then(if (focused) Modifier.border(2.dp, FocusStyle.Border, shape) else Modifier)
    }
