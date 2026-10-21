# GuidanceApp - Workspace Rules & Operating Directives

> **Version:** 1.0.0  
> **Status:** Active & Enforced  
> **Target Device:** Realme RMX3870 (ColorOS / Android 16 / AOSP Custom ROM variants)  
> **Last Updated:** 2026-10-20  

These rules are strictly enforced across all code changes, architecture decisions, and debugging sessions in this workspace. They prevent regressions, eliminate hallucinations, protect device performance, and maintain exact alignment with the user.

---

## 1. User Directives & Absolute Boundaries

1. **NO Autonomous Build or Install:**
   - **NEVER** run `./gradlew installDebug`, `./gradlew build`, `adb install`, or push APK updates to the device.
   - The user will build, compile, and deploy to their phone themselves.
2. **Permanent Fix Preservation (`[PERMANENT FIX]`):**
   - Every bug fix MUST be marked with:  
     `// [PERMANENT FIX] Do not remove this! <Brief explanation of what was killed>`
   - **NEVER** remove, revert, or simplify an existing `[PERMANENT FIX]` block when addressing subsequent issues.
   - If a new requirement conflicts with a previous fix, highlight the conflict explicitly and ask the user before touching that code.
3. **Step-by-Step Problem Resolution:**
   - Address **one problem at a time**.
   - Explain the root cause in simple, plain words with real-world examples first.
   - Explain the proposed solution and wait for user confirmation ("fix it") before editing code.
4. **Zero Ghost Edits & Placeholders:**
   - No mock/dummy implementations presented as complete.
   - No dead code, speculative rewrites, or unverified stub APIs.
   - Preserve all existing comments and docstrings unrelated to code changes.
5. **No Logcat Noise / Spam:**
   - Do NOT emit high-frequency log lines on every coordinate update, scroll tick, or marker repositioning.
   - Keep log tags focused, informative, and clean.

---

## 2. File System & Storage Directory Integrity

1. **Unified Storage Directory (`context.filesDir`):**
   - All runtime files (`clean_page.json`, `temp_tree.json`, `nav_graph.json`, `nav_graph.db`) MUST be stored in internal app memory: `context.filesDir`.
   - **NEVER** mix `getExternalFilesDir(null)` with `context.filesDir`. Mismatched paths cause components (`ElementFinderEngine`) to look in the wrong directory and fail.
2. **Throttle Heavy Disk I/O:**
   - Never write full database dumps or multi-app JSON snapshots (`nav_graph.json`) to physical flash storage during active scrolling.
   - Disk writes must be debounced or restricted to complete navigation transitions and idle states.

---

## 3. Android Accessibility & Window Management Rules

1. **Mandatory Native Binder Recycling & Single Ownership:**
   - Every `AccessibilityNodeInfo` obtained via `rootInActiveWindow`, `event.source`, `window.root`, `findAccessibilityNodeInfosByViewId()`, or `node.getChild(i)` MUST be recycled with `node.recycle()`.
   - Use `try/finally` blocks around node traversals (including `RawDumpWriter.supplementalSearch`). Failing to recycle nodes causes native Binder buffer exhaustion (`DeadObjectException`) and kills the service.
   - **Single Ownership of Node Lifecycle:** The component that allocates/receives the node (`UiTreeAccessibilityService`) is its sole lifecycle owner. Filter helpers such as `MainFilter.apply()` must never recycle nodes passed into them, preventing native double-recycle pool corruption.
2. **Short-Edges Notch & Cutout Handling:**
   - All overlay windows (`ElementMarkerOverlay`, `NavigationHudOverlay`) must set:
     `layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`
   - Overlay types must prioritize `TYPE_ACCESSIBILITY_OVERLAY` when run within the accessibility service context, falling back to `TYPE_APPLICATION_OVERLAY`.
3. **Strict Touch Pass-Through on Markers:**
   - `ElementMarkerOverlay` must always maintain:
     `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`
   - The user must always be able to interact with and tap the underlying Android UI unobstructed.
4. **OEM App Name & Screen Title Extraction (ColorOS / Aggressive ROMs):**
   - Sub-pages on ColorOS often report the parent activity/package title (e.g. "Settings").
   - Prefer user-visible toolbar title (`findPageTitle`) over generic window root names.
   - Strip developer resource ID prefixes (`bbk_`, `sec_`, `originui_`, `miui_`, `vivo_`, `oppo_`, `coloros_`).
