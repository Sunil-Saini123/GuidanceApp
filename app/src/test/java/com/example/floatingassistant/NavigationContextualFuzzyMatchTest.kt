package com.example.floatingassistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * [PERMANENT FIX] Do not remove this! Unit tests verifying the Smart Multi-Tiered Contextual Fuzzy Threshold Architecture
 * and Universal Ambiguous Control Guard.
 * Validates:
 * 1. CURRENT_STEP_TAP: Allows compound preference rows (Title + Subtitle) while blocking mismatched targets.
 * 2. FUTURE_STEP_SKIP: Strict 90% threshold + 80% length ratio blocks compound prefix jumping and control words.
 * 3. SCREEN_TITLE: High 85% threshold prevents false root screen claims.
 * 4. AMBIGUOUS_CONTROLS: Generic controls (3-dots, search, menu) must not collide across different screens.
 * 5. FULL_CONTEXT_PROMPT: PromptBuilder injects screen title, graph context, and disambiguation rules.
 */
class NavigationContextualFuzzyMatchTest {

    @Test
    fun testCurrentStepTap_CompoundPreferenceRows_MatchSuccessfully() {
        // WhatsApp compound preference rows concatenating Title + Subtitle
        assertTrue(
            "Tapping 'Chats Chat history, backup' must match step 'Chats'",
            NavigationStateMachine.fuzzyMatch(
                actual = "Chats Chat history, backup",
                expected = "Chats",
                context = NavigationStateMachine.MatchContext.CURRENT_STEP_TAP
            )
        )

        // Settings compound rows with newline
        assertTrue(
            "Tapping 'Display & brightness\\nDark mode, font size' must match 'Display & brightness'",
            NavigationStateMachine.fuzzyMatch(
                actual = "Display & brightness\nDark mode, font size",
                expected = "Display & brightness",
                context = NavigationStateMachine.MatchContext.CURRENT_STEP_TAP
            )
        )

        // Status separator dot
        assertTrue(
            "Tapping 'Wi-Fi · Connected' must match 'Wi-Fi'",
            NavigationStateMachine.fuzzyMatch(
                actual = "Wi-Fi · Connected",
                expected = "Wi-Fi",
                context = NavigationStateMachine.MatchContext.CURRENT_STEP_TAP
            )
        )

        // Singular vs plural prefix tolerance
        assertTrue(
            "Tapping 'Chat settings and history' must match 'Chats'",
            NavigationStateMachine.fuzzyMatch(
                actual = "Chat settings and history",
                expected = "Chats",
                context = NavigationStateMachine.MatchContext.CURRENT_STEP_TAP
            )
        )
    }

    @Test
    fun testCurrentStepTap_MismatchedElements_Rejected() {
        // Tapping an unrelated setting
        assertFalse(
            "Tapping 'Appearance Theme, wallpapers' must NOT match 'Chats'",
            NavigationStateMachine.fuzzyMatch(
                actual = "Appearance Theme, wallpapers",
                expected = "Chats",
                context = NavigationStateMachine.MatchContext.CURRENT_STEP_TAP
            )
        )

        assertFalse(
            "Tapping 'Notifications Message tones' must NOT match 'Chats'",
            NavigationStateMachine.fuzzyMatch(
                actual = "Notifications Message tones",
                expected = "Chats",
                context = NavigationStateMachine.MatchContext.CURRENT_STEP_TAP
            )
        )
    }

    @Test
    fun testFutureStepSkip_CompoundPrefixBlocked() {
        // Crucial invariant: tapping 'Chats Chat history, backup' must NOT match future step 'Chat backup'
        assertFalse(
            "Compound label 'Chats Chat history, backup' must NOT skip ahead to 'Chat backup'",
            NavigationStateMachine.fuzzyMatch(
                actual = "Chats Chat history, backup",
                expected = "Chat backup",
                context = NavigationStateMachine.MatchContext.FUTURE_STEP_SKIP
            )
        )

        assertFalse(
            "Tapping 'More settings' must NOT skip ahead to 'Settings'",
            NavigationStateMachine.fuzzyMatch(
                actual = "More settings",
                expected = "Settings",
                context = NavigationStateMachine.MatchContext.FUTURE_STEP_SKIP
            )
        )
    }

