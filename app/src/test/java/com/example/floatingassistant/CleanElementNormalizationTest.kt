package com.example.floatingassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [PERMANENT FIX] Do not remove this! Unit tests verifying Slider Normalization and Static Suggestion Noise Filtering.
 * Validates:
 * 1. "895,Brightness" -> normalized to "Brightness" (intelligent update, not deletion).
 * 2. "50%, Volume" -> normalized to "Volume".
 * 3. "Brightness, 895" -> normalized to "Brightness".
 * 4. "You might be looking for:" -> flagged as noise element.
 * 5. Standalone status words like "Off" -> flagged as noise element.
 */
class CleanElementNormalizationTest {

    @Test
    fun testSliderNumberNormalization() {
        assertEquals("Brightness", CleanPageProcessor.cleanSliderOrControlName("895,Brightness"))
        assertEquals("Brightness", CleanPageProcessor.cleanSliderOrControlName("895, Brightness"))
        assertEquals("Brightness", CleanPageProcessor.cleanSliderOrControlName("50%, Brightness"))
        assertEquals("Brightness", CleanPageProcessor.cleanSliderOrControlName("Brightness, 895"))
        assertEquals("Volume", CleanPageProcessor.cleanSliderOrControlName("70%, Volume"))
    }

    @Test
    fun testNoiseFilter_SuggestionHeaders() {
        assertTrue(NavGraphDatabase.isNoiseElement("You might be looking for:"))
        assertTrue(NavGraphDatabase.isNoiseElement("You might be looking for"))
        assertTrue(NavGraphDatabase.isNoiseElement("Looking for other settings?"))
        assertTrue(NavGraphDatabase.isNoiseElement("Need help?"))
    }

    @Test
    fun testNoiseFilter_StatusWords() {
        assertTrue(NavGraphDatabase.isNoiseElement("Off"))
        assertTrue(NavGraphDatabase.isNoiseElement("On"))
        assertTrue(NavGraphDatabase.isNoiseElement("Disabled"))
        assertTrue(NavGraphDatabase.isNoiseElement("Connected"))
    }

    @Test
    fun testRealElements_AreNotNoise() {
        assertFalse(NavGraphDatabase.isNoiseElement("Brightness"))
        assertFalse(NavGraphDatabase.isNoiseElement("Screen refresh rate"))
        assertFalse(NavGraphDatabase.isNoiseElement("Dark mode"))
        assertFalse(NavGraphDatabase.isNoiseElement("Wi-Fi"))
    }
}
