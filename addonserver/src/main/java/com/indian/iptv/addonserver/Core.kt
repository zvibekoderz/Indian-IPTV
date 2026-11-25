package com.indian.iptv.addonserver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** Persistent configuration for the local add-on server. */
internal object Prefs {
    private const val NAME = "addon_server"
    private const val KEY_REPOS = "repo_urls"
    private const val KEY_UPSTREAMS = "upstream_urls"
    private const val KEY_AUTO_START = "auto_start"

    private val DEFAULT_REPOS: List<String> = emptyList()

    private val DEFAULT_UPSTREAMS = listOf(
        "https://cncverse.dpdns.org/u/p_hp9u0hb5bw8s/configure",
        "https://cncverse.dpdns.org/u/p_zaidla1redjb/configure",
        "https://cncverse.dpdns.org/u/p_bzvv0txb102g/configure",
        "https://cncverse.dpdns.org/manifest.json",
    )

    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun repos(context: Context): List<String> =
        readList(prefs(context).getString(KEY_REPOS, null)).ifEmpty { DEFAULT_REPOS }

    fun setRepos(context: Context, urls: List<String>) =
        prefs(context).edit().putString(KEY_REPOS, urls.joinToString("\n")).apply()

    fun upstreams(context: Context): List<String> =
        readList(prefs(context).getString(KEY_UPSTREAMS, null)).ifEmpty { DEFAULT_UPSTREAMS }

    fun setUpstreams(context: Context, urls: List<String>) =
        prefs(context).edit().putString(KEY_UPSTREAMS, urls.joinToString("\n")).apply()

    fun autoStart(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_START, true)

    fun setAutoStart(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_START, enabled).apply()

    private fun readList(raw: String?): List<String> = raw.orEmpty()
        .lineSequence().map { it.trim() }.filter { it.isNotBlank() }.distinct().toList()
}

/** Blocking HTTP JSON helper used by the server worker thread. */
internal object Net {
    fun getJson(url: String): JSONObject? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV)")
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            null
        } else {
            val text = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
            connection.disconnect()
            JSONObject(text)
        }
    } catch (_: Exception) {
        null
    }

    fun getArray(url: String): JSONArray? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        val code = connection.responseCode
        if (code !in 200..299) { connection.disconnect(); null } else {
            val text = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
            connection.disconnect()
            JSONArray(text)
        }
    } catch (_: Exception) { null }

    fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}

/** Reads CloudStream repo JSON files and returns the plugin names they contain. */
internal object RepoReader {
    fun pluginNames(repoUrl: String): List<String> {
        val repo = Net.getJson(repoUrl) ?: return emptyList()
        val lists = repo.optJSONArray("pluginLists") ?: return emptyList()
        val names = LinkedHashSet<String>()
        for (i in 0 until lists.length()) {
            val listUrl = lists.optString(i).takeIf { it.startsWith("http") } ?: continue
            val plugins = Net.getArray(listUrl)
                ?: Net.getJson(listUrl)?.optJSONArray("plugins")
                ?: Net.getJson(listUrl)?.optJSONArray("pluginLists")
                ?: continue
            for (j in 0 until plugins.length()) {
                val obj = plugins.opt(j) as? JSONObject ?: continue
                val name = obj.optString("name").ifBlank { obj.optString("internalName") }
                if (name.isNotBlank()) names += name
            }
        }
        return names.toList()
    }
}

/** Aggregated, in-memory view of the configured upstream Stremio add-ons. */
internal class Aggregator {

    @Volatile private var cached: Data? = null
    @Volatile private var lastLoadAt = 0L
    /** Short aggregated id -> original add-on meta id, so catalog JSON stays small. */
    private val idMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    data class Meta(val id: String, val name: String, val poster: String?, val type: String)
    data class Data(
        val tv: List<Meta>,
        val movies: List<Meta>,
        val upstreams: List<String>,
        val pluginNames: Set<String>,
        val builtAt: Long,
    )