    @Test
    fun testFutureStepSkip_ControlWordsBlocked() {
        // Control word blacklist: system buttons must never match compound steps
        assertFalse(
            "Control word 'Back' must NOT match 'Chat backup'",
            NavigationStateMachine.fuzzyMatch(
                actual = "Back",
                expected = "Chat backup",
                context = NavigationStateMachine.MatchContext.FUTURE_STEP_SKIP
            )
        )

        assertFalse(
            "Control word 'Home' must NOT match 'Home screen & lock screen'",
            NavigationStateMachine.fuzzyMatch(
                actual = "Home",
                expected = "Home screen & lock screen",
                context = NavigationStateMachine.MatchContext.FUTURE_STEP_SKIP
            )
        )
    }

    @Test
    fun testFutureStepSkip_NearIdenticalAllowed() {
        // Near identical step name (e.g. minor plural or exact match) is permitted to skip
        assertTrue(
            "Exact step name must match future step",
            NavigationStateMachine.fuzzyMatch(
                actual = "Chat backup",
                expected = "Chat backup",
                context = NavigationStateMachine.MatchContext.FUTURE_STEP_SKIP
            )
        )

        assertTrue(
            "Plural variation of future step must match",
            NavigationStateMachine.fuzzyMatch(
                actual = "Chat backups",
                expected = "Chat backup",
                context = NavigationStateMachine.MatchContext.FUTURE_STEP_SKIP
            )
        )
    }

    @Test
    fun testScreenTitle_ThresholdsAndPrefixBlocked() {
        // Screen titles must not match via compound prefix
        assertFalse(
            "Screen title 'Chat settings' must NOT match root step 'Chats'",
            NavigationStateMachine.fuzzyMatch(
                actual = "Chat settings",
                expected = "Chats",
                context = NavigationStateMachine.MatchContext.SCREEN_TITLE
            )
        )

        // Exact and plural screen title matches
        assertTrue(
            "Screen title 'Chats' matches step 'Chats'",
            NavigationStateMachine.fuzzyMatch(
                actual = "Chats",
                expected = "Chats",
                context = NavigationStateMachine.MatchContext.SCREEN_TITLE
            )
        )

        assertTrue(
            "Screen title 'Chat setting' matches step 'Chat settings'",
            NavigationStateMachine.fuzzyMatch(
                actual = "Chat setting",
                expected = "Chat settings",
                context = NavigationStateMachine.MatchContext.SCREEN_TITLE
            )
        )
    }

    @Test
    fun testPromptBuilder_FullContextReroutePrompt() {
        val navGraph = JSONObject().apply {
            put("known_screens", JSONArray(listOf("Chats", "Menu", "Settings")))
            val transitions = JSONArray().apply {
                put(JSONObject().apply {
                    put("from", "Chats")
                    put("to", "Menu")
                    put("action", "More options")
                })
            }
            put("known_transitions", transitions)
        }

        val prompt = com.example.floatingassistant.pathgenerator.PromptBuilder.buildGeminiDrivenPrompt(
            "com.whatsapp",
            "block Rohit",
            "{\"elements\":[{\"name\":\"More options\",\"is_clickable\":true}]}",
            JSONObject(),
            true,
            "Chats -> Alice -> More options",
            "Alice",
            navGraph
        )

        assertTrue("Prompt must include active screen title", prompt.contains("Current Screen Title: \"Alice\""))
        assertTrue("Prompt must include verified local app navigation graph", prompt.contains("Verified Local App Navigation Graph (.db):"))
        assertTrue("Prompt must instruct AI to disambiguate generic controls", prompt.contains("Disambiguate Generic & Ambiguous Controls"))
        assertTrue("Prompt must include failed path memory", prompt.contains("Chats -> Alice -> More options"))
    }
}
