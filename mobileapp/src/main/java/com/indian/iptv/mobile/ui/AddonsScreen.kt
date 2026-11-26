package com.indian.iptv.mobile.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.launch

/** Touch-first browser for Stremio-compatible add-on catalogues. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonsScreen(settings: Settings, onBack: () -> Unit, onPlay: (Channel) -> Unit) {
    val savedUrl = remember { settings.addonUrl }

    var input by remember { mutableStateOf(savedUrl) }
    var activeUrl by remember { mutableStateOf(savedUrl) }
    var addon by remember { mutableStateOf<StremioAddon?>(null) }
    var manifest by remember { mutableStateOf<StremioAddon.Manifest?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var selectedIndex by remember { mutableIntStateOf(0) }
    var metas by remember { mutableStateOf<List<StremioAddon.Meta>>(emptyList()) }
    var pendingMeta by remember { mutableStateOf<StremioAddon.Meta?>(null) }
    var streams by remember { mutableStateOf<List<StremioAddon.Stream>>(emptyList()) }

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
            .onFailure { addon = null; error = it.message ?: "Couldn't reach the add-on" }
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
            .onFailure { error = it.message ?: "Couldn't load catalogue" }
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
            .onFailure { error = it.message ?: "Couldn't load streams" }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add-ons") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Add-on URL (manifest.json or host)") },
                    placeholder = { Text("http://127.0.0.1:8080/manifest.json") },
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        settings.addonUrl = input
                        activeUrl = input
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect") }
                Text(
                    text = "CNCVerse Bridge shows this URL when its server is running. " +
                        "Use 127.0.0.1 when Bridge runs on this phone, or a LAN address " +
                        "when it runs on a computer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                loading && manifest == null -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                error != null && manifest == null -> Column(
                    Modifier.padding(16.dp),
                ) { Text(error ?: "Add-on unavailable", color = MaterialTheme.colorScheme.error) }

                manifest == null -> Text(
                    "Enter an add-on URL above and tap Connect.",
                    modifier = Modifier.padding(16.dp),
                )

                else -> {
                    Text(
                        text = manifest?.name.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    if (catalogs.isEmpty()) {
                        Text(
                            "This add-on publishes no catalogues.",
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(catalogs) { catalog ->
                                FilterChip(
                                    selected = catalog === selectedCatalog,
                                    onClick = { selectedIndex = catalogs.indexOf(catalog) },
                                    label = { Text("${catalog.name} · ${catalog.type}") },
                                )
                            }
                        }
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        error?.let {
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
                            items(metas, key = { it.type + it.id }) { meta ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { pendingMeta = meta }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val poster = meta.poster
                                    if (poster != null) {
                                        AsyncImage(
                                            model = poster,
                                            contentDescription = meta.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(width = 60.dp, height = 88.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                        )
                                        Spacer(Modifier.width(12.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            meta.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val subtitle = meta.releaseInfo ?: meta.type
                                        Text(
                                            subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingMeta?.let { meta ->
        AlertDialog(
            onDismissRequest = { pendingMeta = null },
            title = { Text(meta.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Loading streams…")
                    }
                    streams.isEmpty() -> Text(error ?: "No direct-playable stream found.")
                    else -> LazyColumn {
                        items(streams, key = { it.url }) { stream ->
                            TextButton(
                                onClick = {
                                    val ua = stream.headers.entries
                                        .firstOrNull { it.key.equals("User-Agent", true) }?.value
                                    val ref = stream.headers.entries
                                        .firstOrNull {
                                            it.key.equals("Referer", true) || it.key.equals("Referrer", true)
                                        }?.value
                                    pendingMeta = null
                                    onPlay(
                                        Channel(
                                            name = meta.name,
                                            url = stream.url,
                                            logo = meta.poster,
                                            category = meta.type,
                                            tvgId = null,
                                            userAgent = ua,
                                            referrer = ref,
                                            headers = stream.headers,
                                        ),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stream.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingMeta = null }) { Text("Close") }
            },
        )
    }
}
