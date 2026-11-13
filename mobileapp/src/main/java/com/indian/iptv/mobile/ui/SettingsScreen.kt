package com.indian.iptv.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.indian.iptv.mobile.data.Settings

/**
 * Playlist settings: one M3U URL per line (they are all fetched and merged),
 * plus save / reset actions. Standard touch text field — tap opens the
 * keyboard, Enter adds another line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    var urls by remember { mutableStateOf(settings.m3uUrls.joinToString("\n")) }
    var epgUrl by remember { mutableStateOf(settings.epgUrl) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("Playlist URLs (M3U, one per line)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = urls,
                onValueChange = { urls = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                placeholder = { Text("https://example.com/playlist.m3u") },
            )
            Spacer(Modifier.height(16.dp))
            Text("EPG URL (XMLTV, optional)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = epgUrl,
                onValueChange = { epgUrl = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 3,
                placeholder = { Text("https://example.com/guide.xml.gz") },
            )
            Text(
                "For \"Now: …\" programme info under channels and in the player. " +
                    "Any XMLTV guide works (plain or .xml.gz); leave blank to switch it off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    settings.m3uUrls = urls.split("\n")
                    settings.epgUrl = epgUrl
                    onSave()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = urls.isNotBlank(),
            ) {
                Text("Save & reload channels")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    settings.resetUrls()
                    urls = Settings.DEFAULT_URL
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Reset to default URL")
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Tip: add as many playlists as you like — every line is one M3U source. " +
                    "They are all fetched and combined into a single channel list; " +
                    "duplicate channels are removed automatically. Favourites are kept " +
                    "even when a playlist changes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "IPTV Mobile v${com.indian.iptv.mobile.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
