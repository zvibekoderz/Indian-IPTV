package com.indian.iptv.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.indian.iptv.data.Channel
import com.indian.iptv.data.m3u.M3uParser
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads and merges one or more M3U playlists into a single catalogue.
 *
 * Ids stay exactly as the parser produced them for the **first** playlist, so favourites
 * and watch progress survive a multi-playlist upgrade. A later playlist that reuses an id
 * gets an `@s<index>` suffix — deterministic across refreshes, so those rows keep their
 * identity too.
 *
 * Each URL keeps its own ETag / Last-Modified validator. The two validator strings are
 * stored newline-joined and matched by position; adding or removing a playlist clears them.
 */
class M3uPlaylistRepository(
    private val urls: List<String>,
    private val httpClient: OkHttpClient,
) : PlaylistRepository {

    override suspend fun fetch(
        etag: String?,
        lastModified: String?,
        onProgress: (ImportProgress) -> Unit,
    ): PlaylistSnapshot = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) return@withContext PlaylistSnapshot(channels = emptyList())

        val etags = etag?.split('\n').orEmpty()
        val mods = lastModified?.split('\n').orEmpty()

        val merged = LinkedHashMap<String, Channel>()
        val newEtags = ArrayList<String?>(urls.size)
        val newMods = ArrayList<String?>(urls.size)
        var anyChanged = false
        var successCount = 0
        var lastFailure: Exception? = null

        urls.forEachIndexed { index, url ->
            onProgress(ImportProgress(ImportProgress.Stage.Downloading, index + 1))
            val request = Request.Builder()
                .url(url)
                .apply {
                    etags.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { header("If-None-Match", it) }
                    mods.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { header("If-Modified-Since", it) }
                }
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == 304 -> {
                            successCount++
                            newEtags += etags.getOrNull(index).orEmpty()
                            newMods += mods.getOrNull(index).orEmpty()
                        }

                        !response.isSuccessful -> {
                            // One dead playlist must not sink the rest of the catalogue.
                            lastFailure = java.io.IOException("Playlist HTTP ${response.code}: $url")
                            if (urls.size == 1) throw lastFailure!!
                            newEtags += ""
                            newMods += ""
                        }

                        else -> {
                            val body = response.body
                            if (body == null) {
                                newEtags += ""
                                newMods += ""
                            } else {
                                successCount++
                                anyChanged = true
                                val channels = body.charStream().buffered().useLines { M3uParser.parse(it) }
                                mergeInto(merged, channels, index)
                                onProgress(ImportProgress(ImportProgress.Stage.Live, merged.size))
                                newEtags += response.header("ETag").orEmpty()
                                newMods += response.header("Last-Modified").orEmpty()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // A single unreachable playlist should not blank the catalogue.
                lastFailure = e
                if (urls.size == 1) throw e
                newEtags += ""
                newMods += ""
            }
        }

        // Every playlist failing is an outage, not "nothing changed" — otherwise the caller
        // would mark the refresh successful and keep serving a stale catalogue silently.
        if (successCount == 0) {
            throw lastFailure ?: IllegalStateException("No playlist could be downloaded")
        }

        if (!anyChanged) {
            PlaylistSnapshot(channels = emptyList(), notModified = true)
        } else {
            PlaylistSnapshot(
                channels = merged.values.toList(),
                etag = newEtags.joinToString("\n"),
                lastModified = newMods.joinToString("\n"),
            )
        }
    }

    private fun mergeInto(
        target: LinkedHashMap<String, Channel>,
        channels: List<Channel>,
        sourceIndex: Int,
    ) {
        for (channel in channels) {
            if (!target.containsKey(channel.id)) {
                target[channel.id] = channel
                continue
            }
            var candidate = "${channel.id}@s$sourceIndex"
            var n = 2
            while (target.containsKey(candidate)) {
                candidate = "${channel.id}@s$sourceIndex#$n"
                n++
            }
            target[candidate] = channel.copy(id = candidate)
        }
    }
}