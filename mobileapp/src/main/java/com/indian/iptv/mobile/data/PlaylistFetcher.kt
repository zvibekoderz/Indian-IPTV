package com.indian.iptv.mobile.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads the M3U playlist and keeps a one-file cache in internal storage so
 * the channel list opens instantly (and offline) on subsequent launches.
 */
class PlaylistFetcher(context: Context) {

    private val cacheFile = File(context.filesDir, "playlist.m3u")

    private val client = OkHttpClient.Builder()
        .dns(DohDns) // DoH fallback for poisoned ISP resolvers
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        data class Success(val text: String, val fromCache: Boolean) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Returns the cached playlist if present and [forceRefresh] is false;
     * otherwise downloads [url] and (on success) refreshes the cache.
     */
    suspend fun fetch(url: String, forceRefresh: Boolean): Result = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cacheFile.takeIf { it.exists() && it.length() > 0 }?.let { file ->
                val cached = runCatching { file.readText() }.getOrNull()
                if (!cached.isNullOrBlank()) return@withContext Result.Success(cached, fromCache = true)
            }
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
                    return@withContext Result.Failure("HTTP ${resp.code} while fetching playlist")
                }
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext Result.Failure("Playlist is empty")
                }
                cacheFile.writeText(body)
                Result.Success(body, fromCache = false)
            }
        } catch (e: Exception) {
            // Network failed — fall back to a stale cache if we have one.
            val cached = runCatching { cacheFile.takeIf { it.exists() }?.readText() }.getOrNull()
            if (!cached.isNullOrBlank()) {
                Result.Success(cached, fromCache = true)
            } else {
                Result.Failure(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    companion object {
        const val DEFAULT_USER_AGENT = "IptvMobile/1.0 (Android)"
    }
}
