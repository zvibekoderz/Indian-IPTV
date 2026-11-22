package com.indian.iptv.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Minimal reader for the Stremio addon HTTP protocol, written from scratch
 * against the public manifest/catalog/stream REST contract. No code from
 * CNCVerse Bridge is used — its license forbids copying its codebase.
 *
 * Uses org.json, which ships with Android, so the mobile module keeps its
 * "no serialization library" policy.
 */
class StremioAddon private constructor(private val root: String) {

    data class Catalog(val type: String, val id: String, val name: String)

    data class Meta(
        val id: String,
        val type: String,
        val name: String,
        val poster: String?,
        val background: String?,
        val description: String?,
        val releaseInfo: String?,
    )

    data class Stream(val url: String, val label: String, val headers: Map<String, String>)

    data class Manifest(val name: String, val catalogs: List<Catalog>)

    class AddonException(message: String) : Exception(message)

    suspend fun manifest(): Manifest = parseManifest(getJson("$root/manifest.json"))

    suspend fun catalog(type: String, id: String, search: String? = null): List<Meta> {
        // Stremio catalog extras live in the path: /catalog/{type}/{id}/search={term}.json
        val extra = search?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "/search=${path(it)}" }
            .orEmpty()
        return parseCatalog(getJson("$root/catalog/${path(type)}/${path(id)}$extra.json"), type)
    }

    suspend fun streams(type: String, id: String): List<Stream> =
        parseStreams(getJson("$root/stream/${path(type)}/${path(id)}.json"))

    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        // Let OkHttp supply its default headers. An explicit Accept header was
        // removed while investigating empty Bridge catalogues; it was not the
        // cause, but the plain request is simpler and works for every add-on.
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AddonException("HTTP ${response.code} from add-on")
            val body = response.body?.string().orEmpty()
            runCatching { JSONObject(body) }
                .getOrElse { throw AddonException("Add-on returned invalid JSON") }
        }
    }

    companion object {
        fun normalize(raw: String?): String? {
            var value = raw?.trim()?.trimEnd('/') ?: return null
            if (value.isEmpty()) return null
            if (!value.contains("://")) value = "http://$value"
            if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) return null
            if (value.endsWith("/manifest.json", true)) value = value.dropLast("/manifest.json".length)
            return value.trimEnd('/').takeIf { it.isNotEmpty() }
        }

        fun create(raw: String?): StremioAddon? = normalize(raw)?.let(::StremioAddon)

        internal fun parseManifest(json: JSONObject): Manifest {
            val catalogs = json.optJSONArray("catalogs").orEmpty().mapNotNull { raw ->
                val obj = raw as? JSONObject ?: return@mapNotNull null
                val type = obj.optString("type").trim()
                val id = obj.optString("id").trim()
                if (type.isEmpty() || id.isEmpty()) return@mapNotNull null
                Catalog(type, id, obj.optString("name").trim().ifEmpty { id })
            }
            return Manifest(json.optString("name").trim().ifEmpty { "Add-on" }, catalogs)
        }

        internal fun parseCatalog(json: JSONObject, fallbackType: String): List<Meta> =
            json.optJSONArray("metas").orEmpty().mapNotNull { raw ->
                val obj = raw as? JSONObject ?: return@mapNotNull null
                val metaId = obj.optString("id").trim()
                if (metaId.isEmpty()) return@mapNotNull null
                Meta(
                    id = metaId,
                    type = obj.optString("type").trim().ifEmpty { fallbackType },
                    name = obj.optString("name").trim().ifEmpty { metaId },
                    poster = obj.optString("poster").trim().takeIf { it.startsWith("http", true) },
                    background = obj.optString("background").trim().takeIf { it.startsWith("http", true) },
                    description = obj.optString("description").trim().ifEmpty { null },
                    releaseInfo = obj.optString("releaseInfo").trim().ifEmpty { null },
                )
            }

        internal fun parseStreams(json: JSONObject): List<Stream> =
            json.optJSONArray("streams").orEmpty().mapNotNull { raw ->
                val obj = raw as? JSONObject ?: return@mapNotNull null
                val url = obj.optString("url").trim()
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    return@mapNotNull null // torrent/yt streams are not playable here
                }
                val headers = mutableMapOf<String, String>()
                obj.optJSONObject("behaviorHints")?.optJSONObject("headers")?.copyInto(headers)
                obj.optJSONObject("headers")?.copyInto(headers)
                val label = listOf(obj.optString("name"), obj.optString("title"), obj.optString("description"))
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?: url.substringAfterLast('/').takeIf { it.isNotEmpty() }
                    ?: "Play"
                Stream(url, label, headers)
            }.distinctBy { it.url }

        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        private fun path(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private fun JSONArray?.orEmpty(): List<Any?> =
            if (this == null) emptyList() else (0 until length()).map { opt(it) }

        private fun JSONObject.copyInto(target: MutableMap<String, String>) {
            keys().forEach { key ->
                val value = opt(key)?.toString()?.trim()
                if (!value.isNullOrEmpty()) target[key] = value
            }
        }
    }
}
