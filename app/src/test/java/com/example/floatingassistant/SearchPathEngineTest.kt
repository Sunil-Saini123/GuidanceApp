package com.example.floatingassistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * SearchPathEngineTest — Unit tests for Phase 2 Tier 1 Local Graph Search.
 * Tests Dijkstra / BFS shortest path resolution, element matching, cycle avoidance,
 * and unreachable node handling on decoupled graph records.
 */
class SearchPathEngineTest {

    private fun createScreen(
        id: String,
        pkg: String = "com.android.settings",
        title: String,
        elements: List<String> = emptyList(),
        visitCount: Int = 1
    ): NavGraphDatabase.ScreenRecord {
        val jsonArray = JSONArray()
        for (el in elements) {
            jsonArray.put(JSONObject().apply {
                put("name", el)
                put("is_clickable", true)
            })
        }
        return NavGraphDatabase.ScreenRecord(
            id = id,
            packageName = pkg,
            screenTitle = title,
            rootClass = "${title}Activity",
            elementsJson = jsonArray.toString(),
            visitCount = visitCount,
            firstSeen = 1000L,
            lastSeen = 2000L
        )
    }

    private fun createTransition(
        fromId: String,
        toId: String,
        action: String,
        weight: Double = 1.0,
        traversalCount: Int = 1
    ): NavGraphDatabase.TransitionRecord {
        return NavGraphDatabase.TransitionRecord(
            id = 1L,
            fromScreenId = fromId,
            toScreenId = toId,
            actionLabel = action,
            actionType = "CLICK",
            traversalCount = traversalCount,
            weight = weight,
            firstSeen = 1000L,
            lastSeen = 2000L
        )
    }

    @Test
    fun testDirectSingleHopPath() {
        val s1 = createScreen("com.android.settings::Settings", title = "Settings")
        val s2 = createScreen("com.android.settings::Connected devices", title = "Connected devices")
        val t1 = createTransition(s1.id, s2.id, "Connected devices")

        val result = SearchPathEngine.findPathInGraph(
            screens = listOf(s1, s2),
            transitions = listOf(t1),
            currentScreenId = s1.id,
            destinationScreen = "Connected devices",
            exactTask = null
        )

        assertTrue("Direct path should be found", result.found)
        assertEquals("Connected devices", result.pathString)
        assertEquals(listOf("Connected devices"), result.steps)
    }

    @Test
    fun testMultiHopShortestPath() {
        val s1 = createScreen("com.android.settings::Settings", title = "Settings")
        val s2 = createScreen("com.android.settings::Connected devices", title = "Connected devices")
        val s3 = createScreen("com.android.settings::Bluetooth", title = "Bluetooth")

        val t1 = createTransition(s1.id, s2.id, "Connected devices")
        val t2 = createTransition(s2.id, s3.id, "Bluetooth")

        val result = SearchPathEngine.findPathInGraph(
            screens = listOf(s1, s2, s3),
            transitions = listOf(t1, t2),
            currentScreenId = s1.id,
            destinationScreen = "Bluetooth",
            exactTask = "Enable Bluetooth"
        )

        assertTrue("Multi-hop path should be found", result.found)
        assertEquals("Connected devices -> Bluetooth", result.pathString)
        assertEquals(listOf("Connected devices", "Bluetooth"), result.steps)
    }

    @Test
    fun testElementMatchOnDestinationScreen() {
        val s1 = createScreen("com.android.settings::Settings", title = "Settings")
        val s2 = createScreen(
            "com.android.settings::Connected devices",
            title = "Connected devices",
            elements = listOf("Pair new device", "Bluetooth", "NFC")
        )

        val t1 = createTransition(s1.id, s2.id, "Connected devices")

        // User intent targets "Pair new device" which is an element inside Connected devices
        val result = SearchPathEngine.findPathInGraph(
            screens = listOf(s1, s2),
            transitions = listOf(t1),
            currentScreenId = s1.id,
            destinationScreen = "Pair new device",
            exactTask = "Pair a new bluetooth accessory"
        )

        assertTrue("Path to element inside screen should be found", result.found)
        assertEquals("Connected devices -> Pair new device", result.pathString)
        assertEquals(listOf("Connected devices", "Pair new device"), result.steps)
        assertEquals("Pair new device", result.targetElement)
    }

