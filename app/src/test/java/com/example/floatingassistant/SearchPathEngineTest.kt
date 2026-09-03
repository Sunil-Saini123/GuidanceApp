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
        val sA = createScreen("com.test::A", title = "A")
        val sB = createScreen("com.test::B", title = "B")
        val sC = createScreen("com.test::C", title = "C")
        val sD = createScreen("com.test::D", title = "D")

        // Cycle: A -> B -> A
        val tAB = createTransition(sA.id, sB.id, "Go to B")
        val tBA = createTransition(sB.id, sA.id, "Go back to A")

        // Direct path: B -> C
        val tBC = createTransition(sB.id, sC.id, "Go to C")

        // Longer path: A -> D -> C
        val tAD = createTransition(sA.id, sD.id, "Go to D", weight = 5.0)
        val tDC = createTransition(sD.id, sC.id, "Go from D to C", weight = 5.0)

        val result = SearchPathEngine.findPathInGraph(
            screens = listOf(sA, sB, sC, sD),
            transitions = listOf(tAB, tBA, tBC, tAD, tDC),
            currentScreenId = sA.id,
            destinationScreen = "C",
            exactTask = null
        )

        assertTrue(result.found)
        assertEquals("Go to B -> Go to C", result.pathString)
        assertEquals(listOf("Go to B", "Go to C"), result.steps)
    }

    @Test
    fun testAlreadyOnDestinationScreen() {
        val s1 = createScreen("com.whatsapp::Chats", pkg = "com.whatsapp", title = "Chats")

        val result = SearchPathEngine.findPathInGraph(
            screens = listOf(s1),
            transitions = emptyList(),
            currentScreenId = s1.id,
            destinationScreen = "Chats",
            exactTask = null
        )

        assertTrue(result.found)
        assertEquals("Chats", result.pathString)
        assertTrue(result.message.contains("Already on target screen"))
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
}
