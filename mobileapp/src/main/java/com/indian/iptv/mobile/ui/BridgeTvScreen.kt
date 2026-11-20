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
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.indian.iptv.mobile.data.Channel
import com.indian.iptv.mobile.data.Settings
import com.indian.iptv.mobile.data.StremioAddon
import com.indian.iptv.mobile.data.StreamProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private data class WorkingChannel(
    val meta: StremioAddon.Meta,
    val stream: StremioAddon.Stream,
    val catalogName: String,
)

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
    val probeCache = remember { mutableStateMapOf<String, Boolean>() }

    val tvCatalogs = manifest?.catalogs?.filter { it.type.equals("tv", ignoreCase = true) }.orEmpty()
    val catalogsKey = tvCatalogs.joinToString { "${it.type}/${it.id}" }

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

    LaunchedEffect(addon, catalogsKey, selectedCatalogId, query) {
        val client = addon ?: run {
            results = emptyList()
            return@LaunchedEffect
        }
        val catalogs = tvCatalogs.filter { selectedCatalogId.isEmpty() || it.id == selectedCatalogId }
        if (catalogs.isEmpty()) {
            results = emptyList()
            return@LaunchedEffect
        }
        // Debounce typing so we don't hammer the add-on on every keystroke.
        delay(400)
        checking = true
        checkError = null
        val term = query.trim()
        val candidates = mutableListOf<Pair<StremioAddon.Meta, String>>()
        val failure = runCatching {
            catalogs.forEach { catalog ->
                client.catalog(catalog.type, catalog.id, term.ifEmpty { null })
                    .forEach { candidates += it to catalog.name }
            }
        }.exceptionOrNull()
        if (failure != null) {
            checkError = failure.message ?: "Couldn't load channels"
            results = emptyList()
            checking = false
            return@LaunchedEffect
        }

        val limited = candidates
            .distinctBy { (meta, _) -> meta.type + "|" + meta.id }
            .take(MAX_CANDIDATES)
        if (limited.isEmpty()) {
            results = emptyList()
            checking = false
            return@LaunchedEffect
        }

        val permits = Semaphore(PROBE_CONCURRENCY)
        val working = coroutineScope {
            limited.map { (meta, catalogName) ->
                async(Dispatchers.IO) {
                    permits.withPermit {
                        val streams = runCatching { client.streams(meta.type, meta.id) }
                            .getOrDefault(emptyList())
                        val playable = streams.firstOrNull { stream ->
                            val cached = probeCache[stream.url]
                            cached ?: StreamProbe.isPlayable(stream.url, stream.headers)
                                .also { probeCache[stream.url] = it }
                        }
                        playable?.let { WorkingChannel(meta, it, catalogName) }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        results = working
        checking = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bridge TV") },
                actions = {
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
                    .padding(horizontal = 16.dp, top = 8.dp),
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
                            selected = selectedCatalogId.isEmpty(),
                            onClick = { selectedCatalogId = "" },
                            label = { Text("All TV") },
                        )
                    }
                    items(tvCatalogs, key = { it.id }) { catalog ->
                        FilterChip(
                            selected = selectedCatalogId == catalog.id,
                            onClick = { selectedCatalogId = catalog.id },
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
                items(results, key = { it.meta.id + "|" + it.stream.url }) { item ->
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
                    }
                }

                if (!checking && manifest != null && results.isEmpty()) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (tvCatalogs.isEmpty()) {
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

private const val MAX_CANDIDATES = 40
private const val PROBE_CONCURRENCY = 6