    fun data(context: Context, force: Boolean = false): Data {
        val now = System.currentTimeMillis()
        val current = cached
        if (!force && current != null && now - lastLoadAt < CACHE_MS) return current

        val upstreams = Prefs.upstreams(context)
        val plugins = Prefs.repos(context).flatMap(::pluginNamesViaRepo).toSet()
        val tv = LinkedHashMap<String, Meta>()
        val movies = LinkedHashMap<String, Meta>()

        upstreams.forEachIndexed { index, base ->
            val normalized = normalize(base) ?: return@forEachIndexed
            val manifest = Net.getJson("$normalized/manifest.json") ?: return@forEachIndexed
            val catalogs = manifest.optJSONArray("catalogs") ?: return@forEachIndexed
            for (i in 0 until catalogs.length()) {
                val catalog = catalogs.optJSONObject(i) ?: continue
                val type = when (catalog.optString("type").lowercase()) {
                    "tv", "live" -> "tv"
                    "movie", "movies" -> "movie"
                    else -> continue
                }
                val target = if (type == "tv") tv else movies
                val catalogId = catalog.optString("id")
                val json = Net.getJson("$normalized/catalog/$type/${encode(catalogId)}.json") ?: continue
                val metas = json.optJSONArray("metas") ?: continue
                for (j in 0 until metas.length()) {
                    val meta = metas.optJSONObject(j) ?: continue
                    val originalId = meta.optString("id")
                    if (originalId.isBlank()) continue
                    val id = "s$index:${originalId.hashCode().toUInt().toString(16)}"
                    idMap[id] = originalId
                    val name = meta.optString("name").ifBlank { originalId }
                    // When CloudStream repos are configured, keep only catalogs that look
                    // related to the installed plugin set. Without repos, keep everything.
                    if (plugins.isNotEmpty() && !nameMatches(name, catalog.optString("name"), plugins)) continue
                    val poster = meta.optString("poster").takeIf { it.startsWith("http") }
                    target.putIfAbsent(id, Meta(id, name, poster, type))
                }
            }
        }

        val result = Data(
            tv = tv.values.toList(),
            movies = movies.values.toList(),
            upstreams = upstreams,
            pluginNames = plugins,
            builtAt = now,
        )
        cached = result
        lastLoadAt = now
        return result
    }

    /** Streams for one meta, merged from the upstream that produced it. */
    fun streams(type: String, prefixedId: String): JSONArray {
        val result = JSONArray()
        val separator = prefixedId.indexOf(':')
        if (!prefixedId.startsWith("s") || separator < 1) return result
        val index = prefixedId.substring(1, separator).toIntOrNull() ?: return result
        // Legacy ids (s<index>:<originalId>) still resolve after an upgrade.
        val originalId = idMap[prefixedId] ?: prefixedId.substring(separator + 1)
        val base = normalize(dataStaticUpstream(index)) ?: return result
        val json = Net.getJson("$base/stream/$type/${encode(originalId)}.json") ?: return result
        val streams = json.optJSONArray("streams") ?: return result
        val seen = HashSet<String>()
        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue
            val url = stream.optString("url")
            if (!url.startsWith("http") || !seen.add(url)) continue
            result.put(stream)
        }
        return result
    }

    @Volatile private var staticUpstreams: List<String> = emptyList()
    fun setUpstreamsForStreams(list: List<String>) { staticUpstreams = list }
    private fun dataStaticUpstream(index: Int): String = staticUpstreams.getOrNull(index).orEmpty()

    private fun pluginNamesViaRepo(url: String): List<String> = RepoReader.pluginNames(url)

    private fun nameMatches(name: String, catalogName: String, plugins: Set<String>): Boolean {
        val compact = "$name $catalogName".lowercase().replace(Regex("[^a-z0-9]"), "")
        return plugins.any { plugin ->
            val normalized = plugin.lowercase().removeSuffix("provider")
                .replace(Regex("[^a-z0-9]"), "")
            normalized.length >= 4 && compact.contains(normalized)
        }
    }

    private fun normalize(raw: String): String? {
        var value = raw.trim().trimEnd('/')
        if (value.isEmpty()) return null
        if (!value.contains("://")) value = "http://$value"
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) return null
        if (value.endsWith("/configure", true)) {
            value = value.dropLast("/configure".length).trimEnd('/')
        }
        return if (value.endsWith("/manifest.json", true)) {
            value.dropLast("/manifest.json".length).trimEnd('/')
        } else value
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        private val CACHE_MS = TimeUnit.MINUTES.toMillis(10)
    }
}
