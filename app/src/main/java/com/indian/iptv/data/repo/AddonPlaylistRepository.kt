package com.indian.iptv.data.repo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.indian.iptv.data.Channel
import com.indian.iptv.data.ContentType
import com.indian.iptv.data.addon.AddonStreamCache
import com.indian.iptv.data.addon.AddonStreamResolver
import com.indian.iptv.data.addon.StremioAddon
import com.indian.iptv.data.db.ChannelDao
import com.indian.iptv.data.settings.SettingsStore

/**
 * Imports Stremio-compatible add-on catalogues into the same `Channel` pipeline as M3U.
 *
 * Each title gets a stable id `addon:<sourceIndex>:<type>:<metaId>` so favourites and
 * watch progress survive refreshes. Only movies and live TV are imported in this phase:
 * Stremio series metadata has no season/episode structure matching SeriesDetailScreen.
 *
 * Rows are imported as metadata first; a cached verified stream is attached when one
 * exists, and playback resolves the best working direct link on demand. Confirmed-dead
 * links are excluded up front and never reused. Favourites are never added automatically —
 * the user stars channels manually.
 */
class AddonPlaylistRepository(
    private val urls: List<String>,
    private val dao: ChannelDao,
    private val settings: SettingsStore,
    private val resolver: AddonStreamResolver,
) : PlaylistRepository {

    override suspend fun fetch(
        etag: String?,
        lastModified: String?,
        onProgress: (ImportProgress) -> Unit,
    ): PlaylistSnapshot = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) return@withContext PlaylistSnapshot(channels = emptyList(), notModified = true)

        val dead = resolver.deadUrls()
        val channels = ArrayList<Channel>()
        var successfulSources = 0
        var catalogCount = 0

        urls.forEachIndexed { index, raw ->
            val addon = StremioAddon.create(raw)
            if (addon == null) {
                Log.w(TAG, "Skipping invalid add-on URL at index $index")
                return@forEachIndexed
            }
            val manifest = runCatching { addon.manifest() }
                .onFailure { Log.w(TAG, "Manifest failed for $raw: ${it.message}") }
                .getOrNull() ?: return@forEachIndexed
            successfulSources++

            manifest.catalogs.forEach catalogs@{ catalog ->
                val contentType = when (catalog.type.lowercase()) {
                    "tv", "live" -> ContentType.TV
                    "movie", "movies" -> ContentType.MOVIE
                    // Series need a season/episode mapper; defer until that exists.
                    else -> return@catalogs
                }
                catalogCount++
                var metas = emptyList<StremioAddon.Meta>()
                repeat(CATALOG_ATTEMPTS) { attempt ->
                    metas = runCatching { addon.catalog(catalog.type, catalog.id) }
                        .onFailure { Log.w(TAG, "Catalog ${catalog.id} attempt ${attempt + 1} failed: ${it.message}") }
                        .getOrDefault(emptyList())
                    if (metas.isNotEmpty()) return@repeat
                    delay(CATALOG_RETRY_DELAY_MS)
                }

                metas.forEach metas@{ meta ->
                    val id = "addon:$index:${contentType.name}:${meta.id}"
                    // Import the metadata immediately — a 10k+ live catalogue cannot wait
                    // for thousands of stream probes. The last-known best link (if any) is
                    // reused; otherwise the row is metadata-only until the background
                    // resolver or an on-demand click fills in a verified stream.
                    val key = AddonStreamCache.key(index, contentType.name, meta.id)
                    val best = AddonStreamCache.get(key)?.firstOrNull { it.url !in dead }
                    channels += Channel(
                        id = id,
                        name = meta.name,
                        logoUrl = meta.poster,
                        groupTitle = catalog.name,
                        streamUrl = best?.url,
                        epgChannelId = null,
                        type = contentType,
                        userAgent = best?.userAgent,
                        referrer = best?.referrer,
                    )
                }
                onProgress(ImportProgress(ImportProgress.Stage.Live, channels.size))
            }
        }

        if (successfulSources == 0) {
            error("No add-on manifest could be reached")
        }
        Log.i(TAG, "Imported ${channels.size} add-on titles from $successfulSources source(s), $catalogCount catalogues")
        PlaylistSnapshot(channels = channels)
    }

    companion object {
        private const val TAG = "AddonRepo"
        private const val CATALOG_ATTEMPTS = 3
        private const val CATALOG_RETRY_DELAY_MS = 1_000L
    }
}