5. **Underlying App Window Resolution (`AppWindowResolver`):**
   - When inspecting page titles, screen roots, or toolbar text during active overlay interactions (such as inside `NavigationStateMachine.startNavigation()` or `onScreenChanged()`), **NEVER** inspect `rootInActiveWindow` directly because it returns GuidanceApp's own overlay window.
   - Always resolve via `AppWindowResolver.getUnderlyingAppRoot(uiTree, context.packageName)` and recycle the returned root.
   - Prioritize focused/active `TYPE_APPLICATION` windows over background/inactive windows to support split-screen and multi-window environments.
6. **Development Accessibility Self-Activation (`WRITE_SECURE_SETTINGS`):**
   - In `MainActivity.kt`, `ensureAccessibilityServiceEnabled()` checks and self-activates `UiTreeAccessibilityService` via `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` and `ACCESSIBILITY_ENABLED` if `WRITE_SECURE_SETTINGS` is granted.
   - Prevents having to manually grant accessibility permission in Android Settings on every app update/reinstall during development.
   - One-time grant via `enable_permissions.bat` or `adb shell pm grant com.example.floatingassistant android.permission.WRITE_SECURE_SETTINGS`.
7. **Universal Row-Block Expansion & Competing Sibling Protection:**
   - In `ElementFinderEngine.findElementBoundsInLiveTree`, when a matched text node belongs to a compound row (e.g. Setting row with status/chevron, WhatsApp chat list item), dynamically ascend up to 5 parent levels as long as height $\le 350$px.
   - **Competing Sibling Protection:** If the ancestor container holds other independent clickable actions (e.g. ButtonBar 'Cancel' vs 'OK', Calculator grid, Bottom navigation tabs), expansion is immediately blocked to keep the highlight strictly locked onto the individual target.
   - Standalone icons (toolbar back arrow, close button) are protected from expanding into the navigation bar.
8. **Universal Screen Title Resolution (`ScreenTitleResolver`):**
   - Screen naming across `MainFilter`, `NavigationStateMachine`, and `GraphStateMachine` MUST use `ScreenTitleResolver`.
   - **Toolbar / Header Supremacy:** Sub-screens with a Toolbar/ActionBar or Back-Button anchor prioritize the header title above all else.
   - **True Navigation Tabs Only:** Selected tab matching is strictly restricted to recognized navigation containers (`TabLayout`, `BottomNavigationView`, `NavigationBarView`) on root screens without a back button. It MUST NEVER inspect arbitrary `isSelected == true` nodes like SIM card slots (`"No SIM cardNot set"`) or filter chips.

---

## 4. Scroll Mediator & Real-Time Performance Rules

1. **Separation of Marker vs. Graph Pipelines:**
   - **Fast-Track Marker Pipeline (RAM):** Runs on a 30ms fast-track debounce directly from live accessibility tree coordinates (`findElementBoundsInLiveTree`). Moves the visual marker with zero disk I/O.
   - **Scroll Mediator Pipeline (Diffing):** Runs in background. Checks new visible elements against the in-memory `screenElementCache`.
2. **Delta Diffing Enforcement:**
   - If 0 new elements appeared on scroll: **Action: Ignore completely** (`SKIPPED_NO_CHANGE`). No database update, no disk write.
   - If new elements appeared: **Action: Add only the new delta elements** (`DELTA_ADDED`).
3. **In-Memory Scroll Detection & Non-Blocking UI:**
   - Scrollability checks (`hasScrollableContainer`) must run on background coroutines (`Dispatchers.IO`) and prioritize Layer 0 in-memory inspection (`node.isScrollable`, Compose/OEM scrollables) over physical disk reads.
   - Never perform synchronous disk file reads or JSON tree parsing on the Android Main UI Thread.

---

## 5. Navigation & Multi-Tier Pathfinding Rules

1. **No Partial Path Uploading to Cloud Firestore (Tier 2):**
   - Paths generated via mid-screen "Reroutes" or inside an app sub-page are partial paths.
   - **NEVER** upload partial paths to `CloudPathDatabase`. Only full paths originating from launcher/root can be saved to cloud storage.
   - Guarded in both `NavigationStateMachine.handleSuccess` and `FloatingOverlayService.handleStop` (`pendingGroqIsPartial`).
