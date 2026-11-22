package com.indian.iptv.mobile.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One channel that passed the reachability probe, ready to play. */
data class WorkingChannel(
    val meta: StremioAddon.Meta,
    val stream: StremioAddon.Stream,
    val catalogName: String,
)

/**
 * Persistent cache for the Bridge TV tab.
 *
 * Two things are expensive there: probing every stream URL, and asking the
 * add-on for a fresh catalog on every keystroke. Both results are kept in
 * memory for the session and in SharedPreferences across restarts, with a
 * short TTL because signed CDN links expire.
 */
object BridgeTvCache {

    private const val PREFS_NAME = "bridge_tv_cache"
    private const val KEY_PROBES = "probes"
    private const val KEY_SEARCHES = "searches"
    // Public add-ons hand out signed CDN links that stay valid for hours, so a
    // longer TTL is safe and makes revisiting a search effectively instant.
    private const val PROBE_TTL_MS = 30 * 60_000L
    private const val SEARCH_TTL_MS = 30 * 60_000L

    private data class TimedProbe(val at: Long, val ok: Boolean)
    private data class TimedResults(val at: Long, val items: List<WorkingChannel>)

    private val probes = ConcurrentHashMap<String, TimedProbe>()
    private val searches = ConcurrentHashMap<String, TimedResults>()
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
    }

    fun probe(url: String): Boolean? {
        val entry = probes[url] ?: return null
        if (System.currentTimeMillis() - entry.at > PROBE_TTL_MS) {
            probes.remove(url)
            return null
        }
        return entry.ok
    }

    fun putProbe(url: String, ok: Boolean) {
        probes[url] = TimedProbe(System.currentTimeMillis(), ok)
    }

    fun results(key: String): List<WorkingChannel>? {
        val entry = searches[key] ?: return null
        if (System.currentTimeMillis() - entry.at > SEARCH_TTL_MS) {
            searches.remove(key)
            return null
        }
        return entry.items
    }

    fun putResults(key: String, channels: List<WorkingChannel>) {
        searches[key] = TimedResults(System.currentTimeMillis(), channels)
        flush()
    }

    fun clearResults(key: String) {
        searches.remove(key)
        flush()
    }

    /**
     * A stream that passed the probe but failed during real playback (e.g. its
     * signed token expired in between). Hide it from every cached search and
     * remember the failed probe so it stays out until its short TTL expires.
     */
    fun markDead(url: String) {
        probes[url] = TimedProbe(System.currentTimeMillis(), false)
        searches.forEach { (key, entry) ->
            val kept = entry.items.filterNot { it.stream.url == url }
            if (kept.size != entry.items.size) {
                searches[key] = entry.copy(items = kept)
            }
        }
        flush()
    }

    /** Refresh gives recovered links another chance instead of trusting old 403s. */
    fun clearDeadProbes() {
        probes.entries.removeIf { !it.value.ok }
        flush()
    }

    /** Search cache key — a different add-on, catalog or query is a different result. */
    fun key(addonUrl: String, catalogId: String, query: String): String =
        "$addonUrl|$catalogId|${query.trim().lowercase()}"

    private fun flush() {
        val store = prefs ?: return
        val probeJson = JSONObject().apply {
            probes.forEach { (url, entry) ->
                put(url, JSONObject().put("ok", entry.ok).put("at", entry.at))
            }
        }
        val searchJson = JSONArray().apply {
            searches.forEach { (key, entry) ->
                put(JSONObject().put("key", key).put("at", entry.at).put("items", entry.items.toJson()))
            }
        }
        store.edit()
            .putString(KEY_PROBES, probeJson.toString())
            .putString(KEY_SEARCHES, searchJson.toString())
            .apply()
    }

    private fun load() {
        val store = prefs ?: return
        runCatching {
            val probeJson = JSONObject(store.getString(KEY_PROBES, "{}").orEmpty())
            probeJson.keys().forEach { url ->
                val entry = probeJson.optJSONObject(url) ?: return@forEach
                probes[url] = TimedProbe(entry.optLong("at"), entry.optBoolean("ok"))
            }
            val searchJson = JSONArray(store.getString(KEY_SEARCHES, "[]").orEmpty())
            for (i in 0 until searchJson.length()) {
                val obj = searchJson.optJSONObject(i) ?: continue
                searches[obj.optString("key")] = TimedResults(
                    obj.optLong("at"),
                    obj.optJSONArray("items").orEmpty().mapNotNull { it.fromJson() },
                )
            }
        }
    }

    private fun List<WorkingChannel>.toJson(): JSONArray = JSONArray().apply {
        forEach { item ->
            val headers = JSONObject().apply {
                item.stream.headers.forEach { (name, value) -> put(name, value) }
            }
            put(JSONObject()
                .put("metaId", item.meta.id)
                .put("metaType", item.meta.type)
                .put("name", item.meta.name)
                .put("poster", item.meta.poster)
                .put("background", item.meta.background)
                .put("description", item.meta.description)
                .put("releaseInfo", item.meta.releaseInfo)
                .put("streamUrl", item.stream.url)
                .put("streamLabel", item.stream.label)
                .put("headers", headers)
                .put("catalogName", item.catalogName),
            )
        }
    }

    private fun Any?.fromJson(): WorkingChannel? {
        val obj = this as? JSONObject ?: return null
        val metaId = obj.optString("metaId")
        val streamUrl = obj.optString("streamUrl")
        if (metaId.isEmpty() || streamUrl.isEmpty()) return null
        val headers = mutableMapOf<String, String>()
        obj.optJSONObject("headers")?.let { json ->
            json.keys().forEach { name -> json.opt(name)?.toString()?.let { headers[name] = it } }
        }
        return WorkingChannel(
            meta = StremioAddon.Meta(
                id = metaId,
                type = obj.optString("metaType"),
                name = obj.optString("name").ifEmpty { metaId },
                poster = obj.optString("poster").takeIf { it.startsWith("http", true) },
                background = obj.optString("background").takeIf { it.startsWith("http", true) },
                description = obj.optString("description").ifEmpty { null },
                releaseInfo = obj.optString("releaseInfo").ifEmpty { null },
            ),
            stream = StremioAddon.Stream(streamUrl, obj.optString("streamLabel"), headers),
            catalogName = obj.optString("catalogName"),
        )
    }

    private fun JSONArray?.orEmpty(): List<Any?> =
        if (this == null) emptyList() else (0 until length()).map { opt(it) }
}
