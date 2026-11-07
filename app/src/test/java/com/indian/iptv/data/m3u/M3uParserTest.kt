package com.indian.iptv.data.m3u

import com.indian.iptv.data.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    @Test
    fun `parses single entry with all attributes`() {
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="npo1.nl" tvg-logo="http://logo/npo1.png" group-title="Nederland",NPO 1
            http://stream/npo1.ts
        """.trimIndent()

        val channels = M3uParser.parse(content)

        assertEquals(1, channels.size)
        val c = channels[0]
        assertEquals("npo1.nl", c.id)
        assertEquals("NPO 1", c.name)
        assertEquals("http://logo/npo1.png", c.logoUrl)
        assertEquals("Nederland", c.groupTitle)
        assertEquals("http://stream/npo1.ts", c.streamUrl)
        assertEquals("npo1.nl", c.epgChannelId)
        assertEquals(ContentType.TV, c.type)
    }

    @Test
    fun `parses entry without attributes`() {
        val content = """
            #EXTM3U
            #EXTINF:-1,Plain Channel
            http://stream/plain.ts
        """.trimIndent()

        val channels = M3uParser.parse(content)

        assertEquals(1, channels.size)
        assertEquals("Plain Channel", channels[0].name)
        assertNull(channels[0].logoUrl)
        assertNull(channels[0].groupTitle)
        assertNull(channels[0].epgChannelId)
    }

    @Test
    fun `parses multiple entries and skips comments`() {
        val content = """
            #EXTM3U
            # a comment
            #EXTINF:-1 tvg-id="a" group-title="News",A
            http://a/1
            #EXTINF:-1 tvg-id="b" group-title="Sport",B
            http://b/1
        """.trimIndent()

        val channels = M3uParser.parse(content)

        assertEquals(2, channels.size)
        assertEquals(listOf("A", "B"), channels.map { it.name })
        assertEquals(listOf("News", "Sport"), channels.map { it.groupTitle })
    }

    @Test
    fun `ignores EXTINF without following URL`() {
        val content = """
            #EXTM3U
            #EXTINF:-1,Orphan
            #EXTINF:-1,Valid
            http://valid/1
        """.trimIndent()

        val channels = M3uParser.parse(content)

        assertEquals(1, channels.size)
        assertEquals("Valid", channels[0].name)
    }

    @Test
    fun `generates stable id from name when tvg-id missing`() {
        val content = """
            #EXTM3U
            #EXTINF:-1,One
            http://one/1
            #EXTINF:-1,One
            http://one/2
        """.trimIndent()

        val channels = M3uParser.parse(content)

        assertEquals(2, channels.size)
        // Ids must be unique per position; the #index suffix guarantees that.
        assertEquals(channels.map { it.id }.toSet().size, 2)
    }

    @Test
    fun `handles empty or EXTM3U-only input`() {
        assertEquals(0, M3uParser.parse("").size)
        assertEquals(0, M3uParser.parse("#EXTM3U\n").size)
    }

    @Test
    fun `comma inside a quoted attribute does not split the name`() {
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="news" group-title="News, Sports",Channel One
            http://x/1
        """.trimIndent()

        val ch = M3uParser.parse(content).single()

        assertEquals("Channel One", ch.name)
        assertEquals("News, Sports", ch.groupTitle)
    }

    @Test
    fun `duplicate tvg-id keeps both channels with unique ids`() {
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="npo1",NPO 1 HD
            http://x/hd
            #EXTINF:-1 tvg-id="npo1",NPO 1 SD
            http://x/sd
        """.trimIndent()

        val channels = M3uParser.parse(content)

        assertEquals(listOf("npo1", "npo1#2"), channels.map { it.id })
        assertEquals(listOf("npo1", "npo1"), channels.map { it.epgChannelId })
    }

    @Test
    fun `id without tvg-id does not depend on position`() {
        val a = M3uParser.parse("#EXTINF:-1,Foo\nhttp://x/foo").single().id
        val b = M3uParser.parse("#EXTINF:-1,Bar\nhttp://x/bar\n#EXTINF:-1,Foo\nhttp://x/foo")[1].id

        assertEquals(a, b)
    }

    @Test
    fun `movie urls are typed as movies`() {
        val channels = M3uParser.parse(
            "#EXTINF:-1,Film\nhttp://x/movie/u/p/1.mp4\n#EXTINF:-1,Zender\nhttp://x/live/u/p/2.ts",
        )

        assertEquals(listOf(ContentType.MOVIE, ContentType.TV), channels.map { it.type })
    }

    @Test
    fun `EXTGRP supplies the group when group-title is missing`() {
        val ch = M3uParser.parse("#EXTINF:-1,Foo\n#EXTGRP:Kids\nhttp://x/foo").single()

        assertEquals("Kids", ch.groupTitle)
    }

    @Test
    fun `parses tvg-language and tvg-country`() {
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="zee.hindi" tvg-language="hi" tvg-country="IN" group-title="Entertainment;News",Zee TV
            http://stream/zee.ts
        """.trimIndent()

        val channels = M3uParser.parse(content)
        val c = channels[0]
        assertEquals(listOf("hi"), c.languages)
        assertEquals(listOf("Entertainment", "News"), c.genres)
        assertEquals(1, c.number) // First TV channel gets number 1
    }

    @Test
    fun `parses multiple languages from tvg-language`() {
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="test" tvg-language="hi,en" group-title="News",Test Channel
            http://stream/test.ts
        """.trimIndent()

        val channels = M3uParser.parse(content)
        assertEquals(listOf("hi", "en"), channels[0].languages)
    }

    @Test
    fun `assigns channel numbers sequentially for TV only`() {
        val content = """
            #EXTM3U
            #EXTINF:-1,Channel 1
            http://stream/1.ts
            #EXTINF:-1,Movie 1
            http://stream/movie/1.mp4
            #EXTINF:-1,Channel 2
            http://stream/2.ts
            #EXTINF:-1,Series 1
            http://stream/series/1.mp4
            #EXTINF:-1,Channel 3
            http://stream/3.ts
        """.trimIndent()

        val channels = M3uParser.parse(content)
        val tvChannels = channels.filter { it.type == ContentType.TV }
        assertEquals(listOf(1, 2, 3), tvChannels.map { it.number })
        val nonTvChannels = channels.filter { it.type != ContentType.TV }
        assertTrue(nonTvChannels.all { it.number == null })
    }

    @Test
    fun `detects HD from name suffixes`() {
        val hdNames = listOf("Zee TV HD", "Star Plus 720p", "Sony 1080p", "Colors 4K", "DD National UHD", "Test FHD")
        val sdNames = listOf("Zee TV", "Star Plus", "Sony", "Colors")

        hdNames.forEach { name ->
            val content = "#EXTM3U\n#EXTINF:-1,$name\nhttp://stream/test.ts"
            val c = M3uParser.parse(content).single()
            assertTrue("$name should be detected as HD", c.isHd)
        }
        sdNames.forEach { name ->
            val content = "#EXTM3U\n#EXTINF:-1,$name\nhttp://stream/test.ts"
            val c = M3uParser.parse(content).single()
            assertTrue("$name should NOT be detected as HD", !c.isHd)
        }
    }

    @Test
    fun `strips common suffixes from channel name`() {
        val cases = listOf(
            "Zee TV (720p)" to "Zee TV",
            "Star Plus [Geo-blocked]" to "Star Plus",
            "Sony HD [Not 24/7]" to "Sony HD",
            "Colors (1080p) [Test]" to "Colors",
        )
        cases.forEach { (input, expected) ->
            val content = "#EXTM3U\n#EXTINF:-1,$input\nhttp://stream/test.ts"
            val c = M3uParser.parse(content).single()
            assertEquals("Name cleaning failed for '$input'", expected, c.name)
        }
    }

    @Test
    fun `splits group-title by semicolon into multiple genres`() {
        val content = """
            #EXTM3U
            #EXTINF:-1 group-title="News;Entertainment;Regional",Test Channel
            http://stream/test.ts
        """.trimIndent()

        val channels = M3uParser.parse(content)
        assertEquals(listOf("News", "Entertainment", "Regional"), channels[0].genres)
    }

    @Test
    fun `infers Hindi from common Indian channel keywords`() {
        val hindiChannels = listOf(
            "Zee TV", "Sony TV", "Star Plus", "Colors", "&TV", "DD National",
            "Aaj Tak", "ABP News", "India TV", "News18 India", "Republic TV",
            "Times Now", "CNN News18", "TV9", "ETV", "Sahara", "Bharat",
            "Sanskar", "Aastha", "Saraswati", "Divya", "Vedic"
        )
        hindiChannels.forEach { name ->
            val content = "#EXTM3U\n#EXTINF:-1,$name\nhttp://stream/test.ts"
            val c = M3uParser.parse(content).single()
            assertTrue("Expected Hindi for '$name', got ${c.languages}", c.languages.contains("hi"))
        }
    }

    @Test
    fun `infers Tamil from Tamil channel keywords`() {
        val tamilChannels = listOf(
            "Sun TV", "Vijay TV", "Colors Tamil", "Kalaignar", "Pothigai",
            "DD Tamil", "Sun News", "Thanthi TV", "Polimer", "Captain TV",
            "Raj TV", "Jaya TV", "Mega TV", "KTV", "SVBC", "Asianet",
            "Manorama", "Mazhavil", "Surya", "Kairali", "Amrita"
        )
        tamilChannels.forEach { name ->
            val content = "#EXTM3U\n#EXTINF:-1,$name\nhttp://stream/test.ts"
            val c = M3uParser.parse(content).single()
            assertTrue("Expected Tamil for '$name', got ${c.languages}", c.languages.contains("ta"))
        }
    }

    @Test
    fun `infers Telugu from Telugu channel keywords`() {
        val teluguChannels = listOf(
            "Gemini", "MAA", "Star MAA", "Zee Telugu", "ETV Telugu",
            "ETV Andhra", "ABN", "HMV", "V6", "Studio N", "Tollywood"
        )
        teluguChannels.forEach { name ->
            val content = "#EXTM3U\n#EXTINF:-1,$name\nhttp://stream/test.ts"
            val c = M3uParser.parse(content).single()
            assertTrue("Expected Telugu for '$name', got ${c.languages}", c.languages.contains("te"))
        }
    }

    @Test
    fun `infers language from tvg-country when tvg-language missing`() {
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-country="IN" group-title="News",Test Channel
            http://stream/test.ts
        """.trimIndent()

        val channels = M3uParser.parse(content)
        // India maps to hi, en
        assertTrue(channels[0].languages.contains("hi"))
        assertTrue(channels[0].languages.contains("en"))
    }
}