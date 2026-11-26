package com.indian.iptv.ui.addons

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.indian.iptv.IptvApp
import com.indian.iptv.R
import com.indian.iptv.data.Channel
import com.indian.iptv.data.ContentType
import com.indian.iptv.data.addon.AddonStreamCache
import com.indian.iptv.data.addon.AddonStreamResolver
import com.indian.iptv.data.addon.ResolvedStream
import com.indian.iptv.data.addon.StremioAddon
import com.indian.iptv.data.db.toDomain
import com.indian.iptv.ui.channels.PosterCard
import com.indian.iptv.ui.theme.IptvPalette
import com.indian.iptv.ui.theme.tvFocus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val SEARCH_DEBOUNCE_MS = 350L
private const val FINISH_THRESHOLD_MS = 30_000L
private const val MAX_SEARCH_RESULTS = 300
private const val MAX_CONTINUE_WATCHING = 60
private const val MAX_PARALLEL_SEARCHES = 6

private data class CatalogHit(
    val sourceIndex: Int,
    val catalog: StremioAddon.Catalog,
    val meta: StremioAddon.Meta,
)

private data class ProgressUi(
    val positionMs: Long,
    val fraction: Float,
)

/** Stremio types are free-form; fold everything the player can handle into our three tabs. */
private fun contentTypeFor(type: String): ContentType = when (type.trim().lowercase()) {
    "tv", "live" -> ContentType.TV
    "series", "show", "shows", "episode", "episodes" -> ContentType.SERIES
    else -> ContentType.MOVIE
}

