package com.indian.iptv.data

/**
 * Group titles that name a broadcast language rather than a genre. Playlists like
 * iptv-org's India list group channels by language ("Hindi", "Tamil", …), which buries
 * the real genre rails under a wall of language rails on the home screen. Those rails
 * are filtered out of the main screen; the channels themselves stay reachable through
 * the Categories screen and the player's channel list.
 */
object LanguageGroups {

    private val NAMES = setOf(
        "english", "hindi", "urdu", "tamil", "telugu", "malayalam", "kannada", "bengali",
        "marathi", "punjabi", "gujarati", "odia", "oriya", "assamese", "bhojpuri", "sindhi",
        "nepali", "sanskrit", "konkani", "manipuri", "tulu", "maithili", "haryanvi",
        "rajasthani", "chhattisgarhi", "bodo", "kashmiri", "dogri", "mizo", "khasi", "garo",
        "santali", "kokborok", "arabic", "persian", "pashto", "kurdish", "french", "german",
        "spanish", "portuguese", "italian", "russian", "turkish", "chinese", "japanese",
        "korean", "thai", "vietnamese", "indonesian", "malay", "dutch", "polish", "ukrainian",
        "hebrew", "greek", "hungarian", "czech", "slovak", "romanian", "bulgarian", "serbian",
        "croatian", "bosnian", "slovenian", "albanian", "macedonian", "swedish", "norwegian",
        "danish", "finnish", "icelandic", "tagalog", "sinhala", "burmese", "khmer", "lao",
        "mongolian", "uzbek", "kazakh", "azeri", "georgian", "armenian", "amharic", "somali",
        "swahili", "yoruba", "hausa", "igbo", "zulu", "afrikaans", "catalan", "basque",
        "galician", "belarusian", "latvian", "lithuanian", "estonian", "maltese", "chechen",
        "tatar", "bashkir", "chuvash", "ossetian", "abkhaz", "adyghe", "karachay", "balkar",
        "dagestan", "inhush", "cesen", "krio", "bambara", "ewe", "twi", "dinka", "ndebele",
        "shona", "sotho", "tswana", "wolof", "fulfulde", "tigrinya", "amharigna",
    )

    /** True when a category/rail title is just a language name (e.g. "Hindi"). */
    fun isLanguageTitle(title: String): Boolean = title.trim().lowercase() in NAMES
}
