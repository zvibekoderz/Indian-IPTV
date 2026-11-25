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
import java.util.concurrent.TimeUnit

/**
 * Probes add-on streams before they reach the IPTV app and remembers confirmed-dead
 * URLs so they are never returned again unless the list is cleared.
 */
internal object StreamFilter {

    private const val PREFS = "addon_server_dead"
    private const val KEY_DEAD = "dead_urls"
    private const val TTL_MS = 30L * 60 * 1000
    private const val MAX_CANDIDATES = 6
    private const val THREADS = 4

    private data class Cached(val at: Long, val streams: JSONArray)
    private val cache = ConcurrentHashMap<String, Cached>()

    fun deadUrls(context: Context): Set<String> = read(context)

    fun markDead(context: Context, url: String) {
        val set = read(context).toMutableSet()
        if (set.add(url)) write(context, set)
    }

    fun clearDead(context: Context) {
        write(context, emptySet())
        cache.clear()
    }

    /** Working streams, highest resolution first, then lowest probe latency. */
    fun working(context: Context, base: String, type: String, originalId: String): JSONArray {
        val key = "$base|$type|$originalId"
        val hit = cache[key]
        if (hit != null && System.currentTimeMillis() - hit.at < TTL_MS) return hit.streams

        val dead = read(context)
        val url = "$base/stream/$type/${encode(originalId)}.json"
        val response = Net.getJson(url) ?: return JSONArray()
        val streams = response.optJSONArray("streams") ?: return JSONArray()

        data class Candidate(val json: JSONObject, val url: String, val rank: Int, val headers: Map<String, String>)
        val candidates = ArrayList<Candidate>()
        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue
            val address = stream.optString("url")
            if (!address.startsWith("http") || address in dead) continue
            val label = listOf("name", "title", "description")
                .map { stream.optString(it) }
                .firstOrNull { it.isNotBlank() } ?: address
            candidates += Candidate(stream, address, qualityRank(label, address), headersOf(stream))
        }

        val pool = Executors.newFixedThreadPool(THREADS)
        val results = ConcurrentHashMap<String, Pair<Int, Long>>()
        try {
            candidates.sortByDescending { it.rank }
            val futures = candidates.take(MAX_CANDIDATES).map { candidate ->
                pool.submit {
                    val started = System.currentTimeMillis()
                    val ok = probe(candidate.url, candidate.headers)
                    val latency = System.currentTimeMillis() - started
                    if (ok) results[candidate.url] = candidate.rank to latency
                    else markDead(context, candidate.url)
                }
            }
            futures.forEach { runCatching { it.get() } }
        } finally {
            pool.shutdown()
            runCatching { pool.awaitTermination(5, TimeUnit.SECONDS) }
        }

        val output = JSONArray()
        candidates
            .filter { results.containsKey(it.url) }
            .sortedWith(compareByDescending<Candidate> { results[it.url]!!.first }
                .thenBy { results[it.url]!!.second })
            .forEach { output.put(it.json) }

        cache[key] = Cached(System.currentTimeMillis(), output)
        return output
    }

    private fun probe(url: String, headers: Map<String, String>): Boolean = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 4_000
        connection.readTimeout = 6_000
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
            false
        } else {
            val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                val buffer = CharArray(16_384)
                val read = reader.read(buffer)
                if (read < 0) "" else String(buffer, 0, read)
            }
            connection.disconnect()
            val playlist = url.substringBefore('?').endsWith(".m3u8", true) ||
                body.contains("#EXTM3U", true)
            !playlist || body.contains("#EXTM3U", true)
        }
    } catch (_: Exception) {
        false
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
