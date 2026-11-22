package com.indian.iptv.mobile.ui

import android.util.Log
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

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

    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCatalogId by rememberSaveable { mutableStateOf("") }
    // Cached working rows appear first; the rest are appended in small catalogue
    // batches so a busy Bridge cannot hide the whole list.
    var entries by remember { mutableStateOf<List<ChannelEntry>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var cataloguesReady by remember { mutableStateOf(false) }
    var loadedCatalogCount by remember { mutableStateOf(0) }
    var totalCatalogCount by remember { mutableStateOf(0) }
    var probing by remember { mutableStateOf(false) }
    var checkedCount by remember { mutableStateOf(0) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var resolvingId by remember { mutableStateOf<String?>(null) }
    var bridgeFavourites by remember { mutableStateOf(settings.bridgeFavourites) }
    // Stars the user deliberately removed; auto-favouriting must not resurrect them.
    var dismissedAutoFavouriteIds by remember { mutableStateOf(settings.bridgeFavouriteDismissals) }
    var showFavouritesOnly by rememberSaveable { mutableStateOf(false) }
    // Ids removed because their stream died; kept so a stale persisted set cannot
    // resurrect them before the removal is written back.
    var removedFavouriteIds by remember { mutableStateOf(emptySet<String>()) }
    // A dead row must not reappear when a later catalogue batch is merged in.
    var deadEntryIds by remember { mutableStateOf(emptySet<String>()) }
    val entriesLock = remember { Any() }

    fun saveFavourites(next: Set<String>) {
        if (next == bridgeFavourites) return
        bridgeFavourites = next
        settings.bridgeFavourites = next
    }

    fun saveDismissals(next: Set<String>) {
        if (next == dismissedAutoFavouriteIds) return
        dismissedAutoFavouriteIds = next
        settings.bridgeFavouriteDismissals = next
    }

    // Only stream-checked Udaya ("udya")/Kannada rows are starred. Merge with saved
    // favourites rather than replacing them, and never restore a confirmed-dead id.
    val autoFavouriteIds = remember(entries, removedFavouriteIds, dismissedAutoFavouriteIds) {
        entries.filter {
            it.state == ProbeState.WORKING && it.stream != null &&
                it.isAutoFavourite() &&
                it.meta.id !in removedFavouriteIds &&
                it.meta.id !in dismissedAutoFavouriteIds
        }.map { it.meta.id }.toSet()
    }
    LaunchedEffect(
        settings.bridgeFavourites,
        autoFavouriteIds,
        removedFavouriteIds,
        dismissedAutoFavouriteIds,
    ) {
        saveFavourites(
            (settings.bridgeFavourites - removedFavouriteIds - dismissedAutoFavouriteIds) +
                autoFavouriteIds
        )
    }

    fun toggleFavourite(item: ChannelEntry) {
        val key = item.meta.id
        val removing = key in bridgeFavourites
        val next = if (removing) bridgeFavourites - key else bridgeFavourites + key
        bridgeFavourites = next
        settings.bridgeFavourites = next
        // Persist the choice so the next Udaya/Kannada update cannot restore the star.
        saveDismissals(
            if (removing) dismissedAutoFavouriteIds + key else dismissedAutoFavouriteIds - key
        )
    }

    val tvCatalogs = manifest?.catalogs?.filter { it.type.equals("tv", ignoreCase = true) }.orEmpty()
    val catalogsKey = tvCatalogs.joinToString { "${it.type}/${it.id}" }
    val cacheKey = BridgeTvCache.key(activeUrl, selectedCatalogId, query)
    val poolKey = "$activeUrl|$catalogsKey|$selectedCatalogId|${query.trim().lowercase()}|$refreshKey"

    // Udaya/Kannada rows are found by the full catalogue loader below and jump the
    // probe queue (see `sortedByDescending { it.isAutoFavourite() }`), so they are
    // stream-checked and starred without a separate search sweep. A per-catalogue
    // `?search=` sweep of all 139 catalogues is deliberately avoided: Bridge's
    // single-threaded server degrades badly and starts returning empty catalogues
    // after a few hundred search requests.

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

    // Bridge can be briefly unreachable or publish an empty manifest while its
    // plugins load. Retry quietly until TV catalogues appear instead of stranding
    // the screen with a timeout error. Handles both a failed connect (addon == null)
    // and a healthy server that has not finished loading its plugins yet.
    LaunchedEffect(addon, activeUrl, tvCatalogs.isEmpty(), addonError) {
        if (addon != null && tvCatalogs.isNotEmpty()) return@LaunchedEffect
        while (isActive) {
            delay(MANIFEST_RETRY_MS)
            val instance = StremioAddon.create(activeUrl) ?: continue
            val loaded = runCatching { instance.manifest() }.getOrNull() ?: continue
            val tvCount = loaded.catalogs.count { it.type.equals("tv", true) }
            if (tvCount > 0) {
                addon = instance
                manifest = loaded
                addonError = null
                connecting = false
                Log.i("BridgeTv", "Manifest recovered with $tvCount TV catalogues")
                break
            }
        }
    }

    // Phase 1: cached working rows are shown immediately, then catalogue rows are
    // appended in small batches. Bridge never has to answer 150+ full catalogue
    // requests at once, and a stall in one batch cannot hide the rows already loaded.
    LaunchedEffect(addon, catalogsKey, selectedCatalogId, query, refreshKey) {
        val client = addon ?: run {
            entries = emptyList()
            cataloguesReady = false
            fetching = false
            loadingMore = false
            return@LaunchedEffect
        }
        cataloguesReady = false
        loadingMore = false
        loadedCatalogCount = 0
        totalCatalogCount = 0
        checkError = null
        fetching = true

        val cachedChannels = withContext(Dispatchers.IO) {
            BridgeTvCache.results(cacheKey).orEmpty()
        }
        val cachedEntries = cachedChannels.map {
            ChannelEntry(it.meta, it.catalogName, ProbeState.WORKING, it.stream)
        }
        synchronized(entriesLock) {
            entries = sortForDisplay(cachedEntries, bridgeFavourites)
        }
        checkedCount = entries.count { it.state == ProbeState.WORKING }

        val catalogs = tvCatalogs.filter { selectedCatalogId.isEmpty() || it.id == selectedCatalogId }
        totalCatalogCount = catalogs.size
        if (catalogs.isEmpty()) {
            fetching = false
            cataloguesReady = true
            checkError = if (cachedEntries.isEmpty()) {
                null
            } else {
                "Showing cached channels — this add-on publishes no live TV catalogues."
            }
            return@LaunchedEffect
        }

        delay(300)
        val term = query.trim()
        val cachedById = cachedChannels.associateBy { "${it.catalogName}|${it.meta.type}|${it.meta.id}" }
        // Udaya/Kannada sources first, then JTV/Jio. Bridge's server starts
        // returning empty catalogues after many large payloads, and JTV sits at
        // the end of the manifest, so it must not be left until last.
        val ordered = catalogs.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<StremioAddon.Catalog>> { (_, catalog) ->
                    val name = catalog.name.lowercase()
                    when {
                        AUTO_FAVOURITE_TERMS.any { it in name } -> 2
                        PRIORITY_CATALOG_TERMS.any { it in name } -> 1
                        else -> 0
                    }
                }.thenBy { it.index },
            )
            .map { it.value }

        var anySuccess = false
        var initialRowsShown = synchronized(entriesLock) { entries.isNotEmpty() }
        if (initialRowsShown) {
            fetching = false
            cataloguesReady = true
        }
        ordered.chunked(CATALOG_BATCH_SIZE).forEachIndexed { batchIndex, batch ->
            if (!isActive) return@forEachIndexed
            if (batchIndex > 0) delay(CATALOG_BATCH_PAUSE_MS)
            val perCatalog = coroutineScope {
                val fetchPermits = Semaphore(CATALOG_FETCH_CONCURRENCY)
                batch.map { catalog ->
                    async(Dispatchers.IO) {
                        val metas = fetchPermits.withPermit {
                            runCatching {
                                client.catalog(catalog.type, catalog.id, term.ifEmpty { null })
                            }.getOrDefault(emptyList())
                        }
                        catalog.name to metas
                    }
                }.awaitAll()
            }
            val unique = interleaveCatalogRows(perCatalog.filter { it.second.isNotEmpty() })
                .distinctBy { (meta, name) -> "$name|${meta.type}|${meta.id}" }
            if (unique.isNotEmpty()) anySuccess = true
            val incoming = unique.mapNotNull { (meta, catalogName) ->
                val id = "$catalogName|${meta.type}|${meta.id}"
                if (id in deadEntryIds) return@mapNotNull null
                val cached = cachedById[id]
                ChannelEntry(
                    meta = meta,
                    catalogName = catalogName,
                    state = if (cached != null) ProbeState.WORKING else ProbeState.PENDING,
                    stream = cached?.stream,
                )
            }
            val visibleNow = synchronized(entriesLock) {
                entries = sortForDisplay(mergeEntries(entries, incoming), bridgeFavourites)
                checkedCount = entries.count { it.state == ProbeState.WORKING }
                entries.size
            }
            loadedCatalogCount += batch.size
            if (!initialRowsShown && visibleNow > 0) {
                initialRowsShown = true
                fetching = false
                cataloguesReady = true
            }
            loadingMore = loadedCatalogCount < ordered.size
            Log.i(
                "BridgeTv",
                "Batch ${batchIndex + 1}: ${incoming.size} rows (" +
                    "${incoming.count { it.isAutoFavourite() }} Udaya/Kannada), " +
                    "$visibleNow total, catalogue $loadedCatalogCount/${ordered.size}",
            )
        }
        fetching = false
        cataloguesReady = true
        loadingMore = false
        if (!anySuccess) {
            checkError = if (cachedChannels.isNotEmpty()) {
                "Showing cached channels — live catalogues are unreachable"
            } else {
                "Couldn't load channels from any catalogue"
            }
        }
    }

    // Phase 2: keep probing as batches arrive. Udaya/Kannada matches jump the queue
    // so they are stream-checked and starred without waiting for every other channel.
    LaunchedEffect(addon, poolKey, cataloguesReady, loadingMore) {
        if (!cataloguesReady) return@LaunchedEffect
        val client = addon ?: return@LaunchedEffect
        while (isActive) {
            val pending = synchronized(entriesLock) {
                // Only Udaya/Kannada rows are probed while catalogues are still
                // loading; full probing resumes once all catalogues are fetched.
                entries.filter {
                    it.state == ProbeState.PENDING &&
                        it.id !in deadEntryIds &&
                        (!loadingMore || it.isAutoFavourite())
                }
                    .sortedByDescending { it.isAutoFavourite() }
                    .take(PROBE_BATCH_PER_SWEEP)
            }
            if (pending.isEmpty()) {
                probing = false
                delay(PROBE_IDLE_MS)
                continue
            }
            probing = true
            // Parse the large cache off the main thread to keep scrolling smooth.
            val working = withContext(Dispatchers.IO) {
                BridgeTvCache.results(cacheKey).orEmpty()
            }.toMutableList()
            val permits = Semaphore(PROBE_CONCURRENCY)
            var persistedSince = 0
            coroutineScope {
                pending.map { entry ->
                    launch(Dispatchers.IO) {
                        permits.withPermit {
                            if (!isActive) return@withPermit
                            // A failed request only means Bridge is busy/unreachable —
                            // never treat that as a dead channel. Leave it PENDING so a
                            // later sweep retries it; only a successful response with
                            // no playable direct stream proves a channel is dead.
                            val streamsResult = runCatching { client.streams(entry.meta.type, entry.meta.id) }
                            if (streamsResult.isFailure) return@withPermit
                            val playable = streamsResult.getOrThrow().firstOrNull { stream ->
                                BridgeTvCache.probe(stream.url)
                                    ?: StreamProbe.isPlayable(stream.url, stream.headers)
                                        .also { BridgeTvCache.putProbe(stream.url, it) }
                            }
                            withContext(Dispatchers.Main.immediate) {
                                synchronized(entriesLock) {
                                    // Dead rows are removed and remembered so a later
                                    // catalogue batch cannot resurrect them.
                                    if (playable == null) {
                                        deadEntryIds += entry.id
                                        working.removeAll { it.id == entry.id }
                                    }
                                    entries = if (playable == null) {
                                        entries.filterNot { it.id == entry.id }
                                    } else {
                                        entries.map { current ->
                                            if (current.id == entry.id) {
                                                current.copy(state = ProbeState.WORKING, stream = playable)
                                            } else current
                                        }
                                    }
                                    checkedCount++
                                }
                                if (playable == null) {
                                    removedFavouriteIds += entry.meta.id
                                    saveFavourites(bridgeFavourites - entry.meta.id)
                                }
                            }
                            if (playable != null) {
                                synchronized(entriesLock) {
                                    working += WorkingChannel(entry.meta, playable, entry.catalogName)
                                    if (++persistedSince >= PROBE_PERSIST_EVERY) {
                                        BridgeTvCache.putResults(cacheKey, working.distinctBy { it.id })
                                        persistedSince = 0
                                    }
                                }
                            }
                        }
                    }
                }.joinAll()
            }
            val snapshot = synchronized(entriesLock) { working.distinctBy { it.id } }
            withContext(Dispatchers.IO) { BridgeTvCache.putResults(cacheKey, snapshot) }
            delay(PROBE_SWEEP_PAUSE_MS)
        }
    }

    // Re-sort when the favourite set changes, not after every stream probe.
    LaunchedEffect(bridgeFavourites) {
        // Debounce auto-favourite bursts; a single sort after the burst is enough.
        delay(250)
        synchronized(entriesLock) {
            entries = sortForDisplay(entries, bridgeFavourites)
        }
    }

    val visibleResults = remember(entries, bridgeFavourites, showFavouritesOnly) {
        if (showFavouritesOnly) {
            entries.filter { it.meta.id in bridgeFavourites }
        } else {
            entries
        }
    }

    fun sendToPlayer(entry: ChannelEntry, stream: StremioAddon.Stream) {
        val ua = stream.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val ref = stream.headers.entries.firstOrNull {
            it.key.equals("Referer", true) || it.key.equals("Referrer", true)
        }?.value
        onPlay(
            Channel(
                name = entry.meta.name,
                url = stream.url,
                logo = entry.meta.poster,
                category = entry.catalogName,
                tvgId = null,
                userAgent = ua,
                referrer = ref,
            ),
        )
    }

    fun play(entry: ChannelEntry) {
        val ready = entry.stream
        if (ready != null) {
            sendToPlayer(entry, ready)
            return
        }
        scope.launch {
            val client = addon ?: return@launch
            resolvingId = entry.id
            val resolved = withContext(Dispatchers.IO) {
                runCatching { client.streams(entry.meta.type, entry.meta.id) }
                    .getOrDefault(emptyList())
                    .let { streams ->
                        streams.firstOrNull { s ->
                            BridgeTvCache.probe(s.url) ?: StreamProbe.isPlayable(s.url, s.headers)
                                .also { BridgeTvCache.putProbe(s.url, it) }
                        } ?: streams.firstOrNull { it.url.startsWith("http", true) }
                    }
            }
            resolvingId = null
            if (resolved == null) {
                synchronized(entriesLock) {
                    deadEntryIds += entry.id
                    entries = entries.filterNot { it.id == entry.id }
                    removedFavouriteIds += entry.meta.id
                }
                saveFavourites(bridgeFavourites - entry.meta.id)
                checkError = "Removed ${entry.meta.name} — no playable stream found"
                return@launch
            }
            synchronized(entriesLock) {
                entries = entries.map {
                    if (it.id == entry.id) it.copy(state = ProbeState.WORKING, stream = resolved) else it
                }
            }
            BridgeTvCache.putProbe(resolved.url, true)
            sendToPlayer(entry, resolved)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bridge TV") },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                BridgeTvCache.clearDeadProbes()
                                BridgeTvCache.clearResults(cacheKey)
                            }
                            removedFavouriteIds = emptySet()
                            deadEntryIds = emptySet()
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
                onValueChange = { query = it },
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
                            },
                            label = { Text(catalog.name) },
                        )
                    }
                }
            }

            if (connecting || fetching || loadingMore || probing) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            connecting -> "Connecting…"
                            fetching -> "Loading catalogues $loadedCatalogCount/$totalCatalogCount…"
                            loadingMore -> "Loading more catalogues $loadedCatalogCount/$totalCatalogCount…"
                            else -> "Checking streams $checkedCount/${entries.size}…"
                        },
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
                items(visibleResults, key = { it.id }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { play(item) }
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
                            when {
                                resolvingId == item.id -> Text(
                                    "Resolving stream…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                item.state == ProbeState.PENDING -> Text(
                                    "Checking stream…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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

                if (entries.isNotEmpty() && !fetching) {
                    item {
                        Text(
                            text = buildString {
                                append("${entries.size} channels · ${entries.count { it.state == ProbeState.WORKING }} ready")
                                if (probing) append(" · checking the rest in the background")
                                else if (checkedCount < entries.size) append(" · tap any channel to play it")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }

                if (cataloguesReady && !loadingMore && manifest != null && visibleResults.isEmpty()) {
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
                                    "No channels found in this catalogue. Try All TV or refresh."
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

/** One catalogue row; a stream is attached once checked or resolved on tap. */
private data class ChannelEntry(
    val meta: StremioAddon.Meta,
    val catalogName: String,
    val state: ProbeState,
    val stream: StremioAddon.Stream? = null,
) {
    val id: String get() = "$catalogName|${meta.type}|${meta.id}"
}

private enum class ProbeState { PENDING, WORKING }

// Channel/catalogue names that are always auto-favourited ("udya" is a common
// spelling of Udaya in Bridge listings).
private val AUTO_FAVOURITE_TERMS = listOf("udya", "udaya", "kannada")

// JTV/Jio catalogues sit at the end of Bridge's manifest and get starved when
// the server is busy generating earlier catalogues.
private val PRIORITY_CATALOG_TERMS = listOf("jtv", "jio")

private fun ChannelEntry.isAutoFavourite(): Boolean {
    val text = "${meta.name} $catalogName".lowercase()
    return AUTO_FAVOURITE_TERMS.any { it in text }
}

private val PRIORITY_FAVOURITE_VIP_TERMS = listOf("jio vip", "jiotv vip", "vip (jiotv")
private val PRIORITY_FAVOURITE_UDAYA_TERMS = listOf("udya", "udaya")

/** JIOTV VIP first, then Udaya, then the remaining Kannada favourites. */
private fun ChannelEntry.favouriteRank(): Int {
    val text = "${meta.name} $catalogName".lowercase()
    return when {
        PRIORITY_FAVOURITE_VIP_TERMS.any { it in text } -> 0
        PRIORITY_FAVOURITE_UDAYA_TERMS.any { it in text } -> 1
        "kannada" in text -> 2
        else -> 3
    }
}

/** Keep the displayed order stable while background probes update stream state. */
private fun sortForDisplay(items: List<ChannelEntry>, favourites: Set<String>): List<ChannelEntry> =
    items.sortedWith(
        compareByDescending<ChannelEntry> { it.meta.id in favourites }
            .thenBy { if (it.meta.id in favourites) it.favouriteRank() else Int.MAX_VALUE }
    )

/** Round-robin within a batch so one huge catalogue can't bury the others. */
private fun interleaveCatalogRows(
    perCatalog: List<Pair<String, List<StremioAddon.Meta>>>,
): List<Pair<StremioAddon.Meta, String>> {
    val rows = mutableListOf<Pair<StremioAddon.Meta, String>>()
    val longest = perCatalog.maxOfOrNull { it.second.size } ?: 0
    for (index in 0 until longest) {
        perCatalog.forEach { (name, metas) ->
            if (index < metas.size) rows += metas[index] to name
        }
    }
    return rows
}

/** Keep already-verified rows when a later batch repeats the same channel. */
private fun mergeEntries(
    current: List<ChannelEntry>,
    incoming: List<ChannelEntry>,
): List<ChannelEntry> {
    val merged = linkedMapOf<String, ChannelEntry>()
    current.forEach { merged[it.id] = it }
    incoming.forEach { row ->
        val existing = merged[row.id]
        merged[row.id] = when {
            existing == null -> row
            existing.state == ProbeState.WORKING && existing.stream != null -> existing
            row.state == ProbeState.WORKING && row.stream != null -> row
            else -> existing
        }
    }
    return merged.values.toList()
}

private val WorkingChannel.id: String get() = "$catalogName|${meta.type}|${meta.id}"

// Every channel is listed; only the concurrent work is bounded. Bridge can publish
// 150+ catalogues, so load them in small batches with a short pause instead of one
// all-at-once burst that stalls the server. Verified streams are cached so the
// checked set grows across sessions.
private const val PROBE_BATCH_PER_SWEEP = 200
private const val PROBE_PERSIST_EVERY = 25
private const val PROBE_CONCURRENCY = 4
private const val PROBE_IDLE_MS = 600L
private const val PROBE_SWEEP_PAUSE_MS = 400L
// Fetch one catalogue at a time: concurrent multi-megabyte responses plus
// stream probes made Bridge return empty JTV catalogues.
private const val CATALOG_BATCH_SIZE = 1
private const val CATALOG_BATCH_PAUSE_MS = 1000L
private const val CATALOG_FETCH_CONCURRENCY = 1
private const val MANIFEST_RETRY_MS = 5_000L
