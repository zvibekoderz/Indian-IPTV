package com.indian.iptv.ui.addons

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.items
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.itemsIndexed as rowItemsIndexed
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.compose.material3.Icon
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.indian.iptv.ui.theme.tvFocus
import com.indian.iptv.IptvApp
import com.indian.iptv.R
import com.indian.iptv.data.Channel
import com.indian.iptv.data.ContentType
import com.indian.iptv.data.addon.StremioAddon
import com.indian.iptv.ui.channels.PosterCard
import com.indian.iptv.ui.theme.IptvPalette
import com.indian.iptv.ui.theme.tvFocus
import kotlinx.coroutines.launch

/**
 * On-demand catalogues exposed by a Stremio-compatible add-on (e.g. CNCVerse
 * Bridge). This is a thin client of the public add-on REST protocol; it does
 * not embed or depend on the Bridge codebase.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddonsScreen(
    onBack: () -> Unit,
    onPlay: (url: String, title: String, headers: Map<String, String>) -> Unit,
) {
    val app = IptvApp.get()
    val scope = rememberCoroutineScope()
    val savedUrl by app.settings.addonUrl.collectAsState(initial = "")

    var input by remember { mutableStateOf("") }
    var activeUrl by remember { mutableStateOf("") }
    var addon by remember { mutableStateOf<StremioAddon?>(null) }
    var manifest by remember { mutableStateOf<StremioAddon.Manifest?>(null) }
    var loading by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Remember the last-selected catalogue per add-on URL so returning to the screen
    // lands on the same catalogue instead of always the first one.
    var selectedIndex by remember(activeUrl) { mutableIntStateOf(0) }
    var metas by remember { mutableStateOf<List<StremioAddon.Meta>>(emptyList()) }
    val catalogFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    var pendingMeta by remember { mutableStateOf<StremioAddon.Meta?>(null) }
    var streams by remember { mutableStateOf<List<StremioAddon.Stream>>(emptyList()) }

    LaunchedEffect(savedUrl) {
        if (savedUrl.isNotBlank()) {
            input = savedUrl
            activeUrl = savedUrl
        }
    }

    LaunchedEffect(activeUrl) {
        if (activeUrl.isBlank()) {
            addon = null
            manifest = null
            return@LaunchedEffect
        }
        val instance = StremioAddon.create(activeUrl)
        if (instance == null) {
            addon = null
            manifest = null
            error = "Enter a valid http(s) add-on URL"
            return@LaunchedEffect
        }
        loading = true
        error = null
        manifest = null
        runCatching { instance.manifest() }
            .onSuccess { loaded ->
                addon = instance
                manifest = loaded
                selectedIndex = 0
            }
            .onFailure { failure ->
                addon = null
                error = failure.message ?: "Couldn't reach the add-on"
            }
        loading = false
    }

    val catalogs = manifest?.catalogs.orEmpty()
    val selectedCatalog = catalogs.getOrNull(selectedIndex)

    LaunchedEffect(addon, selectedCatalog?.type, selectedCatalog?.id) {
        val current = addon
        val catalog = selectedCatalog
        if (current == null || catalog == null) {
            metas = emptyList()
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching { current.catalog(catalog.type, catalog.id) }
            .onSuccess { metas = it }
            .onFailure { failure -> error = failure.message ?: "Couldn't load catalogue" }
        loading = false
    }

    LaunchedEffect(pendingMeta) {
        val meta = pendingMeta
        val current = addon
        streams = emptyList()
        if (meta == null || current == null) return@LaunchedEffect
        loading = true
        error = null
        runCatching { current.streams(meta.type, meta.id) }
            .onSuccess { found ->
                streams = found
                if (found.isEmpty()) error = "No direct-playable stream for this title"
            }
            .onFailure { failure -> error = failure.message ?: "Couldn't load streams" }
        loading = false
    }

    // Focus the first catalogue pill when the catalogue list appears so D-pad
    // navigation lands on something focusable instead of wandering the URL field.
    LaunchedEffect(catalogs.isNotEmpty()) {
        if (catalogs.isNotEmpty()) runCatching { catalogFocus.requestFocus() }
    }

    // Focus the first poster in the grid when a catalogue's items load.
    LaunchedEffect(metas.isNotEmpty()) {
        if (metas.isNotEmpty()) runCatching { gridFocus.requestFocus() }
    }

    BackHandler {
        if (pendingMeta != null) pendingMeta = null else onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(IptvPalette.AccentDeep.copy(alpha = 0.24f), IptvPalette.BackgroundDeep),
                    radius = 1400f,
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "ADD-ONS",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Black,
                        color = IptvPalette.Accent,
                        letterSpacing = 2.sp,
                    ),
                )
                Spacer(Modifier.weight(1f))
                var backFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = onBack,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = IptvPalette.SurfaceElevated.copy(alpha = 0.6f),
                        focusedContainerColor = IptvPalette.Accent,
                        contentColor = Color.White,
                        focusedContentColor = Color.White,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    modifier = Modifier
                        .size(44.dp)
                        .onFocusChanged { backFocused = it.isFocused }
                        .tvFocus(backFocused, RoundedCornerShape(12.dp)),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.channels_settings_hint),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                var connectFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = {
                        connecting = true
                        scope.launch {
                            app.settings.setAddonUrl(input)
                            activeUrl = input
                            connecting = false
                        }
                    },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (connecting) IptvPalette.TextTertiary else IptvPalette.Accent,
                        focusedContainerColor = IptvPalette.AccentDeep,
                        contentColor = Color.White,
                        focusedContentColor = Color.White,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    modifier = Modifier
                        .size(100.dp)
                        .enabled(!connecting)
                        .onFocusChanged { connectFocused = it.isFocused }
                        .tvFocus(connectFocused, RoundedCornerShape(12.dp)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Cloud,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (connecting) "Connecting…" else "Connect",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            var urlFocused by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { androidx.compose.material3.Text("Add-on URL (manifest.json or host)") },
                placeholder = { androidx.compose.material3.Text("http://127.0.0.1:8080/manifest.json") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .onFocusChanged { urlFocused = it.isFocused }
                    .tvFocus(urlFocused, RoundedCornerShape(8.dp)),
            )
            Text(
                text = "CNCVerse Bridge shows this URL when its server is running. " +
                    "Use the phone/TV's own 127.0.0.1 when Bridge runs on the same device, " +
                    "or a LAN address when it runs on another one.",
                style = MaterialTheme.typography.bodySmall.copy(color = IptvPalette.TextSecondary),
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(16.dp))

            when {
                loading && manifest == null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Connecting to add-on…")
                }

                error != null && manifest == null -> Text(
                    text = error ?: "Add-on unavailable",
                    style = MaterialTheme.typography.bodyMedium.copy(color = IptvPalette.Accent),
                )

                manifest == null -> Text(
                    text = "Enter an add-on URL above and press Connect.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> {
                    Text(
                        text = manifest?.name.orEmpty(),
                        style = MaterialTheme.typography.titleMedium.copy(color = IptvPalette.TextPrimary),
                    )
                    Spacer(Modifier.height(8.dp))
                    if (catalogs.isEmpty()) {
                        Text("This add-on publishes no catalogues.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        TvLazyRow(
                            state = androidx.tv.foundation.lazy.list.rememberTvLazyListState(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.focusRequester(catalogFocus),
                        ) {
                            rowItemsIndexed(catalogs) { index, catalog ->
                                val active = index == selectedIndex
                                var pillFocused by remember { mutableStateOf(false) }
                                Surface(
                                    onClick = { selectedIndex = index },
                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = if (active) IptvPalette.Accent else IptvPalette.SurfaceLift,
                                        focusedContainerColor = if (active) IptvPalette.AccentDeep else IptvPalette.SurfaceElevated,
                                        contentColor = if (active) IptvPalette.BackgroundDeep else IptvPalette.TextPrimary,
                                        focusedContentColor = Color.White,
                                    ),
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                        .onFocusChanged { pillFocused = it.isFocused }
                                        .tvFocus(pillFocused, RoundedCornerShape(20.dp)),
                                ) {
                                    Text(
                                        text = "${catalog.name} · ${catalog.type}",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = if (active || pillFocused) Color.White else IptvPalette.TextPrimary,
                                            fontWeight = if (active || pillFocused) FontWeight.Bold else FontWeight.Normal,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                // Selection happens on OK click only — navigating the
                                // D-pad across catalog pills must not switch the
                                // catalogue out from under the user.
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (loading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        error?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall.copy(color = IptvPalette.Accent))
                        }
                        TvLazyVerticalGrid(
                            columns = TvGridCells.Adaptive(168.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(gridFocus),
                            contentPadding = PaddingValues(bottom = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            items(metas, key = { it.type + it.id }) { meta ->
                                PosterCard(
                                    channel = Channel(
                                        id = "addon-meta:${meta.type}:${meta.id}",
                                        name = meta.name,
                                        logoUrl = meta.poster,
                                        groupTitle = meta.type,
                                        streamUrl = null,
                                        epgChannelId = null,
                                        type = if (meta.type == "series") ContentType.SERIES else ContentType.MOVIE,
                                    ),
                                    progressFraction = null,
                                    onClick = { pendingMeta = meta },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (pendingMeta != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(IptvPalette.BackgroundDeep.copy(alpha = 0.82f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(IptvPalette.SurfaceElevated)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = pendingMeta?.name.orEmpty(),
                        style = MaterialTheme.typography.titleLarge.copy(color = IptvPalette.TextPrimary),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when {
                        loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        streams.isEmpty() -> Text(
                            text = error ?: "No direct-playable stream found.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = IptvPalette.AccentSoft),
                        )
                        else -> streams.forEach { stream ->
                            var streamBtnFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = {
                                    onPlay(
                                        stream.url,
                                        pendingMeta?.name ?: stream.label,
                                        stream.headers,
                                    )
                                },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = IptvPalette.SurfaceElevated,
                                    focusedContainerColor = IptvPalette.Accent,
                                    contentColor = IptvPalette.TextPrimary,
                                    focusedContentColor = Color.White,
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .onFocusChanged { streamBtnFocused = it.isFocused }
                                    .tvFocus(streamBtnFocused, RoundedCornerShape(8.dp)),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        text = stream.label,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.keyhint_ok_play) + "  ·  " + stringResource(R.string.keyhint_back_exit),
                        style = MaterialTheme.typography.labelSmall.copy(color = IptvPalette.TextTertiary),
                    )
                    Button(onClick = { pendingMeta = null }) { Text("Close") }
                }
            }
        }
    }
}
