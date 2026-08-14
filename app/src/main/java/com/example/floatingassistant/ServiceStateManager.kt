package com.example.floatingassistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton that holds the shared ON/OFF state for the UI-tree capture loop.
 *
 * Using a [MutableStateFlow] means:
 *  - MainActivity can write to it when the switch is toggled.
 *  - The AccessibilityService (Phase 2+) can observe it reactively without polling.
 *  - No locks / synchronization primitives needed for simple boolean reads on the hot path.
 */
object ServiceStateManager {

    private val _isServiceEnabled = MutableStateFlow(false)

    /** Publicly-exposed read-only view of the enabled state. */
    val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()

    /** Called by MainActivity when the user toggles the switch. */
    fun setEnabled(enabled: Boolean) {
        _isServiceEnabled.value = enabled
    }
}