    @Test
    fun testCycleAvoidanceAndShortestPath() {
        val sA = createScreen("com.test::Home", title = "Home")
        val sB = createScreen("com.test::B", title = "B")
        val sC = createScreen("com.test::C", title = "C")
        val sD = createScreen("com.test::D", title = "D")

        // Cycle: Home -> B -> Home
        val tAB = createTransition(sA.id, sB.id, "Go to B")
        val tBA = createTransition(sB.id, sA.id, "Go back to Home")

        // Direct path: B -> C
        val tBC = createTransition(sB.id, sC.id, "Go to C")

        // Longer path: Home -> D -> C
        val tAD = createTransition(sA.id, sD.id, "Go to D", weight = 5.0)
        val tDC = createTransition(sD.id, sC.id, "Go from D to C", weight = 5.0)

        // "Home" is a known entry title so it becomes the root
        val result = SearchPathEngine.findPathInGraph(
            screens = listOf(sA, sB, sC, sD),
            transitions = listOf(tAB, tBA, tBC, tAD, tDC),
            currentScreenId = sA.id,
            destinationScreen = "C",
            exactTask = null
        )

        assertTrue(result.found)
        // Path always starts from root Home: Go to B -> Go to C
        assertEquals("Go to B -> Go to C", result.pathString)
        assertEquals(listOf("Go to B", "Go to C"), result.steps)
    }

    @Test
    fun testAlreadyOnDestinationScreen() {
        // When the only screen IS the root, target == root → element is returned directly
        val s1 = createScreen("com.whatsapp::WhatsApp", pkg = "com.whatsapp", title = "WhatsApp")

        val result = SearchPathEngine.findPathInGraph(
            screens = listOf(s1),
            transitions = emptyList(),
            currentScreenId = s1.id,
            destinationScreen = "WhatsApp",
            exactTask = null,
            appDisplayName = "WhatsApp"
        )

        assertTrue(result.found)
        assertEquals("WhatsApp", result.pathString)
        assertTrue(result.message.contains("app home screen"))
    }

    @Test
    fun testDestinationNotFoundReturnsCleanMiss() {
        val s1 = createScreen("com.android.settings::Settings", title = "Settings")

        val result = SearchPathEngine.findPathInGraph(
            screens = listOf(s1),
            transitions = emptyList(),
            currentScreenId = s1.id,
            destinationScreen = "NonExistentFeature",
            exactTask = "Do something unknown"
        )

        assertFalse("Missing destination should return found = false", result.found)
        assertNull("Path string should be null on miss", result.pathString)
        assertTrue(result.message.contains("could not be mapped"))
    }

    @Test
    fun testUnreachableScreenReturnsCleanMiss() {
        val s1 = createScreen("com.android.settings::Settings", title = "Settings")
        val s2 = createScreen("com.android.settings::Developer options", title = "Developer options")
        // No transition connecting s1 to s2

        val result = SearchPathEngine.findPathInGraph(
            screens = listOf(s1, s2),
            transitions = emptyList(),
            currentScreenId = s1.id,
            destinationScreen = "Developer options",
            exactTask = null
        )

        assertFalse("Unreachable node should return found = false", result.found)
        assertNull(result.pathString)
        assertTrue(result.message.contains("No navigable path"))
    }

    @Test
    fun testCommandParserOfflineResolution() {
        val btResult = GeminiCommandParser.resolveLocally("turn on bluetooth")
        assertEquals("Settings", btResult.targetApp)
        assertEquals("Bluetooth", btResult.destinationScreen)

        val wifiResult = GeminiCommandParser.resolveLocally("connect to wifi")
        assertEquals("Settings", wifiResult.targetApp)
        assertEquals("Wi-Fi", wifiResult.destinationScreen)

        val whatsappResult = GeminiCommandParser.resolveLocally("change my whatsapp profile picture")
        assertEquals("WhatsApp", whatsappResult.targetApp)
        assertEquals("Profile", whatsappResult.destinationScreen)

        val backupResult = GeminiCommandParser.resolveLocally("open whatsapp chat backup")
        assertEquals("WhatsApp", backupResult.targetApp)
        assertEquals("chat backup", backupResult.destinationScreen)

        val phoneResult = GeminiCommandParser.resolveLocally("call mom")
        assertEquals("Phone", phoneResult.targetApp)
        assertEquals("Dialer", phoneResult.destinationScreen)
    }

