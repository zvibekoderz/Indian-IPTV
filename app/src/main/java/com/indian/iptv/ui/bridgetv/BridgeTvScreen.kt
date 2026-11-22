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
import android.util.Log
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * TV-native browser for the live `tv` catalogues of a Stremio-compatible add-on
 * (e.g. CNCVerse Bridge running locally). Mirrors the phone's Bridge TV tab:
 * cross-catalog search, full uncapped channel lists, background probing with a
 * persistent working-channel cache, favourites keyed by meta.id, and D-pad focus
 * styling that matches the rest of
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
    // All channels from the selected catalogue(s). Cached working rows appear first;
    // the remaining catalogue rows are appended as each small batch finishes.
    var entries by remember { mutableStateOf<List<ChannelEntry>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var cataloguesReady by remember { mutableStateOf(false) }
    var loadedCatalogCount by remember { mutableIntStateOf(0) }
    var totalCatalogCount by remember { mutableIntStateOf(0) }
    var probing by remember { mutableStateOf(false) }
    var checkedCount by remember { mutableIntStateOf(0) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var resolvingId by remember { mutableStateOf<String?>(null) }
    var bridgeFavourites by remember { mutableStateOf(savedFavourites) }
    var showFavouritesOnly by rememberSaveable { mutableStateOf(false) }
    // Ids removed because their stream died; kept so a late DataStore emission
    // cannot resurrect them before the persisted set catches up.
    var removedFavouriteIds by remember { mutableStateOf(emptySet<String>()) }
    // A dead row must not reappear when a later catalogue batch is merged in.
    var deadEntryIds by remember { mutableStateOf(emptySet<String>()) }
    // Guards read-modify-write of `entries` from the parallel probe coroutines.
    val entriesLock = remember { Any() }

    fun saveFavourites(next: Set<String>) {
        if (next == bridgeFavourites) return
        bridgeFavourites = next
        scope.launch { app.settings.setBridgeFavourites(next) }
    }

    // Only stream-checked Udaya/udya/Kannada rows become favourites. DataStore can
    // emit late, so merge on every change and never restore a confirmed-dead id.
    val autoFavouriteIds = remember(entries, removedFavouriteIds) {
        entries.filter {
            it.state == ProbeState.WORKING && it.stream != null &&
                it.isAutoFavourite() && it.meta.id !in removedFavouriteIds
        }.map { it.meta.id }.toSet()
    }
    LaunchedEffect(savedFavourites, autoFavouriteIds) {
        saveFavourites((savedFavourites - removedFavouriteIds) + autoFavouriteIds)
    }

    // DataStore may emit after the first composition; don't freeze the initial
    // empty value forever, or a return visit asks for the URL again.
    LaunchedEffect(savedUrl) {
        if (savedUrl.isNotEmpty() && activeUrl.isEmpty() && input.isEmpty()) {
            input = savedUrl
            activeUrl = savedUrl
        }
    }

    fun toggleFavourite(item: ChannelEntry) {
        val key = item.meta.id
        val next = if (key in bridgeFavourites) bridgeFavourites - key else bridgeFavourites + key
        bridgeFavourites = next
        scope.launch { app.settings.setBridgeFavourites(next) }
    }

    val tvCatalogs = manifest?.catalogs?.filter { it.type.equals("tv", ignoreCase = true) }.orEmpty()
    val catalogsKey = tvCatalogs.joinToString { "${it.type}/${it.id}" }
    val cacheKey = BridgeTvCache.key(activeUrl, selectedCatalogId, query)
    // Stable identity for the current channel pool. Unlike `entries` itself, this does
    // not change while background probes update individual rows, so the sweep isn't
    // cancelled and restarted on every result.
    val poolKey = "$activeUrl|$catalogsKey|$selectedCatalogId|${query.trim().lowercase()}|$refreshKey"

    // Udaya/Kannada rows are found by the full catalogue loader below and jump the
    // probe queue (see `sortedByDescending { it.isAutoFavourite() }`), so they are
    // stream-checked and starred without a separate search sweep. A per-catalogue
    // `?search=` sweep of all 139 catalogues is deliberately avoided: Bridge's
    // single-threaded server degrades badly and starts returning empty catalogues
    // after a few hundred search requests.

    // Saved scroll offset lives outside composition so it survives leaving and
    // re-entering this screen (e.g. after playback in PlayerActivity).
    val listState = rememberTvLazyListState()
    LaunchedEffect(listState.firstVisibleItemIndex) {
        rememberedScrollIndex = listState.firstVisibleItemIndex
    }
    LaunchedEffect(entries) {
        if (entries.isNotEmpty() && rememberedScrollIndex > 0) {
            listState.scrollToItem(rememberedScrollIndex.coerceAtMost(entries.lastIndex))
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

        val cachedChannels = BridgeTvCache.results(cacheKey).orEmpty()
        val cachedEntries = cachedChannels.map {
            ChannelEntry(it.meta, it.catalogName, ProbeState.WORKING, it.stream)
        }
        synchronized(entriesLock) { entries = cachedEntries }
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

        // Debounce typing so we don't hammer the add-on on every keystroke.
        delay(300)
        val term = query.trim()
        val cachedById = cachedChannels.associateBy { "${it.catalogName}|${it.meta.type}|${it.meta.id}" }
        // Udaya/Kannada catalogues go first so those channels are found in the
        // first visible batch instead of after a long 20k-channel sweep.
        val ordered = catalogs.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<StremioAddon.Catalog>> { (_, catalog) ->
                    AUTO_FAVOURITE_TERMS.any { it in catalog.name.lowercase() }
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
                entries = mergeEntries(entries, incoming)
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
    LaunchedEffect(addon, poolKey, cataloguesReady) {
        if (!cataloguesReady) return@LaunchedEffect
        val client = addon ?: return@LaunchedEffect
        while (isActive) {
            val pending = synchronized(entriesLock) {
                entries.filter { it.state == ProbeState.PENDING && it.id !in deadEntryIds }
                    .sortedByDescending { it.isAutoFavourite() }
                    .take(PROBE_BATCH_PER_SWEEP)
            }
            if (pending.isEmpty()) {
                probing = false
                delay(PROBE_IDLE_MS)
                continue
            }
            probing = true
            val working = BridgeTvCache.results(cacheKey).orEmpty().toMutableList()
            val permits = Semaphore(PROBE_CONCURRENCY)
            var persistedSince = 0
            coroutineScope {
                pending.map { entry ->
                    launch(Dispatchers.IO) {
                        permits.withPermit {
                            if (!isActive) return@withPermit
                            val streams = runCatching { client.streams(entry.meta.type, entry.meta.id) }
                                .getOrDefault(emptyList())
                            val playable = streams.firstOrNull { stream ->
                                BridgeTvCache.probe(stream.url)
                                    ?: StreamProbe.isPlayable(stream.url, stream.headers)
                                        .also { BridgeTvCache.putProbe(stream.url, it) }
                            }
                            withContext(Dispatchers.Main.immediate) {
                                synchronized(entriesLock) {
                                    // Dead rows are removed and remembered so a later
                                    // catalogue batch cannot resurrect them.
                                    if (playable == null) deadEntryIds += entry.id
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
            BridgeTvCache.putResults(cacheKey, working.distinctBy { it.id })
            delay(PROBE_SWEEP_PAUSE_MS)
        }
    }

    val visibleResults = remember(entries, bridgeFavourites, showFavouritesOnly) {
        val filtered = if (showFavouritesOnly) {
            entries.filter { it.meta.id in bridgeFavourites }
        } else {
            entries
        }
        filtered.sortedByDescending { it.meta.id in bridgeFavourites }
    }

    /** Hand a resolved stream to the player, keeping the add-on's playback headers. */
    fun sendToPlayer(entry: ChannelEntry, stream: StremioAddon.Stream) {
        val ua = stream.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val ref = stream.headers.entries.firstOrNull {
            it.key.equals("Referer", true) || it.key.equals("Referrer", true)
        }?.value
        onPlay(
            stream.url,
            entry.meta.name,
            stream.headers + listOfNotNull(
                ua?.let { "User-Agent" to it },
                ref?.let { "Referer" to it },
            ),
        )
    }

    /** Play now: use the cached stream if known, otherwise resolve it on demand. */
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
                        // Prefer a link we already know works; otherwise take the first
                        // direct http(s) stream so tapping a channel is never held up
                        // by a full reachability probe.
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
                    removedFavouriteIds = emptySet()
                    deadEntryIds = emptySet()
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
                onValueChange = { query = it },
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
                        }
                    }
                    items(tvCatalogs, key = { it.id }) { catalog ->
                        TvPillButton(
                            label = catalog.name,
                            selected = !showFavouritesOnly && selectedCatalogId == catalog.id,
                        ) {
                            selectedCatalogId = catalog.id
                            showFavouritesOnly = false
                        }
                    }
                }
            }

            Row(
                Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (connecting || fetching || loadingMore || probing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            connecting -> "Connecting…"
                            fetching -> "Loading catalogues $loadedCatalogCount/$totalCatalogCount…"
                            loadingMore -> "Loading more catalogues $loadedCatalogCount/$totalCatalogCount…"
                            else -> "Checking streams $checkedCount/${entries.size}…"
                        },
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
                itemsIndexed(visibleResults, key = { _, item -> item.id }) { index, item ->
                    BridgeChannelRow(
                        item = item,
                        isFavourite = item.meta.id in bridgeFavourites,
                        resolving = resolvingId == item.id,
                        focusRequester = if (index == 0) firstItemFocus else null,
                        onPlay = { play(item) },
                        onToggleFavourite = { toggleFavourite(item) },
                    )
                }

                if (entries.isNotEmpty() && !fetching) {
                    item {
                        val workingCount = entries.count { it.state == ProbeState.WORKING }
                        Text(
                            text = buildString {
                                append("${entries.size} channels · $workingCount ready")
                                if (probing) append(" · checking the rest in the background")
                                else if (checkedCount < entries.size) {
                                    append(" · tap any channel to play it")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall.copy(color = IptvPalette.TextTertiary),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                        )
                    }
                }

                if (cataloguesReady && !loadingMore && manifest != null && visibleResults.isEmpty()) {
                    item {
                        Text(
                            when {
                                showFavouritesOnly && bridgeFavourites.isEmpty() ->
                                    "No favourites yet — focus the star beside any channel to save it here."
                                showFavouritesOnly ->
                                    "None of your favourites are in the current results. They may be offline."
                                tvCatalogs.isEmpty() -> "This add-on publishes no live TV catalogues."
                                else -> "No channels found in this catalogue. Try All TV or refresh."
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
    item: ChannelEntry,
    isFavourite: Boolean,
    resolving: Boolean,
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
                    when {
                        resolving -> Text(
                            "Resolving stream…",
                            style = MaterialTheme.typography.labelSmall.copy(color = IptvPalette.AccentSoft),
                        )
                        item.state == ProbeState.PENDING -> Text(
                            "Checking stream…",
                            style = MaterialTheme.typography.labelSmall.copy(color = IptvPalette.TextTertiary),
                        )
                    }
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

/** One catalogue row. A stream is attached once it has been checked or resolved on tap. */
private data class ChannelEntry(
    val meta: StremioAddon.Meta,
    val catalogName: String,
    val state: ProbeState,
    val stream: StremioAddon.Stream? = null,
) {
    val id: String get() = "$catalogName|${meta.type}|${meta.id}"
}

private enum class ProbeState { PENDING, WORKING }

// Channel names/catalogue names that are always auto-favourited. Both the
// common spelling and the "udya" spelling are covered.
private val AUTO_FAVOURITE_TERMS = listOf("udya", "udaya", "kannada")

private var rememberedScrollIndex: Int = 0

private fun ChannelEntry.isAutoFavourite(): Boolean {
    val text = "${meta.name} $catalogName".lowercase()
    return AUTO_FAVOURITE_TERMS.any { it in text }
}

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
private const val PROBE_CONCURRENCY = 8
private const val PROBE_IDLE_MS = 600L
private const val PROBE_SWEEP_PAUSE_MS = 200L
private const val CATALOG_BATCH_SIZE = 2
private const val CATALOG_BATCH_PAUSE_MS = 400L
private const val CATALOG_FETCH_CONCURRENCY = 2
private const val MANIFEST_RETRY_MS = 5_000L
