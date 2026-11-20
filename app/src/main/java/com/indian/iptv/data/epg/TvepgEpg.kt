package com.indian.iptv.data.epg

import android.util.Log
import com.indian.iptv.data.db.ProgrammeEntity
import com.indian.iptv.data.remote.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.BufferedInputStream
import java.util.zip.GZIPInputStream

/**
 * Fetches the XMLTV feeds published by mitthu786/tvepg and keeps only programmes for the
 * channel ids referenced by the current catalogue (see TvepgCatalogue).
 *
 * Three feeds are merged because the ids come from different providers: the main feed has
 * JioTV numeric ids, jioepg has a few extra JioTV entries, and tsepg carries the `ts###`
 * Tata Play ids used by some mappings.
 */
object TvepgEpg {

    private val FEEDS = listOf(
        "https://avkb.short.gy/epg.xml.gz",
        "https://avkb.short.gy/jioepg.xml.gz",
        "https://avkb.short.gy/tsepg.xml.gz",
    )

    suspend fun fetch(epgKeys: Set<String>): List<ProgrammeEntity> = withContext(Dispatchers.IO) {
        if (epgKeys.isEmpty()) return@withContext emptyList()

        // LinkedHashMap so a programme from an earlier feed wins over a duplicate later on.
        val merged = LinkedHashMap<Pair<String, Long>, ProgrammeEntity>()
        for (url in FEEDS) {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build()
                HttpClient.okHttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                    val body = response.body ?: return@use emptyList()
                    body.byteStream().use { raw ->
                        val buffered = BufferedInputStream(raw)
                        buffered.mark(2)
                        val b1 = buffered.read()
                        val b2 = buffered.read()
                        buffered.reset()
                        val source = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered) else buffered
                        XmltvParser.parse(source) { key -> key in epgKeys }
                    }
                }
            }
                .onFailure { Log.w(TAG, "tvepg feed failed: $url", it) }
                .getOrNull()
                ?.forEach { programme ->
                    val key = programme.channelKey to programme.startMs
                    if (!merged.containsKey(key)) merged[key] = programme
                }
        }
        merged.values.toList()
    }

    private const val TAG = "TvepgEpg"
    private const val USER_AGENT = "IndianIPTV/1.0 (Android TV)"
}
