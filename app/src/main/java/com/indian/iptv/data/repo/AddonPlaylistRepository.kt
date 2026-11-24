package com.indian.iptv.data.repo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.indian.iptv.data.Channel
import com.indian.iptv.data.ContentType
import com.indian.iptv.data.addon.AddonStreamResolver
import com.indian.iptv.data.addon.StremioAddon
import com.indian.iptv.data.db.ChannelDao
import com.indian.iptv.data.db.IptvDatabase
import com.indian.iptv.data.settings.SettingsStore

/**
 * Imports Stremio-compatible add-on catalogues into the same `Channel` pipeline as M3U.
 *
 * Each title gets a stable id `addon:<sourceIndex>:<type>:<metaId>` so favourites and
 * watch progress survive refreshes. Only movies and live TV are imported in this phase:
 * Stremio series metadata has no season/episode structure matching SeriesDetailScreen.
 *
 * Streams are resolved with [AddonStreamResolver] before a row is emitted, so only the
 * best working direct link reaches the home screen. Confirmed-dead links are excluded
 * up front and never reused.
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
        val dismissals = runCatching { settings.bridgeFavouriteDismissals.first() }.getOrDefault(emptySet())
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
                val metas = runCatching { addon.catalog(catalog.type, catalog.id) }
                    .onFailure { Log.w(TAG, "Catalog ${catalog.id} failed: ${it.message}") }
                    .getOrDefault(emptyList())

                metas.forEach metas@{ meta ->
                    val id = "addon:$index:${contentType.name}:${meta.id}"
                    val working = resolver.resolve(addon, index, contentType.name, meta, force = false, dead = dead)
                    val best = working.firstOrNull()
                    // Live channels without a verified stream are kept as metadata-only rows
                    // so they can be upgraded by the boot resolver; movies with no working
                    // direct link are skipped entirely (nothing playable to show).
                    if (best == null && contentType == ContentType.MOVIE) return@metas

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
                    if (best != null) autoFavourite(id, meta.id, meta.name, dismissals)
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

    private suspend fun autoFavourite(
        channelId: String,
        metaId: String,
        name: String,
        dismissals: Set<String>,
    ) {
        if (!AUTO_FAVOURITE_REGEX.containsMatchIn(name.lowercase())) return
        if (channelId in dismissals || metaId in dismissals) return
        dao.addFavorite(IptvDatabase.DEFAULT_PROFILE_ID, channelId)
    }

    companion object {
        private const val TAG = "AddonRepo"
        private val AUTO_FAVOURITE_REGEX = Regex("udya|udaya|kannada")
    }
}