/**
 * Full-grid add-on browser for movie / series / live catalogues exposed by the configured
 * Stremio-compatible add-ons (torrent-backed or otherwise). It reads catalogue metadata
 * directly from the manifest so non-TV types are no longer forced into "movies".
 *
 * OK plays the best working direct stream immediately — there is no blocking stream-picker.
 * Long-press (or the Menu key) opens stream options and the favourite toggle. The channel id
 * is passed to the player, so watch progress survives and can be resumed from the
 * "Continue" filter.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddonsScreen(
    onBack: () -> Unit,
    onPlay: (
        url: String,
        title: String,
        headers: Map<String, String>,
        channelId: String,
        resumeMs: Long,
    ) -> Unit,
) {
    val app = IptvApp.get()
    val dao = remember { app.database.channelDao() }
    val scope = rememberCoroutineScope()
    val profileId by app.activeProfileId.collectAsState()
    val urls by app.settings.addonUrls.collectAsState(initial = emptyList())
    val favouriteIds by dao.observeFavoriteIds(profileId).collectAsState(initial = emptyList())
    val progressRows by dao.observeAddonProgress(
        profileId,
        FINISH_THRESHOLD_MS,
        MAX_CONTINUE_WATCHING,
    ).collectAsState(initial = emptyList())
    val tvRows by dao.observeAddonChannels(ContentType.TV.name, 20_000).collectAsState(initial = emptyList())
    val movieRows by dao.observeAddonChannels(ContentType.MOVIE.name, 5_000).collectAsState(initial = emptyList())
    val seriesRows by dao.observeAddonChannels(ContentType.SERIES.name, 5_000).collectAsState(initial = emptyList())
    val deadUrls by dao.observeDeadAddonUrls().collectAsState(initial = emptyList())
    val roomChannels = remember(tvRows, movieRows, seriesRows) {
        (tvRows + movieRows + seriesRows).map { it.toDomain() }
    }

    var activeIndex by remember { mutableIntStateOf(0) }
    var manifests by remember { mutableStateOf<Map<Int, StremioAddon.Manifest>>(emptyMap()) }
    var loadedAddons by remember { mutableStateOf<Map<Int, StremioAddon>>(emptyMap()) }
    var selectedCatalog by remember { mutableStateOf<Pair<Int, StremioAddon.Catalog>?>(null) }
    var metas by remember { mutableStateOf<List<StremioAddon.Meta>>(emptyList()) }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var favouritesOnly by remember { mutableStateOf(false) }
    var continueOnly by remember { mutableStateOf(false) }
    var optionsFor by remember { mutableStateOf<Channel?>(null) }
    var optionsStreams by remember { mutableStateOf<List<ResolvedStream>>(emptyList()) }
    var searchHits by remember { mutableStateOf<List<CatalogHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    var continued by remember { mutableStateOf<List<Channel>>(emptyList()) }

    // Small metadata cache so the continue-watching filter can reconstruct titles that
    // never entered the main channels table (series catalogues, for example).
    val metaCache = remember { mutableMapOf<String, StremioAddon.Meta>() }

    fun channelFromMeta(
        index: Int,
        catalog: StremioAddon.Catalog?,
        meta: StremioAddon.Meta,
    ): Channel {
        val type = contentTypeFor(meta.type.ifEmpty { catalog?.type.orEmpty() })
        val key = AddonStreamCache.key(index, type.name, meta.id)
        val best = AddonStreamCache.best(key)
        return Channel(
            id = "addon:$index:${type.name}:${meta.id}",
            name = meta.name,
            logoUrl = meta.poster,
            groupTitle = catalog?.name ?: "Add-ons",
            streamUrl = best?.url,
            epgChannelId = null,
            type = type,
            userAgent = best?.userAgent,
            referrer = best?.referrer,
        )
    }

    // One manifest per configured source. Failed sources are skipped without affecting
    // any other source or any stored dead-link state.
    LaunchedEffect(urls) {
        val built = LinkedHashMap<Int, StremioAddon>()
        val found = LinkedHashMap<Int, StremioAddon.Manifest>()
        urls.forEachIndexed { index, raw ->
            val addon = StremioAddon.create(raw) ?: return@forEachIndexed
            runCatching { addon.manifest() }.onSuccess { manifest ->
                built[index] = addon
                found[index] = manifest
            }
        }
        loadedAddons = built
        manifests = found
        metaCache.clear()
        searchHits = emptyList()
        if (activeIndex !in built) activeIndex = built.keys.firstOrNull() ?: 0
        val first = found[activeIndex]?.catalogs?.firstOrNull()
        selectedCatalog = first?.let { activeIndex to it }
    }

    val catalogs: List<Pair<Int, StremioAddon.Catalog>> = manifests.entries
        .sortedBy { it.key }
        .flatMap { (index, manifest) -> manifest.catalogs.map { index to it } }

    LaunchedEffect(selectedCatalog) {
        val current = selectedCatalog
        val addon = current?.let { loadedAddons[it.first] }
        if (current == null || addon == null) {
            metas = emptyList()
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching { addon.catalog(current.second.type, current.second.id) }
            .onSuccess { metas = it }
            .onFailure { error = it.message ?: "Couldn't load catalogue" }
        loading = false
    }

    // Build lightweight cards from the last-known best stream cache. Resolution is done
    // on demand (OK or long-press) so browsing stays instant. Room rows remain the
    // fallback while the network catalogue is unavailable.
    LaunchedEffect(metas, selectedCatalog, roomChannels, deadUrls) {
        val index = selectedCatalog?.first ?: 0
        val catalog = selectedCatalog?.second
        val fromNetwork = metas.map { channelFromMeta(index, catalog, it) }
        channels = if (fromNetwork.isNotEmpty()) {
            fromNetwork
        } else {
            roomChannels.filter { catalog == null || it.groupTitle == catalog.name }
        }.filterNot { it.streamUrl in deadUrls }
    }

    // Real add-on-side search: Stremio puts the term in the catalog path
    // (`/catalog/{type}/{id}/search={term}.json`). A failed sweep falls back to filtering
    // the already-loaded catalogue locally, so search still works without a network.
    LaunchedEffect(query, manifests, loadedAddons) {
        val term = query.trim()
        if (term.isEmpty()) {
            searching = false
            searchFailed = false
            searchHits = emptyList()
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        searching = true
        searchFailed = false
        try {
            val permits = Semaphore(MAX_PARALLEL_SEARCHES)
            val hits = coroutineScope {
                catalogs.map { (index, catalog) ->
                    async {
                        permits.withPermit {
                            val addon = loadedAddons[index]
                                ?: return@withPermit emptyList<CatalogHit>()
                            runCatching { addon.catalog(catalog.type, catalog.id, term) }
                                .getOrDefault(emptyList<StremioAddon.Meta>())
                                .map { CatalogHit(index, catalog, it) }
                        }
                    }
                }.awaitAll().flatten()
            }
            searchHits = hits
                .distinctBy { hit ->
                    val type = contentTypeFor(hit.meta.type.ifEmpty { hit.catalog.type })
                    "${hit.sourceIndex}:${type.name}:${hit.meta.id}"
                }
                .take(MAX_SEARCH_RESULTS)
            searchFailed = hits.isEmpty() && channels.isNotEmpty()
        } finally {
            searching = false
        }
    }

    // Continue watching is driven by watch_progress rows whose ids are stable add-on ids.
    // Movies/live rows already in Room can be reused directly; other types are rebuilt
    // from the add-on's /meta endpoint.
    LaunchedEffect(progressRows, loadedAddons, roomChannels) {
        val rows = progressRows
        val stored = dao.getChannelsByIds(rows.map { it.channelId }).associateBy { it.id }
        val rebuilt = ArrayList<Channel>()
        rows.forEach { row ->
            val existing = stored[row.channelId]
            if (existing != null) {
                rebuilt += existing.toDomain()
                return@forEach
            }
            val ref = AddonStreamResolver.parseChannelId(row.channelId) ?: return@forEach
            val addon = loadedAddons[ref.sourceIndex] ?: return@forEach
            val meta = metaCache[row.channelId]
                ?: runCatching { addon.meta(ref.type, ref.metaId) }.getOrNull()
                ?: return@forEach
            metaCache[row.channelId] = meta
            rebuilt += channelFromMeta(ref.sourceIndex, null, meta).copy(
                id = row.channelId,
                groupTitle = "Continue watching",
            )
        }
        continued = rebuilt
    }

    val progressById = remember(progressRows) {
        progressRows.associate { row ->
            row.channelId to ProgressUi(
                positionMs = row.positionMs,
                fraction = (row.positionMs.toFloat() / row.durationMs.coerceAtLeast(1L))
                    .coerceIn(0f, 1f),
            )
        }
    }
    val searchChannels = remember(searchHits, deadUrls) {
        searchHits.map { channelFromMeta(it.sourceIndex, it.catalog, it.meta) }
            .filterNot { it.streamUrl in deadUrls }
    }

    val term = query.trim()
    val catalogMatched = term.isNotEmpty() && !searchFailed && searchChannels.isNotEmpty()
    val source = when {
        catalogMatched -> searchChannels
        else -> channels
    }
    val visible = when {
        continueOnly -> continued
        favouritesOnly -> source.filter { it.id in favouriteIds }
        else -> source
    }
        .filterNot { it.streamUrl in deadUrls }
        .filter {
            catalogMatched || term.isEmpty() ||
                it.name.contains(term, ignoreCase = true) ||
                it.groupTitle?.contains(term, ignoreCase = true) == true
        }

    fun resumeMsFor(channel: Channel): Long = progressById[channel.id]?.positionMs ?: 0L

    fun streamHeaders(best: ResolvedStream): Map<String, String> = buildMap {
        best.headers.forEach { (name, value) -> put(name, value) }
        if (keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            best.userAgent?.let { put("User-Agent", it) }
        }
        if (keys.none { it.equals("Referer", ignoreCase = true) }) {
            best.referrer?.let { put("Referer", it) }
        }
    }

    /** OK: resolve if needed, then play the best working link directly. */
    fun play(channel: Channel) {
        val key = AddonStreamCache.keyForChannelId(channel.id) ?: return
        val resumeMs = resumeMsFor(channel)
        val cached = AddonStreamCache.best(key)
        if (cached != null) {
            onPlay(cached.url, channel.name, streamHeaders(cached), channel.id, resumeMs)
            return
        }
        scope.launch {
            loading = true
            val addon = loadedAddons[selectedCatalog?.first] ?: loadedAddons.values.firstOrNull()
            if (addon == null) {
                loading = false
                return@launch
            }
            val ref = AddonStreamResolver.parseChannelId(channel.id)
            if (ref == null) {
                loading = false
                return@launch
            }
            val meta = StremioAddon.Meta(
                id = ref.metaId, type = ref.type.lowercase(), name = channel.name,
                poster = channel.logoUrl, background = null, description = null, releaseInfo = null,
            )
            var best = app.addonStreamResolver.resolve(
                addon, ref.sourceIndex, ref.type, meta, force = false,
            ).firstOrNull()
            if (best == null) {
                val dead = app.addonStreamResolver.deadUrls()
                val direct = runCatching { addon.streams(meta.type, meta.id) }
                    .getOrDefault(emptyList())
                    .filter { it.url.startsWith("http") && it.url !in dead }
                    .maxByOrNull {
                        AddonStreamResolver.qualityOf(it.label, it.url).first
                    }
                if (direct != null) {
                    val (rank, label) = AddonStreamResolver.qualityOf(direct.label, direct.url)
                    best = ResolvedStream(
                        url = direct.url,
                        headers = direct.headers,
                        qualityLabel = label,
                        qualityRank = rank,
                        latencyMs = -1L,
                    )
                }
            }
            loading = false
            if (best != null) {
                onPlay(best.url, channel.name, streamHeaders(best), channel.id, resumeMs)
            } else {
                error = "No working direct stream found for ${channel.name}"
            }
        }
    }

    fun showOptions(channel: Channel) {
        optionsFor = channel
        optionsStreams = AddonStreamCache.get(AddonStreamCache.keyForChannelId(channel.id).orEmpty()).orEmpty()
        scope.launch {
            val ref = AddonStreamResolver.parseChannelId(channel.id) ?: return@launch
            val addon = loadedAddons[ref.sourceIndex] ?: return@launch
            val meta = StremioAddon.Meta(
                id = ref.metaId, type = ref.type.lowercase(), name = channel.name,
                poster = channel.logoUrl, background = null, description = null, releaseInfo = null,
            )
            val streams = app.addonStreamResolver.resolve(addon, ref.sourceIndex, ref.type, meta, force = true)
            optionsStreams = streams
        }
    }

    BackHandler {
        when {
            optionsFor != null -> optionsFor = null
            query.isNotBlank() -> query = ""
            favouritesOnly || continueOnly -> {
                favouritesOnly = false
                continueOnly = false
            }
            else -> onBack()
        }
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
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ADD-ONS",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Black,
                        color = IptvPalette.Accent,
                        letterSpacing = 2.sp,
                    ),
                )
                Spacer(Modifier.weight(1f))
                if (loading || searching) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
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
                        Icons.Filled.ArrowBack,
                        contentDescription = stringResourceSafe(R.string.icon_desc_back),
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.Center).size(24.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { androidx.compose.material3.Text("Search add-on catalogues") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(0.5f),
                )
                Spacer(Modifier.width(12.dp))
                if (searching) {
                    Text(
                        "Searching…",
                        style = MaterialTheme.typography.bodySmall,
                        color = IptvPalette.TextSecondary,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                FilterPill("All", !favouritesOnly && !continueOnly) {
                    favouritesOnly = false
                    continueOnly = false
                }
                Spacer(Modifier.width(8.dp))
                FilterPill("Favourites", favouritesOnly) {
                    favouritesOnly = true
                    continueOnly = false
                }
                Spacer(Modifier.width(8.dp))
                FilterPill("Continue", continueOnly) {
                    continueOnly = true
                    favouritesOnly = false
                }
            }
            Spacer(Modifier.height(10.dp))

            if (catalogs.isNotEmpty()) {
                TvLazyRow(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    rowItemsIndexed(catalogs) { _, (sourceIndex, catalog) ->
                        val active = selectedCatalog?.first == sourceIndex &&
                            selectedCatalog?.second?.id == catalog.id
                        FilterPill(
                            "${manifests[sourceIndex]?.name ?: "Add-on"} · ${catalog.name} · ${catalog.type}",
                            active,
                        ) {
                            selectedCatalog = sourceIndex to catalog
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = IptvPalette.Accent)
            }
            if (term.isNotEmpty() && searchFailed) {
                Text(
                    "Add-on search unavailable — showing matching loaded titles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = IptvPalette.TextSecondary,
                )
            }
            if (manifests.isEmpty() && channels.isEmpty() && continued.isEmpty()) {
                Text(
                    "No add-on sources configured. Add a manifest URL in Settings → Add-ons.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = IptvPalette.TextSecondary,
                )
            } else {
                TvLazyVerticalGrid(
                    columns = TvGridCells.Adaptive(168.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(visible, key = { it.id }) { channel ->
                        PosterCard(
                            channel = channel,
                            progressFraction = progressById[channel.id]?.fraction,
                            onClick = { play(channel) },
                            onLongClick = { showOptions(channel) },
                        )
                    }
                }
            }
        }

        optionsFor?.let { channel ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(IptvPalette.BackgroundDeep.copy(alpha = 0.86f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(IptvPalette.SurfaceElevated)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        channel.name,
                        style = MaterialTheme.typography.titleLarge.copy(color = IptvPalette.TextPrimary),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${channel.type.name} · ${channel.groupTitle ?: "Add-ons"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = IptvPalette.AccentSoft,
                    )
                    Text(
                        "OK plays the best working stream. Long-press opens these options.",
                        style = MaterialTheme.typography.bodySmall,
                        color = IptvPalette.TextSecondary,
                    )
                    if (optionsStreams.isEmpty()) {
                        Text(
                            if (loading) "Checking streams…" else "No working direct stream found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = IptvPalette.AccentSoft,
                        )
                    } else {
                        optionsStreams.take(6).forEach { stream ->
                            val isBest = stream.url == optionsStreams.first().url
                            var focused by remember(stream.url) { mutableStateOf(false) }
                            Surface(
                                onClick = {
                                    onPlay(
                                        stream.url,
                                        channel.name,
                                        streamHeaders(stream),
                                        channel.id,
                                        resumeMsFor(channel),
                                    )
                                    optionsFor = null
                                },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (isBest) IptvPalette.SurfaceLift else IptvPalette.SurfaceElevated,
                                    focusedContainerColor = IptvPalette.Accent,
                                    contentColor = IptvPalette.TextPrimary,
                                    focusedContentColor = Color.White,
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                                    .onFocusChanged { focused = it.isFocused }
                                    .tvFocus(focused, RoundedCornerShape(8.dp)),
                            ) {
                                Row(
                                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    Text(
                                        "${stream.qualityLabel} · ${stream.latencyMs} ms${if (isBest) " · best" else ""}",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { showOptions(channel) }) { Text("Refresh streams") }
                        Button(onClick = {
                            scope.launch {
                                if (channel.id in favouriteIds) dao.removeFavorite(profileId, channel.id)
                                else dao.addFavorite(profileId, channel.id)
                            }
                        }) {
                            Text(if (channel.id in favouriteIds) "Unmark favourite" else "Mark favourite")
                        }
                        Button(onClick = {
                            optionsStreams.firstOrNull()?.url?.let { url ->
                                scope.launch { app.addonStreamResolver.markDead(url, channel.id, "Marked by user") }
                            }
                            optionsFor = null
                        }) { Text("Mark dead") }
                    }
                    Button(onClick = { optionsFor = null }) { Text("Close") }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) IptvPalette.Accent else IptvPalette.SurfaceLift,
            focusedContainerColor = if (selected) IptvPalette.AccentDeep else IptvPalette.SurfaceElevated,
            contentColor = if (selected) Color.White else IptvPalette.TextPrimary,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(focused, shape),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = if (selected || focused) Color.White else IptvPalette.TextPrimary,
                fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun stringResourceSafe(id: Int): String = androidx.compose.ui.res.stringResource(id)
