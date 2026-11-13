package com.indian.iptv.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Orange = Color(0xFFEF6C00)
private val DarkBackground = Color(0xFF101014)
private val DarkSurface = Color(0xFF1C1C22)

/**
 * Plain Material 3 theme (phone widgets only — no TV material anywhere).
 */
@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(primary = Orange, background = DarkBackground, surface = DarkSurface)
    } else {
        lightColorScheme(primary = Orange)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
