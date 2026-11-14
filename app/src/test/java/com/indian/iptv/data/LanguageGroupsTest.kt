package com.indian.iptv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageGroupsTest {

    @Test
    fun `iptv-org india language group titles are detected`() {
        // Group titles as they appear in iptv-org's India playlist.
        for (title in listOf("Hindi", "English", "Tamil", "Telugu", "Malayalam", "Kannada",
            "Bengali", "Marathi", "Punjabi", "Urdu", "Gujarati", "Odia", "Assamese", "Bhojpuri")) {
            assertTrue("expected language: $title", LanguageGroups.isLanguageTitle(title))
        }
    }

    @Test
    fun `genre and provider group titles are not language names`() {
        for (title in listOf("Sports", "News", "Music", "Kids", "Movies", "Religious",
            "Entertainment", "Documentary", "Uncategorized", "", "Hindi News", "Star Plus")) {
            assertFalse("expected non-language: '$title'", LanguageGroups.isLanguageTitle(title))
        }
    }
}
