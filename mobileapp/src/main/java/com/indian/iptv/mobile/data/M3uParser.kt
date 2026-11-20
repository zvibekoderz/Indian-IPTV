package com.indian.iptv.mobile.data

/**
 * One channel parsed out of an M3U playlist.
 */
data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val category: String?,
    val tvgId: String?,
    val userAgent: String?,
    val referrer: String?,
)

/**
 * Fresh, minimal M3U/M3U8 playlist parser for the mobile app.
 *
 * Supports:
 *  - `#EXTINF` attributes (tvg-id, tvg-logo, group-title) and the display name
 *  - `#EXTVLCOPT:http-user-agent=` / `user-agent=` / `referrer=` per-entry headers
 *  - absolute and relative entry URLs
 */
object M3uParser {

    private val attrRegex = Regex("""([\w-]+)\s*=\s*"([^"]*)""")

    fun parse(text: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        var attrs = mutableMapOf<String, String>()
        var name = ""
        var userAgent: String? = null
        var referrer: String? = null

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    attrs = LinkedHashMap()
                    userAgent = null
                    referrer = null

                    // Pull out the quoted attributes first, then whatever is left
                    // after the first remaining comma is the display name. This
                    // keeps commas *inside* quoted values and inside channel
                    // names intact.
                    for (match in attrRegex.findAll(line)) {
                        attrs[match.groupValues[1].lowercase()] = match.groupValues[2]
                    }
                    var remainder = attrRegex.replace(line, "")
                    val comma = remainder.indexOf(',')
                    name = if (comma >= 0) remainder.substring(comma + 1).trim() else ""
                }

                line.startsWith("#EXTVLCOPT") -> {
                    val value = line.substringAfter(':', "").trim()
                    when {
                        value.startsWith("http-user-agent=", ignoreCase = true) ->
                            userAgent = value.substringAfter('=')
                        value.startsWith("user-agent=", ignoreCase = true) ->
                            userAgent = value.substringAfter('=')
                        value.startsWith("referrer=", ignoreCase = true) ->
                            referrer = value.substringAfter('=')
                    }
                }

                line.isEmpty() || line.startsWith("#") -> Unit // ignore everything else

                else -> {
                    // First non-comment, non-blank line after #EXTINF is the URL.
                    val url = resolveUrl(line)
                    if (name.isNotEmpty() && url != null) {
                        channels += Channel(
                            name = name,
                            url = url,
                            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                            category = attrs["group-title"]?.takeIf { it.isNotBlank() },
                            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                            userAgent = userAgent?.takeIf { it.isNotBlank() },
                            referrer = referrer?.takeIf { it.isNotBlank() },
                        )
                    }
                    name = ""
                    attrs = LinkedHashMap()
                    userAgent = null
                    referrer = null
                }
            }
        }
        return channels
    }

    private fun resolveUrl(line: String): String? = when {
        line.startsWith("http://", true) || line.startsWith("https://", true) ||
            line.startsWith("rtmp://", true) || line.startsWith("rtsp://", true) -> line
        else -> null // relative-path entries and junk lines are ignored in v1
    }
}
