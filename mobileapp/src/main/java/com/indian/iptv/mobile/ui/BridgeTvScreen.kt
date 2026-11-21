package com.indian.iptv.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.indian.iptv.mobile.data.BridgeTvCache
import com.indian.iptv.mobile.data.Channel
import com.indian.iptv.mobile.data.Settings
import com.indian.iptv.mobile.data.StremioAddon
import com.indian.iptv.mobile.data.StreamProbe
import com.indian.iptv.mobile.data.WorkingChannel
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
 * Dedicated tab for the live `tv` catalogues of a Stremio-compatible add-on
 * (e.g. CNCVerse Bridge). Unlike the movie catalogue browser, this screen only
 * lists channels whose stream URL answers a reachability probe — add-ons often
 * publish signed CDN links that have already expired.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeTvScreen(
    settings: Settings,
    onOpenCatalogs: () -> Unit,
    onPlay: (Channel) -> Unit,
) {
    var input by remember { mutableStateOf(settings.addonUrl) }
    var activeUrl by remember { mutableStateOf(settings.addonUrl) }
    var addon by remember { mutableStateOf<StremioAddon?>(null) }
    var manifest by remember { mutableStateOf<StremioAddon.Manifest?>(null) }
    var addonError by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }

    var query by rememberSaveable { mutableStateOf("") }
    var selectedCatalogId by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WorkingChannel>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    // How many catalogue entries to probe. Grows via "Load more" because some
    // catalogues hold well over a thousand channels — probing them all at once
    // would be slow and pointless when the user only ever looks at a screenful.
    var requested by remember { mutableStateOf(INITIAL_CANDIDATES) }
    var hasMore by remember { mutableStateOf(false) }
    var bridgeFavourites by remember { mutableStateOf(settings.bridgeFavourites) }
    var showFavouritesOnly by rememberSaveable { mutableStateOf(false) }

    fun toggleFavourite(item: WorkingChannel) {
        val key = item.meta.id
        val next = if (key in bridgeFavourites) bridgeFavourites - key else bridgeFavourites + key
        bridgeFavourites = next
        settings.bridgeFavourites = next
    }

    val tvCatalogs = manifest?.catalogs?.filter { it.type.equals("tv", ignoreCase = true) }.orEmpty()
    val catalogsKey = tvCatalogs.joinToString { "${it.type}/${it.id}" }
    val cacheKey = BridgeTvCache.key(activeUrl, selectedCatalogId, query) + "|$requested"

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
            .onFailure { addon = null; manifest = null; addonError = it.message ?: "Couldn't reach the add-on" }
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
        // "All TV" only needs a small slice per catalogue (round-robin spreads
        // the probe budget across all of them); a single selected catalogue may
        // need the whole thing. Fetching thousands of metas × 21 catalogs would
        // be needlessly slow and memory-hungry.
        val poolLimit = if (selectedCatalogId.isEmpty()) ALL_TV_POOL_LIMIT else CATALOG_POOL_LIMIT
        // A previous search for this add-on/catalog/term is served instantly: no add-on
        // request and no re-probing. This survives tab switches and app restarts.
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

        // Fetch EVERY tv catalogue IN PARALLEL, capping each one, then
        // interleave round-robin. Concatenating sequentially made "All TV" as
        // slow as the sum of all catalogue latencies, and a single large
        // catalogue could fill the whole list before later ones were read.
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

        // Stream verified channels into the list as each probe completes, so
        // the UI fills up progressively instead of sitting on a spinner until
        // the last URL is checked. A hard budget guarantees the search settles
        // even when a host hangs: whatever was verified by then is shown/cached.
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
        // Only cache a complete sweep — a timed-out partial list should be
        // retried on the next visit rather than frozen for the whole TTL.
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
        // Keep stars at the top so favourites are always one tap away.
        filtered.sortedByDescending { it.meta.id in bridgeFavourites }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bridge TV") },
                actions = {
                    IconButton(
                        onClick = {
                            BridgeTvCache.clearDeadProbes()
                            BridgeTvCache.clearResults(cacheKey)
                            refreshKey++
                        },
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh channels")
                    }
                    IconButton(onClick = onOpenCatalogs) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Movie catalogues")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text("Add-on URL (manifest.json or host)") },
                placeholder = { Text("https://bridge.yourdomain.com/manifest.json") },
            )
            Button(
                onClick = {
                    settings.addonUrl = input
                    activeUrl = input
                    requested = INITIAL_CANDIDATES
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("Connect") }

            addonError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (connecting) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    requested = INITIAL_CANDIDATES
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                label = { Text("Search channels") },
                placeholder = { Text("Type a channel name") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            )

            if (tvCatalogs.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = showFavouritesOnly,
                            onClick = { showFavouritesOnly = !showFavouritesOnly },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            label = { Text("Favourites") },
                        )
                    }
                    item {
                        FilterChip(
                            selected = !showFavouritesOnly && selectedCatalogId.isEmpty(),
                            onClick = {
                                showFavouritesOnly = false
                                selectedCatalogId = ""
                                requested = INITIAL_CANDIDATES
                            },
                            label = { Text("All TV") },
                        )
                    }
                    items(tvCatalogs, key = { it.id }) { catalog ->
                        FilterChip(
                            selected = selectedCatalogId == catalog.id,
                            onClick = {
                                selectedCatalogId = catalog.id
                                showFavouritesOnly = false
                                requested = INITIAL_CANDIDATES
                            },
                            label = { Text(catalog.name) },
                        )
                    }
                }
            }

            if (checking) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Checking streams…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            checkError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(visibleResults, key = { it.meta.id + "|" + it.stream.url }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val headers = item.stream.headers
                                val ua = headers.entries
                                    .firstOrNull { it.key.equals("User-Agent", true) }?.value
                                val ref = headers.entries
                                    .firstOrNull {
                                        it.key.equals("Referer", true) || it.key.equals("Referrer", true)
                                    }?.value
                                onPlay(
                                    Channel(
                                        name = item.meta.name,
                                        url = item.stream.url,
                                        logo = item.meta.poster,
                                        category = item.catalogName,
                                        tvgId = null,
                                        userAgent = ua,
                                        referrer = ref,
                                    ),
                                )
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val poster = item.meta.poster
                        if (poster != null) {
                            AsyncImage(
                                model = poster,
                                contentDescription = item.meta.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 64.dp, height = 44.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.meta.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                item.catalogName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { toggleFavourite(item) }) {
                            val isFavourite = item.meta.id in bridgeFavourites
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = if (isFavourite) {
                                    "Remove from favourites"
                                } else {
                                    "Add to favourites"
                                },
                                tint = if (isFavourite) Color(0xFFFFC107) else Color(0xFF6F6F78),
                            )
                        }
                    }
                }

                if (hasMore && !checking && requested < MAX_CANDIDATES) {
                    item {
                        Button(
                            onClick = {
                                requested = minOf(requested + LOAD_MORE_STEP, MAX_CANDIDATES)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text("Load more channels")
                        }
                    }
                }

                if (!checking && manifest != null && visibleResults.isEmpty()) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (showFavouritesOnly && bridgeFavourites.isEmpty()) {
                                    "No favourites yet — tap the star beside any channel to save it here."
                                } else if (showFavouritesOnly) {
                                    "None of your favourites are in the current results. They may be offline — switch off the Favourites filter to find others."
                                } else if (tvCatalogs.isEmpty()) {
                                    "This add-on publishes no live TV catalogues."
                                } else {
                                    "No working channels found. The add-on's links may be expired or offline — try another add-on or search again later."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (manifest == null && addonError == null && !connecting) {
                    item {
                        Text(
                            "Enter the add-on URL above and tap Connect to browse live channels.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Non-null envelope so a null probe result isn't confused with a timeout. */
private data class ProbeResult(val channel: WorkingChannel?)

private const val CATALOG_POOL_LIMIT = 2000
private const val ALL_TV_POOL_LIMIT = 100
private const val INITIAL_CANDIDATES = 120
private const val LOAD_MORE_STEP = 120
private const val MAX_CANDIDATES = 600
private const val PROBE_CONCURRENCY = 14
private const val SEARCH_BUDGET_MS = 90_000L
