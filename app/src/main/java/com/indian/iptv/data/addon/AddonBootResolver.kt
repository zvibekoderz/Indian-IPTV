package com.indian.iptv.data.addon

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.indian.iptv.IptvApp
import com.indian.iptv.data.settings.SettingsStore

data class AddonResolutionState(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val working: Int = 0,
    val dead: Int = 0,
    val finishedAtMs: Long = 0L,
) {
    val progress: Float get() = if (total == 0) 0f else done.toFloat() / total
}

/**
 * Background, upgrade-only stream resolution for imported add-on rows.
 *
 * The home screen opens immediately with the last-known best link in Room. This
 * resolver then re-probes every add-on title on app start / boot and upgrades the
 * stored URL when a better working stream is found. A title whose stream has died
 * keeps its last-known URL until a replacement passes the probe, so the UI never
 * blanks while resolution is in flight.
 */
class AddonBootResolver(
    private val app: IptvApp,
    private val settings: SettingsStore = app.settings,
    private val resolver: AddonStreamResolver = app.addonStreamResolver,
) {

    private val dao get() = app.database.channelDao()
    private val mutex = Mutex()
    private val _state = MutableStateFlow(AddonResolutionState())
    val state: StateFlow<AddonResolutionState> = _state.asStateFlow()

    suspend fun resolveAllIfEnabled() {
        if (!settings.addonBootResolution.first() || !settings.addonsEnabled.first()) return
        resolveAll()
    }

    suspend fun resolveAll() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val urls = settings.addonUrls.first()
            if (urls.isEmpty()) return@withContext
            val allRows = runCatching { dao.addonChannels() }.getOrDefault(emptyList())
            if (allRows.isEmpty()) return@withContext

            // A single add-on can publish >10k channels. Resolve them progressively so
            // the pass stays bounded; favourites and Udaya/Kannada rows go first. The
            // remaining rows keep their metadata and are resolved on later passes or
            // on demand when the user plays them.
            val favIds = runCatching { dao.observeFavoriteIds(app.activeProfileId.value).first() }
                .getOrDefault(emptyList()).toSet()
            val rows = allRows.sortedWith(
                compareByDescending<com.indian.iptv.data.db.ChannelEntity> { it.id in favIds }
                    .thenByDescending { PRIORITY_TERMS.any { t -> it.name.contains(t, true) } }
                    .thenByDescending {
                        AddonStreamCache.best(
                            AddonStreamCache.keyForChannelId(it.id).orEmpty(),
                        ) == null
                    },
            ).take(MAX_PER_PASS)

            _state.value = AddonResolutionState(running = true, total = rows.size)
            val dead = resolver.deadUrls()
            var working = 0
            var deadCount = 0
            var done = 0

            // Group by source so each add-on instance and manifest is fetched once.
            rows.groupBy { AddonStreamResolver.parseChannelId(it.id)?.sourceIndex ?: -1 }
                .forEach sources@{ (sourceIndex, channels) ->
                    val addon = urls.getOrNull(sourceIndex)?.let { StremioAddon.create(it) }
                    if (addon == null) {
                        done += channels.size
                        deadCount += channels.size
                        _state.value = _state.value.copy(done = done, dead = deadCount)
                        return@sources
                    }
                    channels.forEach rows@{ row ->
                        val ref = AddonStreamResolver.parseChannelId(row.id)
                        if (ref == null) {
                            done++
                            return@rows
                        }
                        val meta = StremioAddon.Meta(
                            id = ref.metaId,
                            type = ref.type.lowercase(),
                            name = row.name,
                            poster = row.logoUrl,
                            background = null,
                            description = null,
                            releaseInfo = null,
                        )
                        val best = runCatching {
                            resolver.resolve(addon, ref.sourceIndex, ref.type, meta, force = true, dead = dead)
                                .firstOrNull()
                        }.getOrNull()
                        if (best != null) {
                            dao.updateStream(row.id, best.url, best.userAgent, best.referrer)
                            working++
                        } else {
                            // Keep row.streamUrl: last-known best stays playable until replaced.
                            deadCount++
                        }
                        done++
                        _state.value = _state.value.copy(done = done, working = working, dead = deadCount)
                    }
                }

            settings.setLastAddonResolution(working, deadCount, rows.size)
            _state.value = _state.value.copy(
                running = false,
                working = working,
                dead = deadCount,
                finishedAtMs = System.currentTimeMillis(),
            )
            Log.i(TAG, "Resolution finished: $working working, $deadCount without a verified link of ${rows.size}")
        }
    }

    companion object {
        private const val TAG = "AddonBootResolver"
        private const val MAX_PER_PASS = 400
        private val PRIORITY_TERMS = listOf("udya", "udaya", "kannada", "jio")
    }
}