2. **AI Reroute Memory (Tier 3 Groq):**
   - When Reroute is clicked, the failed path (`pendingGroqPath`) MUST be passed to `PromptBuilder` to explicitly forbid the AI from repeating the hallucinated or broken step.
   - Reroute prompts must use fresh live screen node dumps (`extractSync`) rather than stale disk cache.
3. **Marker Lifecycle Management:**
   - When navigation reaches the final step (`nextStep == null`), the visual marker MUST be hidden immediately (`marker?.hideMarker()`).
   - When navigation is stopped manually via HUD or Stop button, force cleanup of marker and HUD regardless of whether `isNavigating` is true or false.
4. **Dijkstra Sub-Screen Routing & Root Fallback:**
   - Tier 1 graph searches must prioritize the user's active sub-screen (`resolveStartScreen(screens, transitions, currentScreenId, appDisplayName)`).
   - If the target is unreachable forward from the sub-screen, Dijkstra must fall back to the app's root home screen (`resolveRootScreen`), preventing false path misses.
5. **Tier 2 Dual-Key Sync & Model-Aware Signature:**
   - Cloud Firestore paths MUST be stored and queried with dual-key aliases (both canonical app label and raw package name).
   - This ensures queries using human-readable names (`"Settings"`) match entries uploaded with package names (`"com.android.settings"`).
   - **Model-Aware Signatures:** Signatures are constructed hierarchically using `<brand>_<model>_<android_version>` (e.g. `realme_rmx3870_16`) to account for hardware differences across models (e.g. 120Hz AMOLED vs 60Hz LCD), falling back gracefully to brand-level (`realme_16`) and legacy signatures.
6. **Resilient LLM JSON Extraction (Tier 3 Groq):**
   - LLM responses must never be assumed to be pure raw JSON.
   - Response parsers (`GroqResponseParser`) must extract JSON across markdown code fences (````json ... ````), tolerate conversational preambles/postscripts, support direct JSON arrays (`[...]`) as well as objects (`{...}`), and strip bullet points or numeric indices from step names.
7. **Resilient Natural-Language Intent Parsing (`GeminiCommandParser`):**
   - Model intent responses can include markdown bullets (`-`, `*`, `•`), bold styling (`**app:**`), numbered lists (`1.`), or surrounding quotes.
   - `GeminiCommandParser.parseResponse()` must clean formatting noise before extracting `app:`, `end_page:`, and `task:`, preventing false drops to offline keyword fallback.
8. **Target In-View Guard Against False Off-Track:**
   - When an element is tapped that does not match `currStep` or a future step (`NavigationStateMachine.onElementTapped`), the system must first check whether `currStep` is still visible in the live memory tree (`findElementBoundsInLiveTree`).
   - If the target is still visible on screen, false `WRONG PATH` alarms and marker hiding MUST be suppressed.

---

## 6. Telemetry & Live Dashboard Rules

1. **Non-Blocking Telemetry:**
   - All telemetry emissions via `DebugTelemetryClient` must run on background coroutines (`Dispatchers.IO`) with short timeouts (connect: 800ms, read: 1500ms).
   - Telemetry failures must fail silently without ever interrupting core app execution.
2. **User-Controlled Dashboard:**
   - Do NOT force-open Chrome on the host laptop unless requested by the user.
   - The dashboard must reflect real device connection state (`🟢 PHONE CONNECTED` vs `🔴 NO PHONE OR APP CONNECTED`) based on live heartbeats.

---

## 7. Anti-Hallucination & Code Edit Protocol

1. **Inspect Before Changing:**
   - Always read the actual target file and surrounding context using tools before making any modification.
   - Do not guess about function signatures, class members, imports, or variable names.
2. **Exact Drop-in Replacements:**
   - When using replacement tools, targets must match existing code whitespace and characters exactly.
   - Verify changes immediately after editing.
3. **Continuous Updating:**
   - As new bugs, OEM edge cases, or architectural patterns are discovered, this `GEMINI.md` document must be updated to keep all rules active and persistent.
