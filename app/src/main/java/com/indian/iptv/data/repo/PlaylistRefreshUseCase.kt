package com.indian.iptv.data.repo

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.indian.iptv.data.db.CategoryEntity
import com.indian.iptv.data.db.ChannelDao
import com.indian.iptv.data.db.toEntity
import com.indian.iptv.data.remote.HttpClient
import com.indian.iptv.data.addon.AddonStreamResolver
import com.indian.iptv.data.settings.SettingsStore
import com.indian.iptv.data.settings.SourceConfig
import com.indian.iptv.data.xtream.CategoryFilter

/**
 * Single entry point for catalogue refreshes (home screen, settings, nightly worker).
 * Serialised with a mutex so two screens can't run two 2-minute bulk inserts at once;
 * [refreshing] and [lastError] are shared so every screen shows the same status.
 */
class PlaylistRefreshUseCase(
    private val settings: SettingsStore,
    private val dao: ChannelDao,
    private val addonResolver: AddonStreamResolver,
    private val onCatalogueChanged: () -> Unit = {},
) {

    private val mutex = Mutex()
    private val epgMutex = Mutex()

    private val _refreshing = MutableStateFlow(false)
    /** True while the catalogue (not the EPG) is being fetched and written. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _progress = MutableStateFlow<ImportProgress?>(null)
    /** Stage + count of the running refresh; null when idle. */
    val progress: StateFlow<ImportProgress?> = _progress.asStateFlow()

    /**
     * @param onCatalogueReady invoked as soon as channels + categories are persisted, BEFORE
     *   the EPG write runs, so the UI can drop its spinner while the EPG persists.
     */
    suspend operator fun invoke(
        force: Boolean = false,
        onCatalogueReady: () -> Unit = {},
    ): Result<Unit> {
        if (!mutex.tryLock()) {
            // Another refresh is already running: wait for it instead of starting a second one.
            mutex.withLock { }
            onCatalogueReady()
            return _lastError.value?.let { Result.failure(IllegalStateException(it)) } ?: Result.success(Unit)
        }
        try {
            _refreshing.value = true
            _lastError.value = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    refresh(force) {
                        _refreshing.value = false
                        onCatalogueReady()
                    }
                }
            }
            result.exceptionOrNull()?.let { err ->
                Log.w(TAG, "Refresh failed", err)
                _lastError.value = err.message?.takeIf { it.isNotBlank() } ?: err.javaClass.simpleName
            }
            return result
        } finally {
            _refreshing.value = false
            _progress.value = null
            mutex.unlock()
        }
    }

    /**
     * Refreshes only the EPG (no channels, films or series). Skipped when a full refresh is
     * running, since that one writes the EPG too.
     */
    suspend fun refreshEpg(): Result<Unit> {
        if (mutex.isLocked || !epgMutex.tryLock()) return Result.success(Unit)
        return try {
            withContext(Dispatchers.IO) {
                runCatching {
                    val repo = primaryRepository() ?: error("No source configured.")
                    writeEpg(repo)
                }.onFailure { Log.w(TAG, "EPG refresh failed", it) }
            }
        } finally {
            epgMutex.unlock()
        }
    }

    private suspend fun writeEpg(repo: PlaylistRepository) {
        val keys = dao.liveEpgKeys().toHashSet()
        if (keys.isEmpty()) return
        val programmes = repo.fetchProgrammes(keys) ?: return
        if (programmes.isEmpty()) return
        dao.replaceProgrammes(programmes)
        settings.markEpgRefresh()
        Log.i(TAG, "EPG refreshed: ${programmes.size} programmes")
    }

    private suspend fun primaryRepository(): PlaylistRepository? =
        when (val config = settings.sourceConfig.first()) {
            null -> null
            is SourceConfig.M3u -> M3uPlaylistRepository(config.urls, HttpClient.okHttp)
            is SourceConfig.Xtream -> XtreamPlaylistRepository(
                config.host,
                config.username,
                config.password,
                CategoryFilter.parse(settings.categoryFilter.first()),
            )
        }

    /** Main source first (its validators/EPG are authoritative), add-ons appended. */
    private suspend fun repositories(): List<PlaylistRepository> {
        val repos = ArrayList<PlaylistRepository>()
        primaryRepository()?.let(repos::add)
        val addonUrls = settings.addonUrls.first()
        if (settings.addonsEnabled.first() && addonUrls.isNotEmpty()) {
            repos += AddonPlaylistRepository(addonUrls, dao, settings, addonResolver)
        }
        return repos
    }

    private suspend fun refresh(force: Boolean, onCatalogueReady: () -> Unit) {
        val repos = repositories()
        if (repos.isEmpty()) error("No source configured.")
        val primary = repos.first()

        val etag = if (force) null else settings.playlistEtag.first()
        val lastMod = if (force) null else settings.playlistLastModified.first()
        val snapshots = repos.map { repo ->
            if (repo === primary) repo.fetch(etag, lastMod) { p -> _progress.value = p }
            else repo.fetch(null, null) { p -> _progress.value = p }
        }
        if (snapshots.all { it.notModified }) {
            settings.markRefreshSuccess()
            onCatalogueReady()
            epgMutex.withLock { runCatching { writeEpg(primary) } }
            return
        }

        val t0 = SystemClock.elapsedRealtime()
        val channels = snapshots.flatMap { it.channels }.mapIndexed { i, c -> c.toEntity(i) }
        val categories = snapshots.flatMap { snapshot ->
            snapshot.channels
                .mapNotNull { c -> c.groupTitle?.let { it to c.type.name } }
                .distinct()
        }
            .distinct()
            .mapIndexed { i, (name, type) ->
                CategoryEntity(id = name, name = name, sortIndex = i, type = type)
            }
        val programmes = snapshots.flatMap { it.programmes }
        val t1 = SystemClock.elapsedRealtime()
        Log.i(TAG, "Built ${channels.size} entities + ${categories.size} categories in ${t1 - t0}ms")

        _progress.value = ImportProgress(ImportProgress.Stage.Saving, channels.size)
        dao.replaceAll(channels, categories)
        val t2 = SystemClock.elapsedRealtime()
        Log.i(TAG, "replaceAll persisted ${channels.size} rows in ${t2 - t1}ms")

        // Commit the catalogue + validators now so a crash during the EPG write doesn't
        // force a full re-fetch on next launch.
        val primarySnapshot = snapshots.first()
        settings.savePlaylistValidators(primarySnapshot.etag, primarySnapshot.lastModified)
        settings.markRefreshSuccess()
        settings.setCatalogueVersion(CATALOGUE_VERSION)
        onCatalogueChanged()
        onCatalogueReady()

        if (programmes.isNotEmpty()) {
            dao.replaceProgrammes(programmes)
            settings.markEpgRefresh()
            val t3 = SystemClock.elapsedRealtime()
            Log.i(TAG, "persisted ${programmes.size} EPG programmes in ${t3 - t2}ms")
        } else {
            // M3U snapshots carry no guide. Populate Now/Next from the bundled tvepg
            // feeds in the same refresh, now that the enriched epgChannelIds are stored.
            epgMutex.withLock { runCatching { writeEpg(primary) } }
        }
    }

    companion object {
        private const val TAG = "PlaylistRefresh"
        /** Bump when the stored catalogue gains data that only a full reload fills in. */
        const val CATALOGUE_VERSION = 18
        const val EPG_MAX_AGE_MS: Long = 6L * 3_600_000L
    }
}