    @Test
    fun testWhatsAppChatBackupGraphSearch() {
        val sHome = createScreen("com.whatsapp::WhatsApp", pkg = "com.whatsapp", title = "WhatsApp")
        val sSettings = createScreen("com.whatsapp::Settings", pkg = "com.whatsapp", title = "Settings")
        val sChats = createScreen(
            "com.whatsapp::Chats",
            pkg = "com.whatsapp",
            title = "Chats",
            elements = listOf("Chat backup", "Chat history")
        )

        val t1 = createTransition(sHome.id, sSettings.id, "Settings")
        val t2 = createTransition(sSettings.id, sChats.id, "Chats")

        val result = SearchPathEngine.findPathInGraph(
            screens = listOf(sHome, sSettings, sChats),
            transitions = listOf(t1, t2),
            currentScreenId = sHome.id,
            destinationScreen = "chat backup",
            exactTask = "open whatsapp chat backup",
            appDisplayName = "WhatsApp"
        )

        assertTrue("Path to chat backup should be found", result.found)
        assertEquals("Settings -> Chats -> Chat backup", result.pathString)
        assertEquals(listOf("Settings", "Chats", "Chat backup"), result.steps)
    }

    // [PERMANENT FIX] Verify Dijkstra routes from user's current sub-screen rather than restarting from root
    @Test
    fun testMidAppRoutingFromCurrentSubScreen() {
        val s1 = createScreen("com.android.settings::Settings", title = "Settings")
        val s2 = createScreen("com.android.settings::Connected devices", title = "Connected devices")
        val s3 = createScreen("com.android.settings::Bluetooth", title = "Bluetooth")

        val t1 = createTransition(s1.id, s2.id, "Connected devices")
        val t2 = createTransition(s2.id, s3.id, "Bluetooth")

        // User is ALREADY on Connected devices (s2) and wants Bluetooth
        val result = SearchPathEngine.findPathInGraph(
            screens = listOf(s1, s2, s3),
            transitions = listOf(t1, t2),
            currentScreenId = s2.id,
            destinationScreen = "Bluetooth",
            exactTask = "Enable Bluetooth",
            appDisplayName = "Settings"
        )

        assertTrue("Path from current screen should be found", result.found)
        assertEquals("Bluetooth", result.pathString)
        assertEquals(listOf("Bluetooth"), result.steps)
        assertEquals(s2.id, result.fromScreenId)
    }

    // [PERMANENT FIX] Verify Dijkstra falls back to root screen if destination is unreachable from current sub-screen
    @Test
    fun testFallbackToRootWhenUnreachableFromSubScreen() {
        val sRoot = createScreen("com.android.settings::Settings", title = "Settings")
        val sConnected = createScreen("com.android.settings::Connected devices", title = "Connected devices")
        val sBluetooth = createScreen("com.android.settings::Bluetooth", title = "Bluetooth")
        val sDisplay = createScreen("com.android.settings::Display", title = "Display")

        val t1 = createTransition(sRoot.id, sConnected.id, "Connected devices")
        val t2 = createTransition(sConnected.id, sBluetooth.id, "Bluetooth")
        val t3 = createTransition(sRoot.id, sDisplay.id, "Display")

        // User is currently on Display (sDisplay) and wants Bluetooth (only reachable from root)
        val result = SearchPathEngine.findPathInGraph(
            screens = listOf(sRoot, sConnected, sBluetooth, sDisplay),
            transitions = listOf(t1, t2, t3),
            currentScreenId = sDisplay.id,
            destinationScreen = "Bluetooth",
            exactTask = "Enable Bluetooth",
            appDisplayName = "Settings"
        )

        assertTrue("Fallback path from root should be found", result.found)
        assertEquals("Connected devices -> Bluetooth", result.pathString)
        assertEquals(listOf("Connected devices", "Bluetooth"), result.steps)
        assertEquals(sRoot.id, result.fromScreenId)
    }

