package com.indian.iptv.mobile.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * XMLTV (EPG) guide support for Now/Next programme info.
 *
 * Streams a (possibly gzip-compressed) XMLTV file and keeps only the current
 * and next programme per channel, so memory stays small even for
 * multi-megabyte guides. The guide URL is set by the user in Settings —
 * any provider or community XMLTV guide works.
 */
object Epg {

    data class NowNext(val now: String?, val next: String?)

    private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

    /** Backwards-compatible single-feed overload. */
    suspend fun load(context: Context, url: String, forceRefresh: Boolean): Map<String, NowNext>? =
        load(context, listOf(url), forceRefresh)

    /**
     * Downloads and caches each XMLTV guide in [urls], then merges the lookups. The tvepg
     * default is several feeds (JioTV ids + Tata Play `ts###` ids), so a single channel can
     * get a guide from any of them. Returns null only when no feed could be parsed.
     * Each feed is cached at most [MAX_AGE_MS].
     */
    suspend fun load(context: Context, urls: List<String>, forceRefresh: Boolean): Map<String, NowNext>? =
        withContext(Dispatchers.IO) {
            val targets = urls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (targets.isEmpty()) return@withContext emptyMap()

            val client = OkHttpClient.Builder()
                .dns(DohDns)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build()
            val now = System.currentTimeMillis()
            val merged = HashMap<String, NowNext>()

            for (url in targets) {
                val cache = File(context.filesDir, cacheName(url))
                val fresh = cache.exists() && cache.length() > 0 && now - cache.lastModified() < MAX_AGE_MS
                if (!fresh || forceRefresh) {
                    val tmp = File(context.filesDir, "${cache.name}.tmp")
                    try {
                        client.newCall(
                            Request.Builder()
                                .url(url)
                                .header("User-Agent", PlaylistFetcher.DEFAULT_USER_AGENT)
                                .build()
                        ).execute().use { response ->
                            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                            response.body!!.byteStream().use { input ->
                                tmp.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        if (tmp.length() == 0L) throw IllegalStateException("empty guide")
                        if (!tmp.renameTo(cache)) {
                            cache.delete()
                            tmp.renameTo(cache)
                        }
                    } catch (e: Exception) {
                        tmp.delete()
                        // A stale cache, if we have one, is still usable below.
                    }
                }
                if (cache.exists()) {
                    runCatching { merged.putAll(parse(cache.inputStream(), now)) }
                }
            }
            if (merged.isEmpty()) null else merged
        }

    /**
     * Streams XMLTV from [source] and returns Now/Next per channel, keyed by
     * lowercased XMLTV channel id AND by normalized display name.
     */
    fun parse(source: InputStream, nowMillis: Long): Map<String, NowNext> {
        val buffered = BufferedInputStream(source)
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(maybeGunzip(buffered), null)

        val nameToId = HashMap<String, String>()         // normalized name -> xmltv id
        val now = HashMap<String, Pair<Long, String>>()  // id -> (start, title)
        val next = HashMap<String, Pair<Long, String>>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        val id = parser.getAttributeValue(null, "id").orEmpty()
                        while (true) {
                            val e = parser.next()
                            if (e == XmlPullParser.END_TAG && parser.name == "channel") break
                            if (e == XmlPullParser.START_TAG && parser.name == "display-name") {
                                val name = parser.nextText()
                                if (name.isNotBlank()) nameToId[normalizeName(name)] = id
                            }
                        }
                    }

                    "programme" -> {
                        val id = parser.getAttributeValue(null, "channel")?.lowercase().orEmpty()
                        val start = parseTime(parser.getAttributeValue(null, "start"))
                        val stop = parseTime(parser.getAttributeValue(null, "stop"))
                        var title: String? = null
                        while (true) {
                            val e = parser.next()
                            if (e == XmlPullParser.END_TAG && parser.name == "programme") break
                            if (e == XmlPullParser.START_TAG && parser.name == "title") {
                                title = parser.nextText()
                            }
                        }
                        if (id.isNotEmpty() && title != null && start != null && stop != null) {
                            when {
                                stop <= nowMillis -> Unit // already over
                                start <= nowMillis -> {
                                    val existing = now[id]
                                    if (existing == null || existing.first < start) {
                                        now[id] = start to title
                                    }
                                }
                                else -> {
                                    val existing = next[id]
                                    if (existing == null || existing.first > start) {
                                        next[id] = start to title
                                    }
                                }
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        val result = HashMap<String, NowNext>()
        for (id in now.keys + next.keys) {
            result[id] = NowNext(now[id]?.second, next[id]?.second)
        }
        // Also index by normalized display name for playlists without tvg-id.
        for ((name, id) in nameToId) {
            result[id.lowercase()]?.let { value -> result[name] = value }
        }
        return result
    }

    /** Looks up Now/Next for a channel: exact tvg-id first, then name. */
    fun lookup(
        map: Map<String, NowNext>,
        tvgId: String?,
        channelName: String?,
    ): NowNext? {
        tvgId?.lowercase()?.let { id -> map[id]?.let { return it } }
        channelName?.let { name -> map[normalizeName(name)]?.let { return it } }
        return null
    }

    /** Same normalisation as the bundled channel map, so name fallback matches too. */
    fun normalizeName(name: String): String = TvepgCatalogue.normalize(name)

    private fun cacheName(url: String): String =
        "epg-" + MessageDigest.getInstance("MD5")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) } + ".xmltv"

    private fun maybeGunzip(stream: BufferedInputStream): InputStream {
        stream.mark(2)
        val b1 = stream.read()
        val b2 = stream.read()
        stream.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(stream) else stream
    }

    /** Parses "yyyyMMddHHmmss Z" (XMLTV) into UTC epoch millis. */
    private fun parseTime(value: String?): Long? {
        if (value == null || value.length < 14) return null
        return try {
            val format = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val epoch = format.parse(value.substring(0, 14))?.time ?: return null
            val offsetPart = value.substringAfter(' ', "").trim()
            val offsetMillis = if (offsetPart.length >= 5 &&
                (offsetPart[0] == '+' || offsetPart[0] == '-')
            ) {
                val sign = if (offsetPart[0] == '-') -1L else 1L
                val hours = offsetPart.substring(1, 3).toLongOrNull() ?: 0L
                val minutes = offsetPart.substring(3, 5).toLongOrNull() ?: 0L
                sign * (hours * 3600 + minutes * 60) * 1000
            } else {
                0L
            }
            epoch - offsetMillis
        } catch (e: Exception) {
            null
        }
    }
}
