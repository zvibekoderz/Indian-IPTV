package com.indian.iptv.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.indian.iptv.mobile.data.Channel
import com.indian.iptv.mobile.data.Epg
import com.indian.iptv.mobile.data.M3uParser
import com.indian.iptv.mobile.data.PlaylistFetcher
import com.indian.iptv.mobile.data.RecentlyWatched
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
        // Content draws edge-to-edge from the very first frame on every
        // screen — no title bar, no inset band. The player additionally
        // hides the system bars entirely while visible.
        WindowCompat.setDecorFitsSystemWindows(window, false)
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
        screen = Screen.Player(channel)
    }

    // Load once on start: instant from cache when available, otherwise fetch.
    LaunchedEffect(Unit) {
        load(forceRefresh = false)
        // Continue watching: jump straight back into the last channel.
        recentChannels.firstOrNull()?.let { screen = Screen.Player(it) }
    }

    // Load the XMLTV guide (cached up to 6h) whenever the URL is set/changed.
    LaunchedEffect(settings.epgUrl) {
        val url = settings.epgUrl.trim()
        if (url.isBlank()) {
            epg = emptyMap()
        } else {
            Epg.load(context, url, forceRefresh = false)?.let { epg = it }
        }
    }

    val nowNextOf = remember(epg) {
        { channel: Channel -> Epg.lookup(epg, channel.tvgId, channel.name) }
    }

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
            recent = recentChannels,
            nowNextOf = nowNextOf,
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
            settings = settings,
            nowPlaying = nowNextOf(current.channel),
            onClose = { screen = Screen.Channels },
        )
    }
}
