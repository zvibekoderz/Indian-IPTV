package com.indian.iptv.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.indian.iptv.mobile.data.BridgeTvCache
import com.indian.iptv.mobile.data.Channel
import com.indian.iptv.mobile.data.Epg
import com.indian.iptv.mobile.data.M3uParser
import com.indian.iptv.mobile.data.PlaylistFetcher
import com.indian.iptv.mobile.data.RecentlyWatched
import com.indian.iptv.mobile.data.Settings
import com.indian.iptv.mobile.ui.AddonsScreen
import com.indian.iptv.mobile.ui.BridgeTvScreen
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
        BridgeTvCache.init(applicationContext)
        // Content draws edge-to-edge from the very first frame on every
        // screen — no title bar, no inset band. The player additionally
        // hides the system bars entirely while visible.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            IptvTheme { AppRoot() }
        }
    }
}

/** Which tab was visible when the player was opened — where Back should return. */
private enum class Tab { Live, Bridge, Settings }

private sealed interface Screen {
    data object Channels : Screen
    data object BridgeTv : Screen
    data object Settings : Screen
    data object Addons : Screen
    data class Player(val channel: Channel, val origin: Tab = Tab.Live) : Screen
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings = remember { Settings(context) }
    val fetcher = remember { PlaylistFetcher(context) }
    val recent = remember { RecentlyWatched(context) }

    var screen by remember { mutableStateOf<Screen>(Screen.Channels) }

    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var favourites by remember { mutableStateOf(settings.favourites) }
    var recentChannels by remember { mutableStateOf(recent.all()) }
    var epg by remember { mutableStateOf<Map<String, Epg.NowNext>>(emptyMap()) }

    fun toggleFavourite(channel: Channel) {
        val next = if (channel.url in favourites) favourites - channel.url else favourites + channel.url
        favourites = next
        settings.favourites = next
    }

    fun load(forceRefresh: Boolean) {
        scope.launch {
            loading = true
            error = null
            val result = fetcher.fetch(settings.m3uUrls, forceRefresh)
            channels = result.channels.ifEmpty { null }
            if (result.channels.isEmpty()) {
                error = if (result.failures.isEmpty()) {
                    "No channels found in the playlists"
                } else {
                    "Couldn't load any playlist: " + result.failures.joinToString("; ")
                }
            }
            loading = false
        }
    }

    fun openChannel(channel: Channel) {
        recent.record(channel)
        recentChannels = recent.all()
        screen = Screen.Player(channel, Tab.Live)
    }

    // Load once on start: instant from cache when available, otherwise fetch.
    LaunchedEffect(Unit) {
        load(forceRefresh = false)
        // Continue watching: jump straight back into the last channel.
        recentChannels.firstOrNull()?.let { screen = Screen.Player(it, Tab.Live) }
    }

    // Load the XMLTV guide(s, cached up to 6h) whenever the setting is changed.
    LaunchedEffect(settings.epgUrl) {
        val urls = settings.epgUrl.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() }
        if (urls.isEmpty()) {
            epg = emptyMap()
        } else {
            Epg.load(context, urls, forceRefresh = false)?.let { epg = it }
        }
    }

    val nowNextOf = remember(epg) {
        { channel: Channel -> Epg.lookup(epg, channel.tvgId, channel.name) }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (val current = screen) {
                Screen.Channels -> ChannelsScreen(
                    channels = channels,
                    loading = loading,
                    error = error,
                    favourites = favourites,
                    onToggleFavourite = ::toggleFavourite,
                    onOpenChannel = ::openChannel,
                    onRefresh = { load(forceRefresh = true) },
                    onOpenSettings = { screen = Screen.Settings },
                    onOpenAddons = { screen = Screen.Addons },
                    recent = recentChannels,
                    nowNextOf = nowNextOf,
                )

                Screen.BridgeTv -> BridgeTvScreen(
                    settings = settings,
                    onOpenCatalogs = { screen = Screen.Addons },
                    onPlay = { channel -> screen = Screen.Player(channel, Tab.Bridge) },
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

                Screen.Addons -> AddonsScreen(
                    settings = settings,
                    onBack = { screen = Screen.BridgeTv },
                    onPlay = { channel -> screen = Screen.Player(channel, Tab.Bridge) },
                )

                is Screen.Player -> PlayerScreen(
                    channel = current.channel,
                    settings = settings,
                    nowPlaying = nowNextOf(current.channel),
                    onClose = {
                        screen = when (current.origin) {
                            Tab.Live -> Screen.Channels
                            Tab.Bridge -> Screen.BridgeTv
                            Tab.Settings -> Screen.Settings
                        }
                    },
                )
            }
        }

        // The player stays full-screen; every other screen gets the three tabs.
        if (screen !is Screen.Player) {
            NavigationBar {
                NavigationBarItem(
                    selected = screen == Screen.Channels,
                    onClick = { screen = Screen.Channels },
                    icon = { Icon(Icons.Filled.List, contentDescription = null) },
                    label = { Text("Live TV") },
                )
                NavigationBarItem(
                    selected = screen == Screen.BridgeTv || screen == Screen.Addons,
                    onClick = { screen = Screen.BridgeTv },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    label = { Text("Bridge TV") },
                )
                NavigationBarItem(
                    selected = screen == Screen.Settings,
                    onClick = { screen = Screen.Settings },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        }
    }
}
