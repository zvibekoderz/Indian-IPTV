package com.indian.iptv.data.addon

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.indian.iptv.data.db.AddonDeadStreamEntity
import com.indian.iptv.data.db.ChannelDao

/**
 * Finds the best playable direct stream for one add-on title.
 *
 * Ranking (in order): highest resolution label → successful reachability probe →
 * lowest probe latency. Only direct http(s) URLs from [StremioAddon] are considered;
 * torrent/YouTube-only entries are already discarded by its parser.
 *
 * Streams that fail the probe are persisted as dead and excluded from every future
 * import/playback. A failed *metadata* request (manifest/catalog/stream JSON) never
 * marks anything dead — only an actual stream probe does.
 */
class AddonStreamResolver(private val dao: ChannelDao) {

    private val probePermits = Semaphore(MAX_PARALLEL_PROBES)

    suspend fun deadUrls(): Set<String> = dao.deadAddonUrls().toHashSet()

    /**
     * Resolve [meta]'s streams. When [force] is false a fresh cache hit is reused so a
     * catalogue refresh doesn't re-probe every title. Boot resolution passes true.
     */
    suspend fun resolve(
        addon: StremioAddon,
        sourceIndex: Int,
        type: String,
        meta: StremioAddon.Meta,
        force: Boolean = false,
        dead: Set<String>? = null,
    ): List<ResolvedStream> = withContext(Dispatchers.IO) {
        val dead = dead ?: deadUrls()
        val key = AddonStreamCache.key(sourceIndex, type, meta.id)
        if (!force) {
            val cached = AddonStreamCache.get(key)?.filter { it.url !in dead }
            if (!cached.isNullOrEmpty()) return@withContext cached
        }

        // A failed add-on request is not evidence that a stream is dead: leave the
        // dead-link store untouched and let the caller keep its last-known best.
        val streams = runCatching { addon.streams(meta.type, meta.id) }
            .onFailure { Log.w(TAG, "stream request failed for ${meta.id}: ${it.message}") }
            .getOrNull()
            .orEmpty()
            .filter { it.url !in dead }

        if (streams.isEmpty()) {
            // Only trust the cache if the fresh lookup couldn't produce candidates.
            return@withContext if (!force) AddonStreamCache.get(key).orEmpty() else emptyList()
        }

        // Probe the highest-resolution candidates first, but keep enough of them to
        // apply the latency tie-breaker among equally good links.
        val candidates = streams
            .map { stream ->
                val (rank, label) = qualityOf(stream.label, stream.url)
                Candidate(stream, rank, label)
            }
            .sortedWith(compareByDescending<Candidate> { it.rank }
                .thenBy { it.stream.url.hashCode().toLong() and 0xffff })
            .take(MAX_CANDIDATES)

        val working = coroutineScope {
            candidates.map { candidate ->
                async {
                    probePermits.withPermit {
                        val started = SystemClock.elapsedRealtime()
                        val ok = StreamProbe.isPlayable(candidate.stream.url, candidate.stream.headers)
                        val latency = SystemClock.elapsedRealtime() - started
                        if (ok) {
                            candidate to ResolvedStream(
                                url = candidate.stream.url,
                                headers = candidate.stream.headers,
                                qualityLabel = candidate.label,
                                qualityRank = candidate.rank,
                                latencyMs = latency,
                            )
                        } else {
                            markDead(candidate.stream.url, key, "Probe failed")
                            null
                        }
                    }
                }
            }.mapNotNull { it.await() }
        }

        // Highest resolution first; among equal resolutions the faster probe wins.
        val ordered = working
            .sortedWith(compareByDescending<Pair<Candidate, ResolvedStream>> { it.second.qualityRank }
                .thenBy { it.second.latencyMs })
            .map { it.second }

        AddonStreamCache.put(key, ordered)
        ordered
    }

    /** Persistence + cache bookkeeping for a URL that failed a real probe or playback. */
    suspend fun markDead(url: String, channelId: String?, reason: String) = withContext(Dispatchers.IO) {
        BridgeTvCache.markDead(url)
        dao.addDeadAddonStream(
            AddonDeadStreamEntity(
                url = url,
                channelId = channelId,
                reason = reason,
                checkedAt = System.currentTimeMillis(),
            ),
        )
        if (channelId != null) AddonStreamCache.dropUrl(channelId, url)
    }

    /** Retest a previously dead link. A working retest removes it from the dead store. */
    suspend fun retest(url: String, headers: Map<String, String> = emptyMap()): Boolean =
        withContext(Dispatchers.IO) {
            val ok = StreamProbe.isPlayable(url, headers)
            if (ok) dao.removeDeadAddonStream(url) else markDead(url, null, "Retest failed")
            ok
        }

    suspend fun unmarkDead(url: String) = withContext(Dispatchers.IO) {
        dao.removeDeadAddonStream(url)
    }

    suspend fun clearDead() = withContext(Dispatchers.IO) {
        dao.clearDeadAddonStreams()
        AddonStreamCache.clear()
    }

    /** Called when a stream fails during playback: remember it, then use the next best. */
    suspend fun onPlaybackFailure(channelId: String, failedUrl: String): ResolvedStream? {
        val key = AddonStreamCache.keyForChannelId(channelId) ?: return null
        val next = AddonStreamCache.nextAfter(key, failedUrl)
        markDead(failedUrl, key, "Playback failed")
        return next
    }

    private data class Candidate(
        val stream: StremioAddon.Stream,
        val rank: Int,
        val label: String,
    )

    companion object {
        private const val TAG = "AddonResolver"
        private const val MAX_CANDIDATES = 6
        private const val MAX_PARALLEL_PROBES = 6

        fun qualityOf(label: String, url: String): Pair<Int, String> {
            val text = "$label $url".lowercase()
            return when {
                Regex("\\b(4k|2160p?|uhd)\\b").containsMatchIn(text) -> 4 to "4K"
                Regex("\\b1080p?\\b").containsMatchIn(text) -> 3 to "1080p"
                Regex("\\b720p?\\b").containsMatchIn(text) -> 2 to "720p"
                Regex("\\b(480p?|576p?)\\b").containsMatchIn(text) -> 1 to "SD"
                else -> 0 to "Direct"
            }
        }

        /** Parse the stable id produced by [AddonPlaylistRepository]. */
        fun parseChannelId(id: String): ChannelRef? {
            val parts = id.split(':', limit = 4)
            if (parts.size != 4 || parts[0] != "addon") return null
            val index = parts[1].toIntOrNull() ?: return null
            return ChannelRef(index, parts[2], parts[3])
        }

        data class ChannelRef(val sourceIndex: Int, val type: String, val metaId: String)
    }
}
