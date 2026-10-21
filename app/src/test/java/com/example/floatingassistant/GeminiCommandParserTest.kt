package com.example.floatingassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiCommandParserTest {

    @Test
    fun testParseResponseStandardFormat() {
        val raw = """
            app: settings
            end_page: display_settings
            task: change_wallpaper
        """.trimIndent()

        val parsed = GeminiCommandParser.parseResponse(raw)
        assertNotNull(parsed)
        assertEquals("settings", parsed?.targetApp)
        assertEquals("display_settings", parsed?.destinationScreen)
        assertEquals("change_wallpaper", parsed?.exactTask)
    }

    @Test
    fun testParseResponseMarkdownBolding() {
        val raw = """
            **app:** settings
            **end_page:** display_settings
            **task:** change_wallpaper
        """.trimIndent()

        val parsed = GeminiCommandParser.parseResponse(raw)
        assertNotNull(parsed)
        assertEquals("settings", parsed?.targetApp)
        assertEquals("display_settings", parsed?.destinationScreen)
        assertEquals("change_wallpaper", parsed?.exactTask)
    }

    @Test
    fun testParseResponseMarkdownBulletsAndQuotes() {
        val raw = """
            - **app:** "whatsapp"
            - **end_page:** "profile_page"
            - **task:** "update_profile_picture"
        """.trimIndent()

        val parsed = GeminiCommandParser.parseResponse(raw)
        assertNotNull(parsed)
        assertEquals("whatsapp", parsed?.targetApp)
        assertEquals("profile_page", parsed?.destinationScreen)
        assertEquals("update_profile_picture", parsed?.exactTask)
    }

    @Test
    fun testParseResponseNumberedListWithFences() {
        val raw = """
            ```yaml
            1. app: youtube
            2. end_page: search_page
            3. task: search_video
            ```
        """.trimIndent()

        val parsed = GeminiCommandParser.parseResponse(raw)
        assertNotNull(parsed)
        assertEquals("youtube", parsed?.targetApp)
        assertEquals("search_page", parsed?.destinationScreen)
        assertEquals("search_video", parsed?.exactTask)
    }

    @Test
    fun testParseResponseSplitLines() {
        val raw = """
            app:
            clock
            end_page:
            alarm_tab
            task:
            create_alarm
        """.trimIndent()

        val parsed = GeminiCommandParser.parseResponse(raw)
        assertNotNull(parsed)
        assertEquals("clock", parsed?.targetApp)
        assertEquals("alarm_tab", parsed?.destinationScreen)
        assertEquals("create_alarm", parsed?.exactTask)
    }

    @Test
    fun testParseResponseIncompleteReturnsNull() {
        val raw = """
            app: settings
            end_page: display
        """.trimIndent()

        val parsed = GeminiCommandParser.parseResponse(raw)
        assertNull(parsed)
    }
}
