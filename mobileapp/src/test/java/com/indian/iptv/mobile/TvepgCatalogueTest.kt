package com.indian.iptv.mobile

import com.indian.iptv.mobile.data.Channel
import com.indian.iptv.mobile.data.TvepgCatalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvepgCatalogueTest {

    @Test
    fun `normalisation matches playlist names with quality suffixes`() {
        assertEquals("4tvnews", TvepgCatalogue.normalize("4TV News (576p)"))
        assertEquals("aajtak", TvepgCatalogue.normalize("Aaj Tak HD"))
    }

    @Test
    fun `parses tsv and ignores comments and blank ids`() {
        val tsv = """
            # comment
            4tvnews	ts774	https://example.com/logo.png
            starplus	1664	
            broken		
        """.trimIndent()
        val map = TvepgCatalogue.parse(tsv)
        assertEquals(2, map.size)
        assertEquals("ts774", map["4tvnews"]?.epgId)
        assertNull(map["starplus"]?.logo)
        assertNull(map["broken"])
    }

    @Test
    fun `enrichment swaps tvg id and fills only a missing logo`() {
        val map = TvepgCatalogue.parse("4tvnews\tts774\thttps://example.com/logo.png")
        val channel = Channel(
            name = "4TV News (576p)",
            url = "https://example.com/live.m3u8",
            logo = null,
            category = "News",
            tvgId = "4TVNews.in@SD",
            userAgent = "Mozilla/5.0",
            referrer = null,
        )

        val enriched = TvepgCatalogue.enrichWith(channel, map)
        assertEquals("https://example.com/live.m3u8", enriched.url)
        assertEquals("Mozilla/5.0", enriched.userAgent)
        assertEquals("ts774", enriched.tvgId)
        assertEquals("https://example.com/logo.png", enriched.logo)

        val withOwnLogo = channel.copy(logo = "https://example.com/own.png")
        assertEquals("https://example.com/own.png", TvepgCatalogue.enrichWith(withOwnLogo, map).logo)
        assertEquals(channel, TvepgCatalogue.enrichWith(channel, TvepgCatalogue.parse("starplus\t1664\t")))
    }
}
