package com.indian.iptv.data.m3u

import com.indian.iptv.data.Channel
import com.indian.iptv.data.ContentType
import com.indian.iptv.data.DisplayNames
import java.security.MessageDigest

/**
 * Minimal M3U/M3U8 extended-playlist parser.
 *
 * Supports the common shape:
 *   #EXTINF:-1 tvg-id="..." tvg-logo="..." group-title="..." tvg-language="hi" tvg-country="IN",Channel Name
 *   http://host/path
 *
 * Attributes are best-effort: unknown keys are ignored, missing keys become null.
 *
 * IDs are derived from content, not position, so inserting a channel upstream doesn't
 * shift every other channel's ID (which would orphan favourites and watch progress):
 *  - `tvg-id` when present; a repeated tvg-id (HD/SD variants) gets a `#2`, `#3` suffix
 *    so both rows survive the primary-key constraint.
 *  - otherwise stable SHA-256 hash of normalized name + group (first 16 chars).
 *
 * Channel numbers are assigned 1-based in playlist order for TV type, persisted so they
 * don't change between refreshes. Existing channels keep their number; new ones get max+1.
 */
object M3uParser {

    private val attrRegex = Regex("""([a-zA-Z0-9_-]+)="([^"]*)"""")
    private val HASH_LENGTH = 16

    fun parse(content: String): List<Channel> = parse(content.lineSequence())

    fun parse(lines: Sequence<String>): List<Channel> {
        val channels = mutableListOf<Channel>()
        val usedIds = HashMap<String, Int>()
        var pending: PendingEntry? = null
        var groupFromExtGrp: String? = null
        var tvChannelIndex = 0 // 1-based index for TV channels only

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U") -> continue
                line.startsWith("#EXTINF") -> {
                    pending = parseExtInf(line)
                    groupFromExtGrp = null
                }
                line.startsWith("#EXTGRP:") -> groupFromExtGrp = line.substringAfter(':').trim().ifBlank { null }
                line.startsWith("#") -> continue
                else -> {
                    val entry = pending ?: continue
                    val withGroup = if (entry.group == null && groupFromExtGrp != null) {
                        entry.copy(group = groupFromExtGrp)
                    } else entry
                    // Assign channel number for TV type
                    val channelNumber = if (typeFor(line) == ContentType.TV) {
                        tvChannelIndex++
                        tvChannelIndex
                    } else null
                    channels += withGroup.toChannel(line, usedIds, channelNumber)
                    pending = null
                    groupFromExtGrp = null
                }
            }
        }
        return channels
    }

    private fun parseExtInf(line: String): PendingEntry {
        val commaIdx = indexOfNameSeparator(line)
        val header = if (commaIdx >= 0) line.substring(0, commaIdx) else line
        val name = if (commaIdx >= 0) line.substring(commaIdx + 1).trim() else ""
        val attrs = attrRegex.findAll(header).associate { it.groupValues[1] to it.groupValues[2] }
        return PendingEntry(
            name = name,
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
            group = attrs["group-title"]?.takeIf { it.isNotBlank() },
            language = attrs["tvg-language"]?.takeIf { it.isNotBlank() },
            country = attrs["tvg-country"]?.takeIf { it.isNotBlank() },
        )
    }

    /** First comma outside a quoted attribute value — `group-title="News, Sports"` is legal. */
    private fun indexOfNameSeparator(line: String): Int {
        var inQuotes = false
        line.forEachIndexed { i, c ->
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> return i
            }
        }
        return -1
    }

    private fun typeFor(url: String): ContentType {
        return when {
            url.contains("/movie/") -> ContentType.MOVIE
            url.contains("/series/") -> ContentType.SERIES
            else -> ContentType.TV
        }
    }

    private data class PendingEntry(
        val name: String,
        val tvgId: String?,
        val logo: String?,
        val group: String?,
        val language: String?,
        val country: String?,
    ) {
        fun toChannel(url: String, usedIds: HashMap<String, Int>, channelNumber: Int?): Channel {
            val base = tvgId ?: stableIdFromNameAndGroup(name, group)
            val seen = usedIds.merge(base, 1, Int::plus) ?: 1
            val id = if (seen == 1) base else "$base#$seen"

            // Clean up display name: strip common suffixes like "(720p)", "[Geo-blocked]", "[Not 24/7]"
            val cleanName = cleanChannelName(name)

            // Parse group-title into multiple genres (split by ';'), PRESERVE ORDER
            val genres = group?.split(';')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.map { DisplayNames.clean(it) }
                ?: emptyList()

            // Determine languages: tvg-language > tvg-country > infer from RAW name (before cleaning)
            val languages = when {
                language != null -> parseLanguages(language)
                country != null -> countryToLanguages(country)
                else -> inferLanguagesFromName(name)
            }

            // HD detection from name suffixes (only resolution terms, not codecs)
            val isHd = detectHd(name)

            return Channel(
                id = id,
                name = cleanName,
                logoUrl = logo,
                groupTitle = group,
                streamUrl = url,
                epgChannelId = tvgId,
                type = typeFor(url),
                archiveDays = 0,
                languages = languages,
                genres = genres,
                number = channelNumber,
                isHd = isHd,
            )
        }
    }

    /** Generate a stable ID from normalized name + group using SHA-256 (first 16 hex chars). */
    private fun stableIdFromNameAndGroup(name: String, group: String?): String {
        val normalizedName = normalizeForId(name)
        val normalizedGroup = group?.let { normalizeForId(it) } ?: ""
        val input = "$normalizedName|$normalizedGroup"
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            digest.take(HASH_LENGTH).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // Fallback: simple hash if SHA-256 unavailable
            "${normalizedName.hashCode().toString(16)}#${normalizedGroup.hashCode().toString(16)}"
        }
    }

    /** Normalize string for stable ID: lowercase, remove non-alphanumeric except spaces, collapse spaces. */
    private fun normalizeForId(s: String): String {
        return s.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Parse comma-separated language codes (e.g. "hi,en") into a list. */
    private fun parseLanguages(raw: String): List<String> =
        raw.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()

    /** Map ISO 3166-1 alpha-2 country code to likely language(s). */
    private fun countryToLanguages(country: String): List<String> = when (country.uppercase()) {
        "IN" -> listOf("hi", "en") // India: Hindi, English
        "GB", "US", "AU", "CA", "NZ", "IE" -> listOf("en")
        "PK" -> listOf("ur", "en")
        "BD" -> listOf("bn")
        "LK" -> listOf("si", "ta")
        "NP" -> listOf("ne")
        "BT" -> listOf("dz")
        "MV" -> listOf("dv")
        "AF" -> listOf("ps", "fa")
        else -> listOf(country.lowercase()) // Fallback: use country code as language hint
    }

    /**
     * Infer languages from channel name using keyword mapping for Indian channels.
     * Runs on RAW name (before cleaning). Uses word-boundary matching.
     * Explicit language words (in parentheses) beat brand names.
     * Returns a list of ISO 639 language codes.
     */
    private fun inferLanguagesFromName(name: String): List<String> {
        val lower = name.lowercase()
        val found = mutableSetOf<String>()

        // First: check for explicit language in parentheses like "(Hindi)", "(Tamil)", etc.
        val explicitLangRegex = Regex("""\((hi|hindi|ta|tamil|te|telugu|kn|kannada|ml|malayalam|bn|bengali|mr|marathi|gu|gujarati|pa|punjabi|ur|urdu|or|odia|as|assamese|bho|bhojpuri|en|english)\)""", RegexOption.IGNORE_CASE)
        val explicitMatches = explicitLangRegex.findAll(lower).map { it.groupValues[1] }.toSet()
        val explicitLangMap = mapOf(
            "hi" to "hi", "hindi" to "hi",
            "ta" to "ta", "tamil" to "ta",
            "te" to "te", "telugu" to "te",
            "kn" to "kn", "kannada" to "kn",
            "ml" to "ml", "malayalam" to "ml",
            "bn" to "bn", "bengali" to "bn",
            "mr" to "mr", "marathi" to "mr",
            "gu" to "gu", "gujarati" to "gu",
            "pa" to "pa", "punjabi" to "pa",
            "ur" to "ur", "urdu" to "ur",
            "or" to "or", "odia" to "or",
            "as" to "as", "assamese" to "as",
            "bho" to "bho", "bhojpuri" to "bho",
            "en" to "en", "english" to "en",
        )
        explicitMatches.forEach { code -> explicitLangMap[code]?.let { found += it } }

        // Word-boundary keyword matching for brand/channel names
        // Hindi / North Indian
        val hindiKeywords = listOf(
            "zee", "sony", "star", "colors", "&tv", "dd", "doordarshan", "aaj tak", "abp",
            "india tv", "news 18", "republic", "times now", "cnn news18", "tv9", "etv",
            "sahara", "bharat", "sanskar", "aastha", "saraswati", "divya", "vedic",
            "isht", "peace", "satya", "disha", "sadna", "jagran", "prag", "news nation",
            "sudarshan", "paras", "khabar", "fast news", "news world", "india news",
            "p7", "news x", "news 24", "ntv", "t news", "tv5", "10 tv", "sakshi", "abn",
            "hmtv", "v6", "studio n", "tollywood", "etv telugu", "gemini", "maa",
            "star maa", "zee telugu", "etv andhra", "abp majha", "zee marathi",
            "star pravah", "colors marathi", "tv9 marathi", "saam tv", "jai maharashtra",
            "dd sahyadri", "mi marathi", "fakt marathi", "star suvarna", "colors kannada",
            "zee kannada", "udaya", "kasthuri", "tv9 kannada", "suvarna news", "dd chandana",
            "star vijay", "zee tamil", "sun tv", "vijay tv", "colors tamil", "kalaignar",
            "pothigai", "dd tamil", "sun news", "thanthi tv", "polimer", "captain tv",
            "raj tv", "jaya tv", "mega tv", "ktv", "svbc", "sri sankara", "astha tamil",
            "peace tamil", "angel tv", "divya tamil", "win tv", "mk tv", "tamilan",
            "tamil vision", "tamil one", "tamil news", "tamil murray", "tamil dot com",
            "polimer news", "news7 tamil", "tamil today", "pudhiya thalaimurai",
            "puthiyathalaimurai", "asianet", "manorama", "mazhavil", "surya", "kairali",
            "amrita", "janam", "kaumudy", "mathrubhumi", "mediaone", "j ai hind",
            "reporter", "j aihind", "kairali news", "mathrubhumi news", "media one",
            "asianet news", "manorama news", "mazhavil manorama", "surya tv", "kairali tv",
            "amrita tv", "janam tv", "kaumudy tv", "reporter tv", "flowers", "zee keralam",
            "colors malayalam", "star sports", "sony sports", "dd sports", "sony ten",
            "sony six", "sony pix", "sony max", "sony wah", "sony pal", "sony yay",
            "sony sab", "sony marathi", "sony aath", "sony bbc earth", "sony earth",
            "sony liv", "zee cinema", "zee anmol", "zee anmol cinema", "zee bollywood",
            "zee classic", "zee action", "zee premiere", "zee talkies", "zee marathi",
            "zee bangla", "zee bangla cinema", "zee sarthak", "zee punjabi", "zee gujarati",
            "zee bihar", "zee up", "zee mp", "zee rajasthan", "zee haryana", "zee delhi",
            "zee uttar pradesh", "zee madhya pradesh", "zee punjab", "zee gujarat",
            "zee bihar jharkhand", "zee uttar pradesh uttar", "zee rajasthan news",
            "zee haryana news", "zee delhi news", "zee mp cg", "zee 24 taas",
            "zee 24 kalak", "zee 24 ghanta", "zee news", "zee business", "zee hindustan",
            "zee salaam", "zee etv", "etv urdu", "etv telugu", "etv andhra", "etv kannada",
            "etv marathi", "etv gujarati", "etv bangla", "etv oriya", "etv bhojpuri",
            "etv urdu", "etv urdu news", "etv urdu news", "news18", "news18 india",
            "news18 hindi", "news18 urdu", "news18 punjabi", "news18 gujarati",
            "news18 marathi", "news18 kannada", "news18 tamil", "news18 telugu",
            "news18 malayalam", "news18 bangla", "news18 odia", "news18 assamese",
            "news18 kerala", "news18 kerala", "news18 lokmat", "news18 munsif",
            "news18 rajasthan", "news18 mp cg", "news18 up uk", "news18 bihar",
            "news18 jharkhand", "news18 punjab", "news18 gujarat", "news18 assam",
            "news18 north east", "news18 odisha", "news18 karnataka", "news18 tamil nadu",
            "news18 andhra", "news18 telangana", "news18 kerala", "news18 bengal",
            "news18 east", "news18 west", "news18 central", "news18 south", "news18 north"
        )

        val tamilKeywords = listOf("sun tv", "vijay tv", "colors tamil", "kalaignar", "pothigai",
            "dd tamil", "sun news", "thanthi tv", "polimer", "captain tv", "raj tv",
            "jaya tv", "mega tv", "ktv", "svbc", "sri sankara", "astha tamil",
            "peace tamil", "angel tv", "divya tamil", "win tv", "mk tv", "tamilan",
            "tamil vision", "tamil one", "tamil news", "tamil murray", "tamil dot com",
            "polimer news", "news7 tamil", "tamil today", "pudhiya thalaimurai",
            "puthiyathalaimurai", "asianet", "manorama", "mazhavil", "surya", "kairali",
            "amrita", "janam", "kaumudy", "mathrubhumi", "mediaone", "reporter",
            "kairali news", "mathrubhumi news", "media one", "asianet news", "manorama news",
            "mazhavil manorama", "surya tv", "kairali tv", "amrita tv", "janam tv",
            "kaumudy tv", "reporter tv", "flowers", "zee keralam", "colors malayalam",
            "star sports tamil", "sony sports tamil")

        val teluguKeywords = listOf("gemini", "maa", "star maa", "zee telugu", "etv telugu",
            "etv andhra", "abn", "hmtv", "hmv", "v6", "studio n", "tollywood", "abp majha",
            "tv9 telugu", "sakshi", "10 tv", "n tv", "t news", "tv5", "99 tv",
            "hmtv news", "v6 news", "sakshi tv", "abn andhra jyothi", "t news telugu",
            "tv5 news", "ntv telugu", "10 tv telugu", "star maa movies", "star maa gold",
            "star maa music", "gemini movies", "gemini music", "gemini comedy",
            "zee cinemalu", "zee telugu hd", "etv cinema", "etv plus", "etv life",
            "etv abhiruchi", "etv andhra pradesh", "etv telangana", "abp desam",
            "tv9 telugu hd", "sakshi tv hd")

        val kannadaKeywords = listOf("star suvarna", "colors kannada", "zee kannada", "udaya",
            "kasthuri", "tv9 kannada", "suvarna news", "dd chandana", "kasturi",
            "suvrana", "star suvarna plus", "colors super", "colors kannada cinema",
            "zee picchar", "udaya tv", "udaya movies", "udaya music", "udaya comedy",
            "kasthuri tv", "kasthuri news", "dd chandana hd", "public tv", "tv9 kannada hd",
            "suvarna news 24x7", "news18 kannada", "asianet suvarna")

        val malayalamKeywords = listOf("asianet", "manorama", "mazhavil", "surya", "kairali",
            "amrita", "janam", "kaumudy", "mathrubhumi", "mediaone", "reporter",
            "kairali news", "mathrubhumi news", "media one", "asianet news", "manorama news",
            "mazhavil manorama", "surya tv", "kairali tv", "amrita tv", "janam tv",
            "kaumudy tv", "reporter tv", "flowers", "zee keralam", "colors malayalam",
            "star sports malayalam", "sony sports malayalam", "dd malayalam",
            "kairali we", "amrita movies", "asianet movies", "manorama movies",
            "mazhavil movies", "surya movies", "kairali movies", "media one movies")

        val bengaliKeywords = listOf("zee bangla", "zee bangla cinema", "star jalsha", "colors bangla",
            "sony aath", "dd bangla", "aakash aath", "tara tv", "tara newz",
            "star jalsha movies", "star jalsha hd", "colors bangla cinema",
            "sony aath hd", "dd bangla hd", "news18 bangla", "zee 24 ghanta",
            "aakash aath hd", "tara hd", "star jalsha movies hd")

        val marathiKeywords = listOf("zee marathi", "star pravah", "colors marathi", "tv9 marathi",
            "saam tv", "jai maharashtra", "dd sahyadri", "mi marathi", "fakt marathi",
            "star pravah hd", "colors marathi hd", "tv9 marathi hd", "dd sahyadri hd",
            "zee 24 taas", "news18 lokmat", "abp majha", "ibn lokmat", "lokmat")

        val gujaratiKeywords = listOf("zee gujarati", "colors gujarati", "tv9 gujarati", "dd girnar",
            "dd gujarati", "gs tv", "gujarat samachar", "sandesh news", "v tv",
            "tv9 gujarati hd", "colors gujarati hd", "zee gujarati hd", "dd girnar hd",
            "news18 gujarati", "abp asmita", "etv gujarati", "gs tv gujarati")

        val punjabiKeywords = listOf("zee punjabi", "zee punjab", "dd punjabi", "ptc punjabi",
            "ptc news", "ptc chakde", "ptc music", "ptc simran", "ptc punjabi hd",
            "ptc news hd", "dd punjabi hd", "news18 punjab", "news18 punjabi",
            "ptc punjabi gold", "charhdikala", "fateh tv", "khabrain abhi tak")

        val urduKeywords = listOf("etv urdu", "zeee salaam", "zee salaam", "news18 urdu",
            "dd urdu", "dd urdu hd", "etv urdu news", "munsif tv", "ahm tv",
            "peace tv urdu", "madani channel", "q tv", "aag tv", "geo tv")

        val odiaKeywords = listOf("dd odia", "star kirano", "tarang tv", "colors odia",
            "zeee sarthak", "zee sarthak", "news18 odia", "kalinga tv", "nandighosha tv",
            "prarthana tv", "om tv", "dd odia hd", "star kirano hd", "tarang tv hd",
            "colors odia hd", "zee sarthak hd")

        val assameseKeywords = listOf("dd assam", "news18 assam", "north east live", "prag news",
            "news live", "dy 365", "pratidin time", "protidin", "assam talka",
            "dd assam hd", "news18 north east", "north east live hd", "prag news hd")

        val bhojpuriKeywords = listOf("dd bihar", "bhojpuri", "mahuaa", "sangeet bhojpuri",
            "dd bihar hd", "bhojpuri cinema", "bhojpuri hits", "bhojpuri music",
            "etv bhojpuri", "etv bhojpuri hd", "news18 bihar", "news18 jharkhand",
            "bhojpuri channel", "bhojpuri tv", "sahara samay bihar", "dd bihar jharkhand")

        val englishKeywords = listOf("cnn", "bbc", "al jazeera", "france 24", "dw", "rt",
            "nbc", "abc", "cbs", "fox", "cnbc", "bloomberg", "reuters", "ap",
            "nhk", "cgtn", "euronews", "sky news", "sky sports", "bt sport",
            "espn", "star sports select", "sony sports", "dd sports", "eurosport",
            "discovery", "nat geo", "national geographic", "history", "animal planet",
            "tlc", "dtv", "investigation discovery", "discovery science",
            "discovery turbo", "discovery channel", "discovery hd", "discovery kids",
            "discovery home", "discovery health", "discovery world", "discovery civilization",
            "discovery travel", "discovery home & health", "discovery science hd",
            "discovery turbo hd", "discovery channel hd", "discovery kids hd",
            "discovery home & health hd", "discovery world hd", "discovery civilization hd",
            "discovery travel hd", "discovery hd world", "discovery hd theater")

        val devotionalKeywords = listOf("aastha", "sanskar", "saraswati", "divya", "vedic",
            "isht", "peace", "satya", "disha", "sadna", "jagran", "sri sankara",
            "sri adhyatmik", "bhakti", "dharm", "aastha bhajan", "sanskar tv",
            "saraswati tv", "divya tv", "vedic tv", "isht tv", "peace tv",
            "satya tv", "disha tv", "sadna tv", "jagran tv", "aastha channel",
            "sanskar channel", "saraswati channel", "divya channel", "vedic channel")

        // Word-boundary matching function - handles special chars like &, +, etc.
        fun containsWord(text: String, word: String): Boolean {
            val escaped = Regex.escape(word)
            // Match at word boundary: start of string or non-word char before, end of string or non-word char after
            // Using lookbehind/lookahead style: (^|\\W)word(\\W|$)
            // But allow the word itself to contain spaces (multi-word keywords)
            val pattern = Regex("(^|\\W)" + escaped + "(\\W|$)")
            return pattern.containsMatchIn(text)
        }

        // Check each keyword list with word boundaries
        if (hindiKeywords.any { containsWord(lower, it) }) found += "hi"
        if (tamilKeywords.any { containsWord(lower, it) }) found += "ta"
        if (teluguKeywords.any { containsWord(lower, it) }) found += "te"
        if (kannadaKeywords.any { containsWord(lower, it) }) found += "kn"
        if (malayalamKeywords.any { containsWord(lower, it) }) found += "ml"
        if (bengaliKeywords.any { containsWord(lower, it) }) found += "bn"
        if (marathiKeywords.any { containsWord(lower, it) }) found += "mr"
        if (gujaratiKeywords.any { containsWord(lower, it) }) found += "gu"
        if (punjabiKeywords.any { containsWord(lower, it) }) found += "pa"
        if (urduKeywords.any { containsWord(lower, it) }) found += "ur"
        if (odiaKeywords.any { containsWord(lower, it) }) found += "or"
        if (assameseKeywords.any { containsWord(lower, it) }) found += "as"
        if (bhojpuriKeywords.any { containsWord(lower, it) }) found += "bho"
        if (englishKeywords.any { containsWord(lower, it) }) found += "en"
        if (devotionalKeywords.any { containsWord(lower, it) }) found += "hi" // Devotional mostly Hindi

        return if (found.isEmpty()) listOf("other") else found.toList()
    }

    /** Detect HD/4K from channel name suffixes. ONLY resolution terms, NOT codecs. */
    private fun detectHd(name: String): Boolean {
        val lower = name.lowercase()
        // Resolution indicators (word-boundary or suffix)
        val resolutionPatterns = listOf(
            Regex("\\bhd\\b"),          // HD as whole word
            Regex("\\b720p\\b"),
            Regex("\\b1080p\\b"),
            Regex("\\b4k\\b"),
            Regex("\\buhd\\b"),
            Regex("\\bfull\\s+hd\\b"),
            Regex("\\bfhd\\b"),
            Regex("\\b2160p\\b"),
            Regex("\\s+h\\d*$"),        // Suffix like " H", " H264" - but this is risky
        )
        // Explicitly EXCLUDE codec terms
        val codecPatterns = listOf(
            "hevc", "h.264", "h.265", "h264", "h265", "avc", "x264", "x265"
        )

        // Check for codec terms first - if found, don't mark as HD based on resolution alone
        // unless there's also an explicit resolution term
        val hasCodec = codecPatterns.any { lower.contains(it) }
        val hasResolution = resolutionPatterns.any { it.containsMatchIn(lower) }

        return hasResolution && !hasCodec
    }

    /** Strip common provider suffixes from channel name. */
    private fun cleanChannelName(name: String): String {
        var s = name
        // Remove (720p), (1080p), (4K), (HD), etc. in parentheses
        s = s.replace(Regex("""\s*\([^)]*(?:720p|1080p|4k|hd|uhd|fhd|2160p)[^)]*\)""", RegexOption.IGNORE_CASE), "")
        // Remove [Geo-blocked], [Not 24/7], [Test], etc. in brackets
        s = s.replace(Regex("""\s*\[[^\]]*\]"""), "")
        // Collapse multiple spaces and trim
        s = s.replace(Regex("""\s{2,}"""), " ").trim()
        return DisplayNames.clean(s)
    }
}