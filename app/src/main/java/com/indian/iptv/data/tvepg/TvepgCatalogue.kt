package com.indian.iptv.data.tvepg

import android.content.Context
import com.indian.iptv.data.Channel

/**
 * Bundled channel-metadata map generated from mitthu786/tvepg (JioTV + XMLTV channel ids).
 *
 * The upstream repository publishes guide data and logos, but no public stream URLs, so we
 * only use it to enrich channels we already have: an M3U channel whose name matches gets the
 * tvepg channel id (used as the EPG key) and, when the playlist has no logo, the tvepg logo.
 * No new/unplayable entries are added to the catalogue.
 *
 * The map is a small TSV asset so it works on both modules without pulling in a JSON
 * serializer on the mobile side.
 */
object TvepgCatalogue {

    data class Match(val epgId: String, val logo: String?)

    @Volatile
    private var loaded = false
    @Volatile
    private var byName: Map<String, Match> = emptyMap()

    /** Called once from IptvApp.onCreate; cheap (~100 KB), and failure-tolerant. */
    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            byName = runCatching {
                context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
            }.getOrElse { emptyMap() }
            loaded = true
        }
    }

    /** Testable pure parser for `key<TAB>tvg-id<TAB>logo` lines. */
    internal fun parse(tsv: String): Map<String, Match> {
        val result = HashMap<String, Match>()
        for (line in tsv.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts.size < 2) continue
            val key = parts[0].trim()
            val id = parts[1].trim()
            if (key.isEmpty() || id.isEmpty()) continue
            val logo = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
            if (!result.containsKey(key)) result[key] = Match(id, logo)
        }
        return result
    }

    /**
     * Normalises a channel name to the key used in the TSV. Kept in sync with the generator
     * (`gen_tvepg_map.py`): drop a trailing "(576p)"-style qualifier, drop quality words
     * anywhere in the name, then keep only letters/digits.
     */
    fun normalize(name: String): String {
        var s = TRAILING_QUALIFIER.replace(name.trim(), "")
        s = QUALITY_WORD.replace(s, "")
        return s.filter { it.isLetterOrDigit() }.lowercase()
    }

    fun find(name: String): Match? = byName[normalize(name)]

    /** Enriches an existing playlist channel without changing its stable id or stream URL. */
    fun enrich(channel: Channel): Channel = enrichWith(channel, byName)

    internal fun enrichWith(channel: Channel, map: Map<String, Match>): Channel {
        val match = map[normalize(channel.name)] ?: return channel
        return channel.copy(
            epgChannelId = match.epgId,
            logoUrl = channel.logoUrl?.takeIf { it.isNotBlank() } ?: match.logo,
        )
    }

    private const val ASSET_NAME = "tvepg_channels.tsv"
    private val TRAILING_QUALIFIER = Regex("""\s*\([^)]*\)\s*$""")
    private val QUALITY_WORD = Regex("""\b(full\s*hd|ultra\s*hd|4k|hd|sd|hevc)\b""", RegexOption.IGNORE_CASE)
}
