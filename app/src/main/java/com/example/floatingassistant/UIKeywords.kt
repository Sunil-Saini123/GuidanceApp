package com.example.floatingassistant

/**
 * UIKeywords — Phase 11 v4
 *
 * An expanded, modular wordlist for filtering UI elements.
 * Categories are combined into a single case-insensitive Regex for fast matching.
 */
object UIKeywords {

    private val NAVIGATION = listOf(
        "back", "home", "navigate up", "next", "previous", "close", "done", "cancel", "exit", "return"
    )

    private val SETTINGS_SYSTEM = listOf(
        "settings", "about", "display", "network", "wifi", "bluetooth", "sound", "vibration",
        "notifications", "privacy", "security", "battery", "storage", "apps", "accounts",
        "system", "update", "profile", "device", "advanced", "permissions"
    )

    private val ACTIONS_MEDIA = listOf(
        "search", "more options", "menu", "overflow", "filter", "camera", "share", "edit",
        "add", "create", "help", "info", "play", "pause", "stop", "download", "upload", "save",
        "delete", "remove"
    )

    private val SOCIAL_COMMUNICATION = listOf(
        "all", "unread", "favorites", "groups", "archived", "chats", "updates", "calls",
        "communities", "contacts", "messages", "status"
    )

    private val ALL_KEYWORDS = (NAVIGATION + SETTINGS_SYSTEM + ACTIONS_MEDIA + SOCIAL_COMMUNICATION).toSet()

    // Single case-insensitive regex for fast matching
    // Matches the entire string to one of the keywords
    val REGEX = Regex(
        "^(" + ALL_KEYWORDS.joinToString(separator = "|") { Regex.escape(it) } + ")$",
        RegexOption.IGNORE_CASE
    )

    fun matches(name: String): Boolean {
        return REGEX.matches(name.trim())
    }
}
