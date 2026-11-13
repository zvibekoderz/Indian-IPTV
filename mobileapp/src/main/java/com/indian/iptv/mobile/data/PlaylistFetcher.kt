package com.indian.iptv.mobile.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads one or more M3U playlists in parallel and merges the channels
 * (deduped by stream URL). Each playlist gets its own cache file in internal
 * storage so the channel list opens instantly (and offline) on subsequent
 * launches, and a playlist that fails to download falls back to its cache
 * without affecting the others.
 */
class PlaylistFetcher(context: Context) {

    private val cacheDir = File(context.filesDir, "playlists").apply { mkdirs() }

    private val client = OkHttpClient.Builder()
        .dns(DohDns) // DoH fallback for poisoned ISP resolvers
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Result(
        val channels: List<Channel>,
        /** Descriptions of the playlists that could not be downloaded at all. */
        val failures: List<String>,
    )

    private class Fetched(val url: String, val text: String?, val failure: String?)

    /**
     * Fetches every [url], parses and merges the results. With
     * [forceRefresh] = false, cached copies are used when present.
     */
    suspend fun fetch(urls: List<String>, forceRefresh: Boolean): Result {
        val fetched = coroutineScope {
            urls.map { url -> async { fetchOne(url, forceRefresh) } }.awaitAll()
        }

        val channels = mutableListOf<Channel>()
        val failures = mutableListOf<String>()
        for (f in fetched) {
            val text = f.text
            when {
                text != null -> channels += M3uParser.parse(text)
                f.failure != null -> failures += "${f.url}: ${f.failure}"
            }
        }
        return Result(channels.distinctBy { it.url }, failures)
    }

    private suspend fun fetchOne(url: String, forceRefresh: Boolean): Fetched =
        withContext(Dispatchers.IO) {
            val cacheFile = File(cacheDir, cacheName(url))

            if (!forceRefresh) {
                val cached = cacheFile.readOrNull()
                if (cached != null) return@withContext Fetched(url, cached, null)
            }

            try {
                val response = client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", DEFAULT_USER_AGENT)
                        .build()
                ).execute()

                response.use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext failedWithCacheFallback(
                            url, cacheFile, "HTTP ${resp.code}",
                        )
                    }
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) {
                        return@withContext failedWithCacheFallback(url, cacheFile, "playlist is empty")
                    }
                    cacheFile.writeText(body)
                    Fetched(url, body, null)
                }
            } catch (e: Exception) {
                failedWithCacheFallback(url, cacheFile, e.message ?: e.javaClass.simpleName)
            }
        }

    /** Network failed — fall back to a stale cache if we have one. */
    private fun failedWithCacheFallback(url: String, cacheFile: File, failure: String): Fetched {
        val cached = cacheFile.readOrNull()
        return if (cached != null) Fetched(url, cached, null) else Fetched(url, null, failure)
    }

    private fun File.readOrNull(): String? =
        runCatching { takeIf { exists() && length() > 0 }?.readText() }.getOrNull()

    private fun cacheName(url: String): String =
        MessageDigest.getInstance("MD5").digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) } + ".m3u"

    companion object {
        const val DEFAULT_USER_AGENT = "IptvMobile/1.0 (Android)"
    }
}
