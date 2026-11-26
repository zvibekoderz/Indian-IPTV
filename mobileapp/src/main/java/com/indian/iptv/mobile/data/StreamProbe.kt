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
 * probe the first few media entries; any one answering keeps the channel.
 */
object StreamProbe {

    // Dead CDN links are the norm on public add-ons. Keep the per-request
    // ceiling low so one unreachable host can't stall the whole search; the
    // caller also enforces an overall time budget.
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
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

                    // Providers often publish several variants. A broken first entry
                    // must not condemn an otherwise playable channel.
                    val entries = body.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .take(MAX_PLAYLIST_ENTRIES)
                        .mapNotNull { entry ->
                            runCatching {
                                URI.create(entry).takeIf { it.isAbsolute }?.toString()
                                    ?: URI.create(url).resolve(entry).toString()
                            }.getOrNull()
                        }
                        .toList()
                    if (entries.isEmpty()) return@withContext false

                    entries.any { absolute ->
                        runCatching {
                            client.newCall(requestFor(absolute, headers)).execute().use { segment ->
                                isSuccess(segment.code)
                            }
                        }.getOrDefault(false)
                    }
                }
            }.getOrDefault(false)
        }

    private fun requestFor(url: String, headers: Map<String, String>): Request =
        Request.Builder().url(url).apply {
            // Probe with exactly the same headers the player will send, otherwise
            // signed/hotlink-protected streams look dead here and playable in theory.
            headers.forEach { (name, value) -> header(name, value) }
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                header("User-Agent", DEFAULT_USER_AGENT)
            }
            header("Range", "bytes=0-0")
        }.build()

    /** 416 means the resource exists but is smaller than the requested range. */
    private fun isSuccess(code: Int): Boolean = code in 200..399 || code == 416

    private const val MAX_PLAYLIST_ENTRIES = 3

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
}
