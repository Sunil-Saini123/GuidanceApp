package com.example.floatingassistant

import org.junit.Assert.*
import org.junit.Test

/**
 * [PERMANENT FIX] Do not remove this! Unit tests for ScreenTitleResolver WhatsApp DNA Architecture and Canonicalization.
 * Validates:
 * 1. Badge noise stripping: Unread counts and notification labels are cleanly stripped.
 * 2. WhatsApp root canonicalization: "WhatsApp" and "Chats, 1 unread" map to "Chats".
 * 3. Sub-screen preservation: "Chats Settings", "Chat backup", "Settings" are preserved and never collapsed to root.
 * 4. Settings contact collision protection: Personal names ("Rohit Thatikonda") are never canonicalized to Settings.
 */
class ScreenTitleResolverTest {

    @Test
    fun testStripBadgeNoise_RemovesNotificationAndUnreadCounters() {
        assertEquals("Chats", ScreenTitleResolver.stripBadgeNoise("Chats, 1 new notification"))
        assertEquals("Chats", ScreenTitleResolver.stripBadgeNoise("Chats, 5 new messages"))
        assertEquals("Updates", ScreenTitleResolver.stripBadgeNoise("Updates, 3 unread"))
        assertEquals("Chats", ScreenTitleResolver.stripBadgeNoise("Chats (2)"))
        assertEquals("Calls", ScreenTitleResolver.stripBadgeNoise("Calls"))
    }

    @Test
    fun testCanonicalizeTitle_WhatsAppRootAndSubpages() {
        // Root screen canonicalization
        assertEquals("Chats", ScreenTitleResolver.canonicalizeTitle("WhatsApp", "com.whatsapp"))
        assertEquals("Chats", ScreenTitleResolver.canonicalizeTitle("WhatsApp Home", "com.whatsapp"))
        assertEquals("Chats", ScreenTitleResolver.canonicalizeTitle("Chats", "com.whatsapp"))
        assertEquals("Chats", ScreenTitleResolver.canonicalizeTitle("Chats, 2 new messages", "com.whatsapp"))

        // Sub-page preservation (Must NEVER collapse to "Chats")
        assertEquals("Chats Settings", ScreenTitleResolver.canonicalizeTitle("Chats Settings", "com.whatsapp"))
        assertEquals("Chat backup", ScreenTitleResolver.canonicalizeTitle("Chat backup", "com.whatsapp"))
        assertEquals("Settings", ScreenTitleResolver.canonicalizeTitle("Settings", "com.whatsapp"))
        assertEquals("Profile", ScreenTitleResolver.canonicalizeTitle("Profile", "com.whatsapp"))

        // Conversation contact name preservation
        assertEquals("Rohit Thatikonda (You)", ScreenTitleResolver.canonicalizeTitle("Rohit Thatikonda (You)", "com.whatsapp"))
        assertEquals("Mayuresh Vit", ScreenTitleResolver.canonicalizeTitle("Mayuresh Vit", "com.whatsapp"))
    }

    @Test
    fun testIsNoiseElement_ChatsSettingsHeaderSuppression() {
        // [PERMANENT FIX] Do not remove this! Verify sub-screen header suppression vs root Settings preservation
        assertTrue(NavGraphDatabase.isNoiseElement("Chats", "Chats Settings", "com.whatsapp"))
        assertTrue(NavGraphDatabase.isNoiseElement("Chat settings", "Chats Settings", "com.whatsapp"))
        assertTrue(NavGraphDatabase.isNoiseElement("Chats Settings", "Chats Settings", "com.whatsapp"))

        // Under root "Settings", "Chats" is an actionable link and must NOT be filtered out
        assertFalse(NavGraphDatabase.isNoiseElement("Chats", "Settings", "com.whatsapp"))
        assertFalse(NavGraphDatabase.isNoiseElement("Appearance", "Settings", "com.whatsapp"))
        assertFalse(NavGraphDatabase.isNoiseElement("Storage and data", "Settings", "com.whatsapp"))
    }
}
