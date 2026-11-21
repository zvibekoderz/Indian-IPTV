package com.indian.iptv.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Reachability check for an add-on stream URL.
 *
 * A 200 on an HLS master playlist is not enough — providers routinely serve a
 * valid playlist whose segments 403/404. So for playlists we also resolve and
 * probe the first media entry. A URL only counts as working when both answer.
 */
object StreamProbe {

    // Dead CDN links are the norm on public add-ons. Keep the per-request
    // ceiling low so one unreachable host can't stall the whole search; the
    // caller also enforces an overall time budget.
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun isPlayable(url: String, headers: Map<String, String> = emptyMap()): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = requestFor(url, headers)
                client.newCall(request).execute().use { response ->
                    if (!isSuccess(response.code)) return@withContext false
                    val isPlaylist = response.header("Content-Type").orEmpty()
                        .contains("mpegurl", true) ||
                        url.substringBefore('?').endsWith(".m3u8", true) ||
                        url.substringBefore('?').endsWith(".mpd", true)
                    if (!isPlaylist) return@withContext true

                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) return@withContext false
                    if (!body.contains("#EXTM3U", true) && !body.contains("<MPD", true)) {
                        return@withContext false
                    }

                    val firstEntry = body.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .firstOrNull()
                        ?: return@withContext false

                    val absolute = runCatching {
                        URI.create(firstEntry).takeIf { it.isAbsolute }?.toString()
                            ?: URI.create(url).resolve(firstEntry).toString()
                    }.getOrNull() ?: return@withContext false

                    client.newCall(requestFor(absolute, headers)).execute().use { segment ->
                        isSuccess(segment.code)
                    }
                }
            }.getOrDefault(false)
        }

    private fun requestFor(url: String, headers: Map<String, String>): Request =
        Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("User-Agent", headers.value("User-Agent") ?: DEFAULT_USER_AGENT)
            .apply { headers.value("Referer")?.let { header("Referer", it) } }
            .build()

    /** 416 means the resource exists but is smaller than the requested range. */
    private fun isSuccess(code: Int): Boolean = code in 200..399 || code == 416

    private fun Map<String, String>.value(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
}
