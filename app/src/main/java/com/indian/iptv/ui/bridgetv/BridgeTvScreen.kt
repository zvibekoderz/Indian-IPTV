package com.indian.iptv.ui.bridgetv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.foundation.lazy.list.rememberTvLazyListState
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.indian.iptv.IptvApp
import com.indian.iptv.data.addon.BridgeTvCache
import com.indian.iptv.data.addon.StremioAddon
import com.indian.iptv.data.addon.StreamProbe
import com.indian.iptv.data.addon.WorkingChannel
import com.indian.iptv.ui.theme.FocusStyle
import com.indian.iptv.ui.theme.IptvPalette
import com.indian.iptv.ui.theme.tvFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel as ProbeChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * TV-native browser for the live `tv` catalogues of a Stremio-compatible add-on
 * (e.g. CNCVerse Bridge running locally). Mirrors the phone's Bridge TV tab:
 * cross-catalog search, working-only probing with a persistent cache, load-more,
 * favourites keyed by meta.id, and D-pad focus styling that matches the rest of
 * the TV UI.
 *
 * This is an independent client of the public add-on REST contract; it does not
 * embed, copy or depend on any CNCVerse Bridge code.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BridgeTvScreen(
    onBack: () -> Unit,
    onPlay: (url: String, title: String, headers: Map<String, String>) -> Unit,
) {
    val app = IptvApp.get()
    val scope = rememberCoroutineScope()
    val savedUrl by app.settings.addonUrl.collectAsState(initial = "")
    val savedFavourites by app.settings.bridgeFavourites.collectAsState(initial = emptySet())

    var input by remember { mutableStateOf(savedUrl) }
    var activeUrl by remember { mutableStateOf(savedUrl) }
    var addon by remember { mutableStateOf<StremioAddon?>(null) }
    var manifest by remember { mutableStateOf<StremioAddon.Manifest?>(null) }
    var addonError by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }

    var query by rememberSaveable { mutableStateOf("") }
    var selectedCatalogId by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WorkingChannel>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var requested by remember { mutableIntStateOf(INITIAL_CANDIDATES) }
    var hasMore by remember { mutableStateOf(false) }
    var bridgeFavourites by remember { mutableStateOf(savedFavourites) }
    var showFavouritesOnly by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(savedFavourites) { bridgeFavourites = savedFavourites }

    fun toggleFavourite(item: WorkingChannel) {
        val key = item.meta.id
        val next = if (key in bridgeFavourites) bridgeFavourites - key else bridgeFavourites + key
        bridgeFavourites = next
        scope.launch { app.settings.setBridgeFavourites(next) }
    }

    val tvCatalogs = manifest?.catalogs?.filter { it.type.equals("tv", ignoreCase = true) }.orEmpty()
    val catalogsKey = tvCatalogs.joinToString { "${it.type}/${it.id}" }
    val cacheKey = BridgeTvCache.key(activeUrl, selectedCatalogId, query) + "|$requested"

    // Saved scroll offset lives outside composition so it survives leaving and
    // re-entering this screen (e.g. after playback in PlayerActivity).
    val listState = rememberTvLazyListState()
    LaunchedEffect(listState.firstVisibleItemIndex) {
        rememberedScrollIndex = listState.firstVisibleItemIndex
    }
    LaunchedEffect(results) {
        if (results.isNotEmpty() && rememberedScrollIndex > 0) {
            listState.scrollToItem(rememberedScrollIndex.coerceAtMost(results.lastIndex))
        }
    }

    LaunchedEffect(activeUrl) {
        val instance = StremioAddon.create(activeUrl)
        if (instance == null) {
            addon = null
            manifest = null
            addonError = "Enter a valid http(s) add-on URL"
            return@LaunchedEffect
        }
        connecting = true
        addonError = null
        runCatching { instance.manifest() }
            .onSuccess { loaded ->
                addon = instance
                manifest = loaded
                selectedCatalogId = ""
            }
            .onFailure {
                addon = null
                manifest = null
                addonError = it.message ?: "Couldn't reach the add-on"
            }
        connecting = false
    }

    LaunchedEffect(addon, catalogsKey, selectedCatalogId, query, refreshKey, requested) {
        val client = addon ?: run {
            results = emptyList()
            return@LaunchedEffect
        }
        val catalogs = tvCatalogs.filter { selectedCatalogId.isEmpty() || it.id == selectedCatalogId }
        if (catalogs.isEmpty()) {
            results = emptyList()
            return@LaunchedEffect
        }
        hasMore = false
        // "All TV" spreads a small slice per catalog round-robin; a single selected
        // catalog may need the whole pool. Fetching thousands × 21 catalogs would be
        // needlessly slow and memory-hungry.
        val poolLimit = if (selectedCatalogId.isEmpty()) ALL_TV_POOL_LIMIT else CATALOG_POOL_LIMIT
        // Serve a previous sweep instantly — no add-on request and no re-probing.
        BridgeTvCache.results(cacheKey)?.let { cached ->
            results = cached
            checking = false
            checkError = null
            return@LaunchedEffect
        }
        // Debounce typing so we don't hammer the add-on on every keystroke.
        delay(400)
        checking = true
        checkError = null
        val term = query.trim()

        // Fetch every tv catalog IN PARALLEL, cap each, then interleave round-robin
        // so one huge catalog can't fill the whole list before later ones are read.
        val perCatalog = coroutineScope {
            catalogs.map { catalog ->
                async(Dispatchers.IO) {
                    val metas = runCatching {
                        client.catalog(catalog.type, catalog.id, term.ifEmpty { null })
                    }.getOrDefault(emptyList())
                    catalog.name to metas.take(poolLimit)
                }
            }.awaitAll()
        }
        if (perCatalog.all { it.second.isEmpty() }) {
            checkError = "Couldn't load channels from any catalogue"
            results = emptyList()
            checking = false
            return@LaunchedEffect
        }

        val interleaved = mutableListOf<Pair<StremioAddon.Meta, String>>()
        val longest = perCatalog.maxOf { it.second.size }
        for (index in 0 until longest) {
            perCatalog.forEach { (name, metas) ->
                if (index < metas.size) interleaved += metas[index] to name
            }
        }
        val unique = interleaved
            .distinctBy { (meta, name) -> "$name|${meta.type}|${meta.id}" }
        hasMore = unique.size > requested
        val limited = unique.take(minOf(requested, MAX_CANDIDATES))
        if (limited.isEmpty()) {
            results = emptyList()
            checking = false
            return@LaunchedEffect
        }

        // Stream verified channels in as each probe completes, with a hard time
        // budget so a hanging host can't stall the search forever.
        results = emptyList()
        val permits = Semaphore(PROBE_CONCURRENCY)
        val verified = ProbeChannel<ProbeResult>(ProbeChannel.UNLIMITED)
        val found = mutableListOf<WorkingChannel>()
        val deadline = System.currentTimeMillis() + SEARCH_BUDGET_MS
        var timedOut = false
        coroutineScope {
            val jobs = limited.map { (meta, catalogName) ->
                launch(Dispatchers.IO) {
                    permits.withPermit {
                        val streams = runCatching { client.streams(meta.type, meta.id) }
                            .getOrDefault(emptyList())
                        val playable = streams.firstOrNull { stream ->
                            val cached = BridgeTvCache.probe(stream.url)
                            cached ?: StreamProbe.isPlayable(stream.url, stream.headers)
                                .also { BridgeTvCache.putProbe(stream.url, it) }
                        }
                        verified.send(ProbeResult(playable?.let { WorkingChannel(meta, it, catalogName) }))
                    }
                }
            }
            var received = 0
            while (received < limited.size) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    timedOut = true
                    break
                }
                val item = withTimeoutOrNull(remaining) { verified.receive() }
                if (item == null) {
                    timedOut = true
                    break
                }
                received++
                item.channel?.let { channel ->
                    found += channel
                    results = found.toList()
                }
            }
            jobs.forEach { it.cancel() }
        }
        // Only cache a complete sweep — a timed-out partial list should be retried.
        if (!timedOut) BridgeTvCache.putResults(cacheKey, found)
        results = found
        checking = false
    }

    val visibleResults = remember(results, bridgeFavourites, showFavouritesOnly) {
        val filtered = if (showFavouritesOnly) {
            results.filter { it.meta.id in bridgeFavourites }
        } else {
            results
        }
        filtered.sortedByDescending { it.meta.id in bridgeFavourites }
    }

    BackHandler(onBack = onBack)

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
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "BRIDGE TV",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Black,
                        color = IptvPalette.Accent,
                        letterSpacing = 2.sp,
                    ),
                )
                Spacer(Modifier.weight(1f))
                TvPillButton(label = "Refresh") {
                    BridgeTvCache.clearDeadProbes()
                    BridgeTvCache.clearResults(cacheKey)
                    refreshKey++
                }
                Spacer(Modifier.width(12.dp))
                TvPillButton(label = "Back", onClick = onBack)
            }
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                FocusableTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = "Add-on URL (manifest.json or host)",
                    modifier = Modifier.fillMaxWidth(0.62f),
                )
                Spacer(Modifier.width(16.dp))
                TvPillButton(label = "Connect") {
                    scope.launch {
                        app.settings.setAddonUrl(input)
                        activeUrl = input
                        requested = INITIAL_CANDIDATES
                    }
                }
            }
            Text(
                text = "CNCVerse Bridge shows this URL when its server is running. " +
                    "Use http://127.0.0.1:8080/manifest.json when Bridge runs on this TV.",
                style = MaterialTheme.typography.bodySmall.copy(color = IptvPalette.TextSecondary),
                modifier = Modifier.padding(top = 4.dp),
            )
            addonError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFFF6B6B)))
            }

            Spacer(Modifier.height(10.dp))
            FocusableTextField(
                value = query,
                onValueChange = {
                    query = it
                    requested = INITIAL_CANDIDATES
                },
                label = "Search channels",
                modifier = Modifier.fillMaxWidth(0.62f),
            )

            if (tvCatalogs.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                TvLazyRow(
                    contentPadding = PaddingValues(end = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        TvPillButton(
                            label = "★ Favourites",
                            selected = showFavouritesOnly,
                        ) {
                            showFavouritesOnly = !showFavouritesOnly
                        }
                    }
                    item {
                        TvPillButton(
                            label = "All TV",
                            selected = !showFavouritesOnly && selectedCatalogId.isEmpty(),
                        ) {
                            showFavouritesOnly = false
                            selectedCatalogId = ""
                            requested = INITIAL_CANDIDATES
                        }
                    }
                    items(tvCatalogs, key = { it.id }) { catalog ->
                        TvPillButton(
                            label = catalog.name,
                            selected = !showFavouritesOnly && selectedCatalogId == catalog.id,
                        ) {
                            selectedCatalogId = catalog.id
                            showFavouritesOnly = false
                            requested = INITIAL_CANDIDATES
                        }
                    }
                }
            }

            Row(
                Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (connecting || checking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (connecting) "Connecting…" else "Checking streams…",
                        style = MaterialTheme.typography.bodySmall.copy(color = IptvPalette.TextSecondary),
                    )
                }
                checkError?.let {
                    Spacer(Modifier.width(12.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFFF6B6B)))
                }
            }

            val firstItemFocus = remember { FocusRequester() }
            LaunchedEffect(visibleResults.size) {
                if (visibleResults.isNotEmpty()) runCatching { firstItemFocus.requestFocus() }
            }

            TvLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(visibleResults, key = { _, item -> item.meta.id + "|" + item.stream.url }) { index, item ->
                    BridgeChannelRow(
                        item = item,
                        isFavourite = item.meta.id in bridgeFavourites,
                        focusRequester = if (index == 0) firstItemFocus else null,
                        onPlay = {
                            val headers = item.stream.headers
                            val ua = headers.entries
                                .firstOrNull { it.key.equals("User-Agent", true) }?.value
                            val ref = headers.entries
                                .firstOrNull {
                                    it.key.equals("Referer", true) || it.key.equals("Referrer", true)
                                }?.value
                            onPlay(item.stream.url, item.meta.name, headers + listOfNotNull(
                                ua?.let { "User-Agent" to it },
                                ref?.let { "Referer" to it },
                            ))
                        },
                        onToggleFavourite = { toggleFavourite(item) },
                    )
                }

                if (hasMore && !checking && requested < MAX_CANDIDATES) {
                    item {
                        TvPillButton(label = "Load more channels") {
                            requested = minOf(requested + LOAD_MORE_STEP, MAX_CANDIDATES)
                        }
                    }
                }

                if (!checking && manifest != null && visibleResults.isEmpty()) {
                    item {
                        Text(
                            when {
                                showFavouritesOnly && bridgeFavourites.isEmpty() ->
                                    "No favourites yet — focus the star beside any channel to save it here."
                                showFavouritesOnly ->
                                    "None of your favourites are in the current results. They may be offline."
                                tvCatalogs.isEmpty() -> "This add-on publishes no live TV catalogues."
                                else -> "No working channels found. The add-on's links may be expired or offline."
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(color = IptvPalette.TextSecondary),
                            modifier = Modifier.padding(vertical = 20.dp),
                        )
                    }
                }

                if (manifest == null && addonError == null && !connecting) {
                    item {
                        Text(
                            "Enter the add-on URL above and choose Connect to browse live channels.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = IptvPalette.TextSecondary),
                            modifier = Modifier.padding(vertical = 20.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BridgeChannelRow(
    item: WorkingChannel,
    isFavourite: Boolean,
    focusRequester: FocusRequester?,
    onPlay: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        var focused by remember { mutableStateOf(false) }
        Surface(
            onClick = onPlay,
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = IptvPalette.SurfaceElevated.copy(alpha = 0.55f),
                focusedContainerColor = IptvPalette.SurfaceLift,
                contentColor = Color.White,
                focusedContentColor = Color.White,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier
                .weight(1f)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .tvFocus(focused, shape),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val poster = item.meta.poster
                if (poster != null) {
                    AsyncImage(
                        model = poster,
                        contentDescription = item.meta.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 92.dp, height = 56.dp)
                            .background(IptvPalette.BackgroundDeep, shape = RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        item.meta.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.catalogName,
                        style = MaterialTheme.typography.bodySmall.copy(color = IptvPalette.TextSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        var starFocused by remember { mutableStateOf(false) }
        Surface(
            onClick = onToggleFavourite,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = IptvPalette.SurfaceElevated.copy(alpha = 0.55f),
                focusedContainerColor = IptvPalette.SurfaceLift,
                contentColor = if (isFavourite) Color(0xFFFFC107) else IptvPalette.TextSecondary,
                focusedContentColor = Color.White,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier
                .onFocusChanged { starFocused = it.isFocused }
                .tvFocus(starFocused, RoundedCornerShape(999.dp)),
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = if (isFavourite) "Remove from favourites" else "Add to favourites",
                tint = if (isFavourite) Color(0xFFFFC107) else Color(0xFF9A9AA4),
                modifier = Modifier.padding(12.dp).size(22.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvPillButton(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(999.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) IptvPalette.Accent else IptvPalette.SurfaceElevated.copy(alpha = 0.7f),
            contentColor = Color.White,
            focusedContainerColor = if (selected) IptvPalette.Accent else FocusStyle.Fill,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier.onFocusChanged { focused = it.isFocused }.tvFocus(focused, shape, FocusStyle.ChipScale),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FocusableTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(focused, shape),
    )
}

/** Non-null envelope so a null probe result isn't confused with a timeout. */
private data class ProbeResult(val channel: WorkingChannel?)

private var rememberedScrollIndex: Int = 0

private const val CATALOG_POOL_LIMIT = 2000
private const val ALL_TV_POOL_LIMIT = 100
private const val INITIAL_CANDIDATES = 120
private const val LOAD_MORE_STEP = 120
private const val MAX_CANDIDATES = 600
private const val PROBE_CONCURRENCY = 14
private const val SEARCH_BUDGET_MS = 90_000L
