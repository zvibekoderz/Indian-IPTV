package com.indian.iptv.data.tvepg

import com.indian.iptv.data.Channel
import com.indian.iptv.data.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvepgCatalogueTest {

    @Test
    fun `normalisation strips quality suffixes and words`() {
        assertEquals("4tvnews", TvepgCatalogue.normalize("4TV News (576p)"))
        assertEquals("4tvnews", TvepgCatalogue.normalize("4TV News HD"))
        assertEquals("starplus", TvepgCatalogue.normalize("Star Plus (1080p)"))
        assertEquals("aajtak", TvepgCatalogue.normalize("Aaj Tak HEVC"))
    }

    @Test
    fun `parses tsv and ignores comments and blank ids`() {
        val tsv = """
            # key<TAB>id<TAB>logo
            4tvnews	ts774	https://example.com/logo.png
            starplus	1664	
            broken		
        """.trimIndent()
        val map = TvepgCatalogue.parse(tsv)
        assertEquals(2, map.size)
        assertEquals("ts774", map["4tvnews"]?.epgId)
        assertEquals("https://example.com/logo.png", map["4tvnews"]?.logo)
        assertNull(map["starplus"]?.logo)
        assertNull(map["broken"])
    }

    @Test
    fun `enrichment sets epg id and fills only a missing logo`() {
        val map = TvepgCatalogue.parse("4tvnews\tts774\thttps://example.com/logo.png")
        val original = Channel(
            id = "4TVNews.in@SD",
            name = "4TV News (576p)",
            logoUrl = null,
            groupTitle = "News",
            streamUrl = "https://example.com/live.m3u8",
            epgChannelId = "4TVNews.in@SD",
            type = ContentType.TV,
        )

        val enriched = TvepgCatalogue.enrichWith(original, map)
        assertEquals("4TVNews.in@SD", enriched.id) // favourites/progress stay valid
        assertEquals("https://example.com/live.m3u8", enriched.streamUrl)
        assertEquals("ts774", enriched.epgChannelId)
        assertEquals("https://example.com/logo.png", enriched.logoUrl)

        val withOwnLogo = original.copy(logoUrl = "https://example.com/own.png")
        assertEquals("https://example.com/own.png", TvepgCatalogue.enrichWith(withOwnLogo, map).logoUrl)
    }

    @Test
    fun `unmatched channels are left untouched`() {
        val map = TvepgCatalogue.parse("4tvnews\tts774\t")
        val channel = Channel(
            id = "unknown",
            name = "Channel Not In Map",
            logoUrl = null,
            groupTitle = null,
            streamUrl = "https://example.com/live.m3u8",
            epgChannelId = "unknown",
            type = ContentType.TV,
        )
        assertEquals(channel, TvepgCatalogue.enrichWith(channel, map))
    }
}
