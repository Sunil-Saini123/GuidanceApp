package com.example.floatingassistant

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NavigationStateMachine — Phase 7
 *
 * Minimal state machine that owns the "currently guiding" state. It is the
 * integration point between:
 *   - [FloatingOverlayService] (submits a resolved path string, or stops it)
 *   - a future consumer (the Accessibility Service) that will watch [state]
 *     to find/highlight the live on-screen element matching the current step.
 *
 * Scope of this phase: track *which step we're on* and expose it reactively.
 * Actual on-screen highlight rendering (finding the AccessibilityNodeInfo that
 * matches the current step's name and drawing an overlay around it) is NOT
 * implemented here — that is a distinct follow-up phase. [stop] still clears
 * this state machine's data immediately, which is the signal any future
 * highlight-renderer would use to remove active highlights.
 */
object NavigationStateMachine {

    private const val TAG = "NavStateMachine"

    sealed class State {
        /** No guide is running. */
        object Idle : State()

        /**
         * A guide is actively running.
         * @param steps        Ordered list of step names, e.g. ["WhatsApp", "3 dots", "Settings", ...]
         * @param currentIndex Index into [steps] of the step currently being guided.
         */
        data class Running(val steps: List<String>, val currentIndex: Int) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Begin guiding the user through [pathString], e.g.
     * "WhatsApp -> 3 dots -> Settings -> Profile -> Change Profile"
     *
     * Splits on "->", trims each step, drops blanks. No-op (with a warning log)
     * if the resulting step list is empty.
     */
    fun start(pathString: String) {
        val steps = pathString
            .split("->")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (steps.isEmpty()) {
            Log.w(TAG, "start() called with empty/blank path — ignored")
            return
        }

        _state.value = State.Running(steps = steps, currentIndex = 0)
        Log.i(TAG, "Guide STARTED — ${steps.size} steps: ${steps.joinToString(" → ")}")
        Log.i(TAG, "Step 1/${steps.size}: \"${steps[0]}\" ← highlight target (future phase will render this)")
    }

    /**
     * Advance to the next step. Automatically returns to [State.Idle] once the
     * last step has been passed. No-op if not currently [State.Running].
     */
    fun advance() {
        val current = _state.value
        if (current !is State.Running) return

        val nextIndex = current.currentIndex + 1
        if (nextIndex >= current.steps.size) {
            Log.i(TAG, "Guide COMPLETE — all ${current.steps.size} steps done")
            _state.value = State.Idle
            return
        }

        _state.value = current.copy(currentIndex = nextIndex)
        Log.i(TAG, "Step ${nextIndex + 1}/${current.steps.size}: \"${current.steps[nextIndex]}\"")
    }

    /**
     * Instantly stop any running guide: resets to [State.Idle].
     * This is the single source of truth for "clear active highlights" —
     * any future highlight-rendering consumer observes [state] and removes
     * its overlay the moment this flips back to Idle.
     */
    fun stop() {
        val wasRunning = _state.value is State.Running
        _state.value = State.Idle
        if (wasRunning) {
            Log.i(TAG, "Guide STOPPED — state reset, highlights cleared")
        }
    }

    /** Convenience accessor: the step name currently being guided, or null if idle. */
    fun currentStepOrNull(): String? =
        (_state.value as? State.Running)?.let { it.steps.getOrNull(it.currentIndex) }

    fun isRunning(): Boolean = _state.value is State.Running
}