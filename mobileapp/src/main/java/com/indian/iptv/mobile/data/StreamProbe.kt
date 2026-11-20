package com.indian.iptv.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Cheap reachability check for an add-on stream URL.
 *
 * The public Stremio add-ons we talk to often hand out signed CDN links that
 * expire quickly. A GET with a one-byte Range is enough to tell whether the
 * link still works, without pulling down any video. Results are cached by the
 * caller so a search keystroke never re-probes the same URL.
 */
object StreamProbe {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun isPlayable(url: String, headers: Map<String, String> = emptyMap()): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-0")
                    .header("User-Agent", headers.value("User-Agent") ?: DEFAULT_USER_AGENT)
                    .apply { headers.value("Referer")?.let { header("Referer", it) } }
                    .build()
                client.newCall(request).execute().use { response ->
                    // 416 just means the resource is smaller than the requested range —
                    // it still proves the URL is alive.
                    response.code in 200..399 || response.code == 416
                }
            }.getOrDefault(false)
        }

    private fun Map<String, String>.value(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
}
