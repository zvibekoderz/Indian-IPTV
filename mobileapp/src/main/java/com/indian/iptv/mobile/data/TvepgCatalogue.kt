package com.indian.iptv.mobile.data

import android.content.Context

/**
 * Bundled channel-metadata map generated from mitthu786/tvepg (JioTV + XMLTV channel ids).
 *
 * The upstream repo ships guide data and logos but no public streams, so this only enriches
 * channels already parsed from the user's M3U playlists: a name match supplies the tvepg
 * channel id (for EPG lookup) and, when the playlist has none, a logo. Nothing unplayable is
 * added to the list. The data is a small TSV asset to avoid adding a JSON dependency here.
 */
object TvepgCatalogue {

    data class Match(val epgId: String, val logo: String?)

    @Volatile
    private var loaded = false
    @Volatile
    private var byName: Map<String, Match> = emptyMap()

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
     * Normalises a channel name to the TSV key: drop a trailing "(576p)"-style qualifier,
     * drop quality words, keep only letters/digits. Also used by Epg so guide display names
     * and playlist channel names are indexed the same way.
     */
    fun normalize(name: String): String {
        var s = TRAILING_QUALIFIER.replace(name.trim(), "")
        s = QUALITY_WORD.replace(s, "")
        return s.filter { it.isLetterOrDigit() }.lowercase()
    }

    fun find(name: String): Match? = byName[normalize(name)]

    /** Keeps the playlist url/user-agent/referrer intact; only id + optional logo change. */
    fun enrich(channel: Channel): Channel = enrichWith(channel, byName)

    internal fun enrichWith(channel: Channel, map: Map<String, Match>): Channel {
        val match = map[normalize(channel.name)] ?: return channel
        return channel.copy(
            tvgId = match.epgId,
            logo = channel.logo?.takeIf { it.isNotBlank() } ?: match.logo,
        )
    }

    private const val ASSET_NAME = "tvepg_channels.tsv"
    private val TRAILING_QUALIFIER = Regex("""\s*\([^)]*\)\s*$""")
    private val QUALITY_WORD = Regex("""\b(full\s*hd|ultra\s*hd|4k|hd|sd|hevc)\b""", RegexOption.IGNORE_CASE)
}
