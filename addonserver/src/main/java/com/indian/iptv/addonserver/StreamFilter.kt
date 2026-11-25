package com.indian.iptv.addonserver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Probes add-on streams and remembers confirmed-dead URLs so they are never returned
 * again unless the list is cleared.
 *
 * The reply is built immediately from the best-labelled direct links. Probing happens in
 * a shared parallel pool and upgrades the cached reply, so a slow CDN never blocks the
 * IPTV app's request.
 */
internal object StreamFilter {

    private const val PREFS = "addon_server_dead"
    private const val KEY_DEAD = "dead_urls"
    private const val TTL_MS = 30L * 60 * 1000
    private const val IMMEDIATE_TTL_MS = 2L * 60 * 1000
    private const val MAX_CANDIDATES = 8
    private const val NETWORK_FAILURE = -1
    private const val BAD_PLAYLIST = -2

    private data class Cached(val at: Long, val streams: JSONArray)
    private data class Candidate(
        val json: JSONObject,
        val url: String,
        val rank: Int,
        val headers: Map<String, String>,
    )

    private val cache = ConcurrentHashMap<String, Cached>()
    private val rawCache = ConcurrentHashMap<String, Cached>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newFixedThreadPool(12)

    fun deadUrls(context: Context): Set<String> = read(context)

    fun markDead(context: Context, url: String) {
        val set = read(context).toMutableSet()
        if (set.add(url)) write(context, set)
    }

    fun clearDead(context: Context) {
        write(context, emptySet())
        cache.clear()
        rawCache.clear()
    }

    /** Best streams, highest resolution first, then lowest probe latency. */
    fun working(context: Context, base: String, type: String, originalId: String): JSONArray {
        val key = "$base|$type|$originalId"
        val hit = cache[key]
        if (hit != null && System.currentTimeMillis() - hit.at < TTL_MS) return hit.streams

        val streams = upstreamStreams(key, base, type, originalId)
        if (streams == null || streams.length() == 0) return JSONArray()

        val dead = read(context)
        val candidates = ArrayList<Candidate>()
        for (i in 0 until streams.length()) {
            val stream = streams.opt(i) as? JSONObject ?: continue
            val address = stream.optString("url")
            if (!address.startsWith("http") || address in dead) continue
            val label = listOf("name", "title", "description")
                .map { stream.optString(it) }
                .firstOrNull { it.isNotBlank() } ?: address
            candidates += Candidate(stream, address, qualityRank(label, address), headersOf(stream))
        }
        candidates.sortByDescending { it.rank }
        if (candidates.isEmpty()) return JSONArray()

        // Reply immediately with the best-labelled links. A short TTL keeps repeated
        // requests from scheduling duplicate probe passes while one is in flight.
        val immediate = JSONArray()
        candidates.take(3).forEach { immediate.put(it.json) }
        cache[key] = Cached(System.currentTimeMillis(), immediate)

        if (inFlight.add(key)) {
            pool.execute {
                try {
                    val verified = verify(context, candidates)
                    cache[key] = Cached(System.currentTimeMillis(), verified)
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return immediate
    }

    private fun verify(context: Context, candidates: List<Candidate>): JSONArray {
        val results = ConcurrentHashMap<String, Pair<Int, Long>>()
        val futures = candidates.take(MAX_CANDIDATES).map { candidate ->
            pool.submit {
                val started = System.currentTimeMillis()
                val code = probe(candidate.url, candidate.headers)
                val latency = System.currentTimeMillis() - started
                if (code in 200..399 || code == 416) {
                    results[candidate.url] = candidate.rank to latency
                } else if (code == BAD_PLAYLIST || code == 403 || code == 404 ||
                    code == 410 || code in 500..599
                ) {
                    // Confirmed dead. Network failures stay eligible as unverified links.
                    markDead(context, candidate.url)
                }
            }
        }
        futures.forEach { runCatching { it.get() } }

        val output = JSONArray()
        candidates
            .filter { results.containsKey(it.url) }
            .sortedWith(compareByDescending<Candidate> { results[it.url]!!.first }
                .thenBy { results[it.url]!!.second })
            .forEach { output.put(it.json) }
        if (output.length() == 0) {
            val dead = read(context)
            candidates.firstOrNull { it.url !in dead }?.let { output.put(it.json) }
        }
        return output
    }

    private fun upstreamStreams(
        key: String,
        base: String,
        type: String,
        originalId: String,
    ): JSONArray? {
        val cached = rawCache[key]
        if (cached != null && System.currentTimeMillis() - cached.at < TTL_MS) return cached.streams
        val url = "$base/stream/$type/${encode(originalId)}.json"
        val fetched = runCatching { Net.getJson(url)?.optJSONArray("streams") }.getOrNull()
        if (fetched != null) {
            rawCache[key] = Cached(System.currentTimeMillis(), fetched)
            return fetched
        }
        // Last known list is better than nothing when the add-on is temporarily down.
        return cached?.streams
    }

    /** HTTP status, or [NETWORK_FAILURE]/[BAD_PLAYLIST] for non-HTTP outcomes. */
    private fun probe(url: String, headers: Map<String, String>): Int = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 4_000
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.setRequestProperty("Range", "bytes=0-0")
        connection.setRequestProperty(
            "User-Agent",
            headers["User-Agent"] ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36",
        )
        headers["Referer"]?.let { connection.setRequestProperty("Referer", it) }
        val code = connection.responseCode
        if (code !in 200..399 && code != 416) {
            connection.disconnect()
            code
        } else {
            val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                val buffer = CharArray(16_384)
                val read = reader.read(buffer)
                if (read < 0) "" else String(buffer, 0, read)
            }
            connection.disconnect()
            val playlist = url.substringBefore('?').endsWith(".m3u8", true) ||
                body.contains("#EXTM3U", true)
            if (!playlist || body.contains("#EXTM3U", true)) code else BAD_PLAYLIST
        }
    } catch (_: Exception) {
        NETWORK_FAILURE
    }

    private fun headersOf(stream: JSONObject): Map<String, String> {
        val result = HashMap<String, String>()
        val hints = stream.optJSONObject("behaviorHints")?.optJSONObject("headers")
            ?: stream.optJSONObject("headers")
        hints?.keys()?.forEach { key ->
            hints.opt(key)?.toString()?.let { result[key] = it }
        }
        return result
    }

    private fun qualityRank(label: String, url: String): Int {
        val text = "$label $url".lowercase()
        return when {
            Regex("\\b(4k|2160p?|uhd)\\b").containsMatchIn(text) -> 4
            Regex("\\b1080p?\\b").containsMatchIn(text) -> 3
            Regex("\\b720p?\\b").containsMatchIn(text) -> 2
            Regex("\\b(480p?|576p?)\\b").containsMatchIn(text) -> 1
            else -> 0
        }
    }

    private fun read(context: Context): Set<String> = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_DEAD, null)
        .orEmpty()
        .lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toSet()

    private fun write(context: Context, urls: Set<String>) = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY_DEAD, urls.joinToString("\n")).apply()

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
