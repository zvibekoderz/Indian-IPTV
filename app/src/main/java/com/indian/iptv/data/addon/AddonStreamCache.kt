package com.indian.iptv.data.addon

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * A direct stream that passed the reachability probe, with its provenance.
 *
 * [qualityRank] is derived from the resolution label: 4 = 4K, 3 = 1080p,
 * 2 = 720p, 1 = SD, 0 = unknown. [latencyMs] is the round-trip time of the
 * successful probe and is the final tie-breaker between equal-quality links.
 */
data class ResolvedStream(
    val url: String,
    val headers: Map<String, String>,
    val qualityLabel: String,
    val qualityRank: Int,
    val latencyMs: Long,
    val checkedAt: Long = System.currentTimeMillis(),
) {
    val userAgent: String? get() = headers.entries.firstOrNull {
        it.key.equals("User-Agent", ignoreCase = true)
    }?.value

    val referrer: String? get() = headers.entries.firstOrNull {
        it.key.equals("Referer", ignoreCase = true) || it.key.equals("Referrer", ignoreCase = true)
    }?.value
}

/**
 * Session + disk cache of the probed streams for each imported add-on title.
 *
 * The first entry is the current best (highest resolution, then lowest latency).
 * Keeping the other working entries lets the player fall back instantly when a
 * signed CDN URL dies mid-playback, without probing again under a black screen.
 *
 * Signed links expire, so entries are only trusted for [TTL_MS]; a boot-time
 * resolution explicitly ignores the cache and re-probes.
 */
object AddonStreamCache {

    private const val PREFS_NAME = "addon_stream_cache"
    private const val KEY_STREAMS = "streams"
    private const val TTL_MS = 30 * 60_000L

    private data class TimedStreams(val at: Long, val streams: List<ResolvedStream>)

    private val cache = ConcurrentHashMap<String, TimedStreams>()
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
    }

    /** Working streams for [key], newest-best first, or null when stale/unknown. */
    fun get(key: String): List<ResolvedStream>? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.at > TTL_MS) {
            cache.remove(key)
            return null
        }
        return entry.streams
    }

    fun best(key: String): ResolvedStream? = get(key)?.firstOrNull()

    /** Next working stream after [failedUrl] has been discarded from consideration. */
    fun nextAfter(key: String, failedUrl: String): ResolvedStream? =
        get(key)?.firstOrNull { it.url != failedUrl }

    fun put(key: String, streams: List<ResolvedStream>) {
        if (streams.isEmpty()) {
            cache.remove(key)
        } else {
            cache[key] = TimedStreams(System.currentTimeMillis(), streams)
        }
        flush()
    }

    /** Drop one dead URL from the cached candidates; keeps the rest for instant fallback. */
    fun dropUrl(key: String, url: String) {
        val entry = cache[key] ?: return
        val kept = entry.streams.filterNot { it.url == url }
        if (kept.isEmpty()) cache.remove(key) else cache[key] = entry.copy(streams = kept)
        flush()
    }

    fun clear() {
        cache.clear()
        flush()
    }

    private fun flush() {
        val store = prefs ?: return
        val root = JSONObject()
        cache.forEach { (key, entry) ->
            val arr = JSONArray()
            entry.streams.forEach { s ->
                val headers = JSONObject()
                s.headers.forEach { (name, value) -> headers.put(name, value) }
                arr.put(
                    JSONObject()
                        .put("url", s.url)
                        .put("headers", headers)
                        .put("qualityLabel", s.qualityLabel)
                        .put("qualityRank", s.qualityRank)
                        .put("latencyMs", s.latencyMs)
                        .put("checkedAt", s.checkedAt),
                )
            }
            root.put(key, JSONObject().put("at", entry.at).put("streams", arr))
        }
        store.edit().putString(KEY_STREAMS, root.toString()).apply()
    }

    private fun load() {
        val store = prefs ?: return
        runCatching {
            val root = JSONObject(store.getString(KEY_STREAMS, "{}").orEmpty())
            root.keys().forEach { key ->
                val obj = root.optJSONObject(key) ?: return@forEach
                val arr = obj.optJSONArray("streams") ?: return@forEach
                val streams = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toResolved() }
                if (streams.isNotEmpty()) cache[key] = TimedStreams(obj.optLong("at"), streams)
            }
        }
    }

    private fun JSONObject.toResolved(): ResolvedStream? {
        val url = optString("url")
        if (url.isBlank()) return null
        val headers = mutableMapOf<String, String>()
        optJSONObject("headers")?.let { json ->
            json.keys().forEach { name -> json.opt(name)?.toString()?.let { headers[name] = it } }
        }
        return ResolvedStream(
            url = url,
            headers = headers,
            qualityLabel = optString("qualityLabel").ifBlank { "Direct" },
            qualityRank = optInt("qualityRank"),
            latencyMs = optLong("latencyMs"),
            checkedAt = optLong("checkedAt").takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
    }

    /** Stable cache key for an imported channel. */
    fun key(sourceIndex: Int, type: String, metaId: String): String =
        "$sourceIndex:${type.uppercase()}:$metaId"

    /** Translate a full `addon:<index>:<TYPE>:<metaId>` channel id into a cache key. */
    fun keyForChannelId(channelId: String): String? =
        channelId.takeIf { it.startsWith("addon:") }?.removePrefix("addon:")
}