    @Test
    fun testWhatsApp4StepChatBackupPath() {
        val sChats = createScreen("com.whatsapp::Chats", pkg = "com.whatsapp", title = "Chats", elements = listOf("More options", "Camera", "Search", "Chats", "Updates"))
        val sMenu = createScreen("com.whatsapp::Menu", pkg = "com.whatsapp", title = "Menu", elements = listOf("New group", "Settings"))
        val sSettings = createScreen("com.whatsapp::Settings", pkg = "com.whatsapp", title = "Settings", elements = listOf("Account", "Privacy", "Chats", "Notifications"))
        val sChatsSettings = createScreen("com.whatsapp::Chats Settings", pkg = "com.whatsapp", title = "Chats Settings", elements = listOf("Theme", "Wallpaper", "Chat backup", "Chat history"))
        val sBackup = createScreen("com.whatsapp::Chat backup", pkg = "com.whatsapp", title = "Chat backup", elements = listOf("BACK UP", "Google Account"))

        val t1 = createTransition(sChats.id, sMenu.id, "More options")
        val t2 = createTransition(sMenu.id, sSettings.id, "Settings")
        val t3 = createTransition(sSettings.id, sChatsSettings.id, "Chats")
        val t4 = createTransition(sChatsSettings.id, sBackup.id, "Chat backup")

        val result = SearchPathEngine.findPathInGraph(
            screens = listOf(sChats, sMenu, sSettings, sChatsSettings, sBackup),
            transitions = listOf(t1, t2, t3, t4),
            currentScreenId = sChats.id,
            destinationScreen = "chats_settings",
            exactTask = "chat_backup",
            appDisplayName = "WhatsApp",
            packageName = "com.whatsapp"
        )

        assertTrue("WhatsApp 4-step chat backup path should be found", result.found)
        assertEquals("More options -> Settings -> Chats -> Chat backup", result.pathString)
        assertEquals(listOf("More options", "Settings", "Chats", "Chat backup"), result.steps)
    }

    @Test
    fun testRootScreenDeepTargetShortCircuitGuard() {
        // Even if sChats was corrupted with 'Chat backup' in its elements, the guard must reject a 1-step shortcut
        // and find the full multi-hop traversal to the dedicated sub-screen!
        val sChatsCorrupted = createScreen("com.whatsapp::Chats", pkg = "com.whatsapp", title = "Chats", elements = listOf("More options", "Chat backup"))
        val sMenu = createScreen("com.whatsapp::Menu", pkg = "com.whatsapp", title = "Menu", elements = listOf("Settings"))
        val sSettings = createScreen("com.whatsapp::Settings", pkg = "com.whatsapp", title = "Settings", elements = listOf("Chats"))
        val sChatsSettings = createScreen("com.whatsapp::Chats Settings", pkg = "com.whatsapp", title = "Chats Settings", elements = listOf("Chat backup"))

        val t1 = createTransition(sChatsCorrupted.id, sMenu.id, "More options")
        val t2 = createTransition(sMenu.id, sSettings.id, "Settings")
        val t3 = createTransition(sSettings.id, sChatsSettings.id, "Chats")

        val result = SearchPathEngine.findPathInGraph(
            screens = listOf(sChatsCorrupted, sMenu, sSettings, sChatsSettings),
            transitions = listOf(t1, t2, t3),
            currentScreenId = sChatsCorrupted.id,
            destinationScreen = "chats_settings",
            exactTask = "chat_backup",
            appDisplayName = "WhatsApp",
            packageName = "com.whatsapp"
        )

        assertTrue("Should route via 3-step hierarchy rather than collapsing onto root screen", result.found)
        assertEquals("More options -> Settings -> Chats -> Chat backup", result.pathString)
        assertEquals(listOf("More options", "Settings", "Chats", "Chat backup"), result.steps)
    }

    // [PERMANENT FIX] Verify Dijkstra unwinds from a sub-screen using BACK transition to reach destination
    @Test
    fun testSubScreenBackUnwindingToTargetDestination() {
        val sRoot = createScreen("com.android.settings::Settings", title = "Settings", elements = listOf("About phone", "Display & brightness"))
        val sAbout = createScreen("com.android.settings::About phone", title = "About phone", elements = listOf("Device name", "Status"))
        val sDisplay = createScreen("com.android.settings::Display & brightness", title = "Display & brightness", elements = listOf("Screen refresh rate", "Eye comfort"))

        val tForwardAbout = createTransition(sRoot.id, sAbout.id, "About phone")
        val tReturnAbout = createTransition(sAbout.id, sRoot.id, "BACK")
        val tForwardDisplay = createTransition(sRoot.id, sDisplay.id, "Display & brightness")

        val result = SearchPathEngine.findPathInGraph(
            screens = listOf(sRoot, sAbout, sDisplay),
            transitions = listOf(tForwardAbout, tReturnAbout, tForwardDisplay),
            currentScreenId = sAbout.id,
            destinationScreen = "display_and_brightness",
            exactTask = "screen_refresh_rate",
            appDisplayName = "Settings",
            packageName = "com.android.settings"
        )

        assertTrue("Path starting from sub-screen with BACK unwinding should be found", result.found)
        assertEquals("BACK -> Display & brightness -> Screen refresh rate", result.pathString)
        assertEquals(listOf("BACK", "Display & brightness", "Screen refresh rate"), result.steps)
        assertEquals(sAbout.id, result.fromScreenId)
    }
}
