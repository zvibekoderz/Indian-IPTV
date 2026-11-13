package com.indian.iptv.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import com.indian.iptv.mobile.data.Channel
import com.indian.iptv.mobile.data.M3uParser
import com.indian.iptv.mobile.data.PlaylistFetcher
import com.indian.iptv.mobile.data.Settings
import com.indian.iptv.mobile.ui.ChannelsScreen
import com.indian.iptv.mobile.ui.IptvTheme
import com.indian.iptv.mobile.ui.PlayerScreen
import com.indian.iptv.mobile.ui.SettingsScreen
import kotlinx.coroutines.launch

/**
 * Single-activity Compose app. Screens are a simple sealed state — no
 * navigation library, no view-model framework.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IptvTheme { AppRoot() }
        }
    }
}

private sealed interface Screen {
    data object Channels : Screen
    data object Settings : Screen
    data class Player(val channel: Channel) : Screen
}

@androidx.compose.runtime.Composable
private fun AppRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val settings = remember { Settings(context) }
    val fetcher = remember { PlaylistFetcher(context) }

    var screen by remember { mutableStateOf<Screen>(Screen.Channels) }

    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(forceRefresh: Boolean) {
        scope.launch {
            loading = true
            error = null
            when (val result = fetcher.fetch(settings.m3uUrl, forceRefresh)) {
                is PlaylistFetcher.Result.Success ->
                    channels = M3uParser.parse(result.text).ifEmpty {
                        error = "No channels found in the playlist"
                        null
                    }
                is PlaylistFetcher.Result.Failure ->
                    error = result.message
            }
            loading = false
        }
    }

    // Load once on start: instant from cache when available, otherwise fetch.
    LaunchedEffect(Unit) { load(forceRefresh = false) }

    when (val current = screen) {
        Screen.Channels -> ChannelsScreen(
            channels = channels,
            loading = loading,
            error = error,
            onOpenChannel = { screen = Screen.Player(it) },
            onRefresh = { load(forceRefresh = true) },
            onOpenSettings = { screen = Screen.Settings },
        )

        Screen.Settings -> SettingsScreen(
            settings = settings,
            onSave = {
                screen = Screen.Channels
                channels = null
                load(forceRefresh = true)
            },
            onBack = { screen = Screen.Channels },
        )

        is Screen.Player -> PlayerScreen(
            channel = current.channel,
            onClose = { screen = Screen.Channels },
        )
    }
}
