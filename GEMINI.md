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
6. **NO Autonomous Git Push:**
   - **NEVER** push commits to GitHub (`git push`) automatically.
   - Only push when the user explicitly instructs to push.
7. **Compulsory Chained Feature & Non-Regression Invariant ($N-1$ Rule):**
   - **Strict Cumulative Preservation:** All features and bug fixes operate in an unbroken, cumulative chain. When working on feature or issue $N$, all previous $1, 2, \dots, N-1$ features, architectural contracts, and bug fixes MUST remain 100% active, functional, and intact.
   - **Zero Regression Tolerance:** Implementing a new capability or fixing a bug must NEVER break, simplify, bypass, or degrade any prior functionality.
   - **Pre-Change Invariant Verification:** Before touching any code, cross-check against the entire chain of established behaviors (live tree supremacy, active node lock, zero-flicker back unwinding, binder recycling, directional scrolling, flight recording) to guarantee that the change harmonizes without introducing regressions.
   - **Cumulative Contract:** If a new requirement appears to conflict with an existing ($N-1$) behavior, you must explicitly highlight the conflict and ask the user for direction before making changes.

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
   - Always resolve via `AppWindowResolver.getUnderlyingAppRoot(uiTree)` and recycle the returned root.
   - Prioritizes focused/active `TYPE_APPLICATION` windows (Activities) over background windows to support split-screen and multi-window environments. Floating overlays are `TYPE_APPLICATION_OVERLAY` or `TYPE_ACCESSIBILITY_OVERLAY` and are never matched as application roots.
   - GuidanceApp's `MainActivity` is recognized as a valid `TYPE_APPLICATION` activity so that starting navigation from within GuidanceApp triggers `WRONG APP: Open <TargetApp>` rather than inspecting paused background activities.
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
9. **System Navigation Bar & Visible Viewport Clamping:**
   - In `ElementMarkerOverlay`, visual highlight bounds must be clamped strictly above the phone's 3-button system navigation bar (Back, Home, Recents) and below the status bar (`WindowInsets.Type.systemBars()`).
   - If a target element is pushed behind the navigation bar (e.g. at the bottom edge of a scroll view), the marker hides to prevent phantom boxes.
   - `MarkerView.onDraw()` enforces canvas clipping (`canvas.clipRect`) to guarantee the green rectangle and fill never draw over the phone's system navigation buttons.

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
4. **Dedicated Marker Scout Worker (`MarkerScout`):**
   - Visual element tracking is isolated into an independent worker (`MarkerScout`) supervised by `NavigationStateMachine`.
   - `MarkerScout` is assigned the current target and tracks its coordinates reactively on screen motion (scrolls, page transitions).
   - In-flight scout searches are cleanly cancelled upon step advancement (`scoutJob?.cancel()`) or stop (`shutdown()`), discarding stale step results and eliminating race conditions, ghost markers, and native memory leaks.

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
9. **Cross-Package Transition Tracking & Connected Cluster Dijkstra:**
   - When an action in Package A launches an Activity in Package B (e.g. Settings launching `com.oplus.wirelesssettings` or `com.oplus.battery`), `GraphStateMachine` detects the recent click and records both the forward bridge edge and the reverse `BACK` edge in SQLite.
   - `SearchPathEngine` and `ElementFinderEngine` use recursive CTE queries (`getConnectedScreens`, `getConnectedTransitions`) to navigate the entire connected OEM satellite cluster.
10. **Screen Title Canonicalization & Duplicate Merging:**
    - OEM home variants (e.g. `Oplus Settings Homepage`, `Settings Homepage`, `Oplus Settings`) are canonicalized to standard app titles (`Settings`) by `ScreenTitleResolver.canonicalizeTitle()`.
    - `NavGraphDatabase.mergeDuplicateSettingsScreens()` migrates all transitions and elements into the canonical screen on startup, eliminating disconnected split-brain nodes.
11. **Keystroke & Ghost Screen Suppression:**
    - Editable `EditText` fields in toolbars/headers represent active user typing and MUST NEVER become screen titles. Search screens without a static header are titled `"Search"`.
    - Dialog backdrop containers (`"Touch Outside"`, `"Touch outside to dismiss"`, `"scrim"`) are filtered from screen resolution, and `pruneKeystrokeScreens()` collapses fake typing chains into direct search transitions.
12. **Typographic Normalization & Dynamic Suffix Stripping:**
    - `ElementFinderEngine` and `SearchPathEngine` normalize Unicode typographic hyphens (`\u2011`, `\u2012`, `\u2013`, `\u2014`, `\u2212`) and non-breaking spaces (`\u00A0`, `\u202F`) to standard ASCII characters before comparing.
    - Suffixes after ` · ` (network/contact status), unread badge counts (e.g. `"Spam 15"` -> `"Spam"`), and trailing toggle states (`" High"`, `" Off"`) are stripped so live UI elements match stored transition edges regardless of state changes.
13. **Mandatory Bidirectional Return Transitions & Dead-End Sink Prevention:**
    - Every forward `CLICK` transition $A \to B$ represents an Activity or Fragment push. In Android, the user can always press Back to return to $A$.
    - `GraphStateMachine` automatically records the inverse return edge $B -[BACK]\to A$ whenever $A -[CLICK]\to B$ is recorded.
    - `NavGraphDatabase.repairBidirectionalTransitions()` auto-heals pre-existing dead-end sinks on startup, ensuring Dijkstra can always route backward out of leaf screens (e.g. `Spam`, `Chat backup`, `Your screen time`, `ODialer`).
14. **Screen Header Title Suppression, Structural Noise Filtering & Base-Label Deduplication:**
    - Static non-clickable toolbar/header titles (e.g. `toolbar_title`, `action_bar_title`) are dropped during extraction (`CleanPageProcessor.extractCleanElements`).
    - Passive spatial layout containers (`"Bottom_Bar"`, `"Action_Bar"`, `"Scrollable_List"`) representing phone navigation/gesture bars or full-width docking containers are filtered out of extraction and database insertion.
    - `NavGraphDatabase.upsertScreen()` and `mergeElements()` actively filter out redundant screen titles (e.g. `"Settings"` under `Settings`), search noise (`"Search box"`), and structural containers (`"Bottom_Bar"`).
    - Elements are deduplicated by their base label (`extractBaseElementLabel()`), merging transient variants like `"Wi-Fi · Vidyalankar Campus"` and `"Wi-Fi Off"` into the single canonical `"Wi-Fi"` element.
    - `NavGraphDatabase.deduplicateScreenElements()` automatically heals pre-existing screen records on startup.
15. **Local Dijkstra Dual-Intent Resolution & Query Normalization:**
    - `SearchPathEngine.resolveTarget()` matches across BOTH `destinationScreen` (`end_page`) and `exactTask` (`task`).
    - Strips snake_case underscores, generic UI suffixes (`_settings`, `_page`, `_screen`, `_tab`, `_menu`, `_section`), and imperative action prefixes (`set_`, `change_`, `toggle_`, `update_`, `enable_`, `disable_`).
    - Prioritizes specific `exactTask` element targets over generic screen categories so deep leaf elements (e.g. `Screen refresh rate` under `Display & brightness`) are reached via Tier 1 local Dijkstra rather than falling back to Tier 2 Cloud DB.
16. **Authoritative Live Foreground App Resolution & WRONG APP Start Enforcement:**
    - `NavigationStateMachine.startNavigation()` resolves the foreground application and screen title directly from `service.windows` at the exact instant navigation begins.
    - If the user initiates navigation from within GuidanceApp's own `MainActivity` or any third-party app that is not the target app, the state machine triggers `WRONG APP: Open <TargetApp>` at step 0 (`startIdx = 0`), hiding markers and preventing false smart fast-forwarding onto background apps.
    - `UiTreeAccessibilityService` detects silent app switches to GuidanceApp's `MainActivity` and updates navigation state to `WRONG APP`.
17. **Live Tree Supremacy, Stale Snapshot Rejection & Two-Phase Wrong Tap Tracking:**
    - In `ElementFinderEngine.findElement()`, an element can ONLY be reported as `found = true` if it is physically visible in the phone's live accessibility tree in memory right now (`findElementBoundsInLiveTree != null`).
    - Stale disk snapshots (`clean_page.json` and `temp_tree.json`) lag behind screen transitions and must NEVER be used to claim an element is on screen with phantom coordinates. Returning `found = true` from stale disk snapshots falsely turns the HUD green (`Found on screen`), suppressing wrong-path alerts and scroll directives.
    - In `NavigationStateMachine.onElementTapped()`, system navigation buttons are matched by exact name (`ignoredExactLabels.contains(tapNorm)`), preventing menu items like `"Home screen, Lock screen & style"` from being ignored as the Android Home button.
    - When any non-target option is clicked, `lastWrongTapText` is tracked immediately. If the user transitions to an unrecognized screen, the HUD surfaces `WRONG PATH: You tapped '<label>'` and hides the marker, maintaining `isCurrentScreenUnrecognized` across scroll events.
18. **Persistent Wrong-Path State, Root Substring Exclusion & Target In-Context Guard:**
    - **No Substring Hijacking for Root Screens:** In `NavigationStateMachine`, `isRootScreen` must strictly match exact root titles (`targetAppLabel`, `"Settings"`, `"home"`, or explicit home suffixes). Sub-screens with `"setting"` or `"settings"` in their title (e.g. `"SOS Helper Setting"`, `"Privacy settings"`, `"More settings"`) must NEVER match as `isRootScreen`, preventing them from falsely claiming `isOnKnownPath = true` and wiping out wrong-path tracking.
    - **Persistent Wrong-Turn Lock:** In `onElementTapped()`, tapping a non-target element immediately sets `isCurrentScreenUnrecognized = true`. `lastWrongTapText` does NOT expire after 5 seconds; it persists until the user navigates back to a known path screen (`isOnKnownPath == true`).
    - **Target In-Context Guard Against False Scrolling:** In `triggerSearchPipeline()`, if `isOffTrack` (`isUnrecognizedScreen || isCurrentScreenUnrecognized || lastWrongTapText != null`) is true, the HUD strictly displays `WRONG PATH` (`OFF_TRACK`) and is forbidden from falling through to `hasScrollable` (`Scroll to find...`).
    - **Connected Cluster Recognition in `isTargetApp`:** `isTargetApp()` queries `NavGraphDatabase.getConnectedScreens(targetPackage)` (cached in `connectedTargetPackages`) to recognize OEM satellite sub-packages (`com.oplus.sos`, `com.oplus.wallpapers`, `com.oplus.wirelesssettings`, `com.oplus.battery`) as valid components of the target app cluster.
    - **Compound Preference Container Text Extraction:** In `UiTreeAccessibilityService.TYPE_VIEW_CLICKED`, when a container view has empty text, child nodes are traversed up to depth 3 with strict native Binder recycling to reliably capture clicked labels from complex preference rows.
    - **Back Button & Null Tap Protection:** In `UiTreeAccessibilityService.TYPE_VIEW_CLICKED`, clicks on icon buttons with empty/null text collections filter out literal `"null"` and `"nil"` strings. In `NavigationStateMachine.onElementTapped()`, navigation actions (`"back"`, `"navigate up"`, `"null"`, `"nil"`) return immediately and are never recorded as wrong taps, eliminating transient "You tapped 'null'" flashes when returning from an off-track screen.
19. **Directional Scroll Guidance & Homepage Search Noise Suppression:**
    - **Multi-Source Directional Scroll Intelligence (`determineScrollDirection`):**
      When target element is off-screen, scroll direction is determined hierarchically without blind defaulting:
      1. **Native Scroll Container Boundaries:** Inspects live scrollable container's native actions in RAM (`AccessibilityNodeInfo.ACTION_SCROLL_FORWARD` vs `ACTION_SCROLL_BACKWARD`). If at the bottom (`!canScrollForward && canScrollBackward`), scrolling down is physically impossible $\to$ forces direction UP. If at the top (`!canScrollBackward && canScrollForward`), forces direction DOWN.
      2. **Relative Element Order Intelligence:** Compares the target element index against the average index of currently visible elements on screen (from `NavGraphDatabase` / `screenRecord.elementsJson`). If target index < visible average $\to$ target is above current viewport $\to$ direction UP. If target index > visible average $\to$ direction DOWN.
      3. **Historical Target Motion Fallback:** If element order is indeterminate, checks `lastKnownTargetBounds`. If last seen in the top half (< 45% height) $\to$ UP.
    - **Target Direction Memory Across Branch Unwinds:** In `MarkerScout.shutdown(isPermanentStop)`, temporary branch divergences or gesture back unwinds preserve `lastKnownTargetStep`, `lastKnownTargetBounds`, and `targetScrolledAbove` (`isPermanentStop = false`), ensuring returning to the origin screen maintains correct scroll direction without resetting to blind default.
    - **Screen-Center Directional Green Arrow (`ElementMarkerOverlay.showScrollArrow`):** When the target element is off-screen and scrolling is required, a prominent visual arrow is rendered directly in the vertical/horizontal center of the screen matching the target highlight color (`#4CAF50`). If the target is above the viewport, the arrow points UP, and the HUD prompts `Scroll up to find '<target>'` (`"Passed it — scroll back up"`). Otherwise, the arrow points DOWN -> `Scroll down to find '<target>'`. The moment the target enters view, the center arrow instantly vanishes and the green bounding box appears around the target.
    - **Homepage Search Noise Suppression:** On root screens without a back button (e.g. Settings homepage), an inline search bar that scrolls into view must never cause `ScreenTitleResolver` to rename the screen to `"Search"`. It preserves the canonical app title (`"Settings"`). In `NavigationStateMachine`, `isEffectiveRootScreen` treats transient search box noise on root as the root screen, preventing false `WRONG PATH` alerts during scrolling.
20. **Stateful Navigation Execution Tracker, Active Node Lock & Dynamic Dijkstra Branch Unwinding:**
    - **Active Node Lock (`TrackerState.OnRoute`):** While on a route node without unexpected taps (`isLockedOnRoute = currentTrackerState is TrackerState.OnRoute && lastWrongTapText == null`), in-page scrolling, header collapse/expansion, and dynamic UI updates are strictly locked to the active node. `isUnrecognized` evaluates to `false`, preventing false off-track or wrong-turn alarms during scrolling on any route step.
    - **Transition-Only Advancement & Dynamic Diverged Stack:** State advancement occurs exclusively on transition events (element taps or activity launches). Tapping a non-target interactive label transitions into `TrackerState.Diverged` and pushes the tapped label onto `divergedStack`. Nested wrong turns increment the stack depth.
    - **Dynamic Multi-Step Back Recovery Guidance:** When diverged, the HUD provides precise multi-step return instructions: `Press Back to return to <OriginScreen>` (or `Press Back (N steps)...`). An asynchronous IO coroutine queries `SearchPathEngine.findPath` in SQLite; if Dijkstra discovers a path with `BACK` transitions, the step count is dynamically synchronized.
    - **Gesture & Button Branch Unwinding:** Tapping the Back button pops `divergedStack` and, when empty, restores `TrackerState.OnRoute(originNode)`. Android edge-swipe back gestures are detected in `onScreenChanged`, unwinding diverged states back to the origin node or previous parent screen.
    - **Real-Time 0-DB Cold Start Seeding (`seedRoute`):** On navigation start, `NavGraphDatabase.seedRoute` immediately populates SQLite with the screens, child elements, and bidirectional `CLICK` and `BACK` edges of the active route, ensuring that even with zero prior database entries, Dijkstra recovery and graph pathfinding function in real time.
21. **Zero-Flicker Back Navigation, Alphabet Index Rejection & Underlying Launcher Tracking:**
    - **System Navigation Back Tap Processing:** In `NavigationStateMachine.onElementTapped()`, system UI taps from `com.android.systemui` are ignored EXCEPT when `tapNorm == "back"` or contains `"back"`. Dropping the system Back button caused state unwinding to lag behind screen animations, generating transient intermediate wrong-path flickers.
    - **Zero-Flicker Unwind Transition:** When Back is tapped and `newStack.isEmpty()`, the state machine immediately restores `TrackerState.OnRoute(originNode)`, clears `lastWrongTapText = null`, sets `isCurrentScreenUnrecognized = false`, cancels any in-flight scout or motion jobs (`MarkerScout.shutdown()`), and updates the HUD directly to `ON_TRACK`. This completely eliminates intermediate flashes during page pop animations.
    - **Strict "null" / "nil" / Blank Tap Sanitization:** In `NavigationStateMachine`, `lastWrongTapText` is strictly sanitized. If it is null, blank, `"null"`, or `"nil"`, it is never formatted as `"You tapped 'null'"` or `"You tapped 'nil'"`. The HUD cleanly falls back to `"Press Back to return"`.
    - **Minimum Prefix Match Length Guard (`cleanEl.length >= 4`):** In `ElementFinderEngine.fuzzyElementMatch()`, `cleanTarget.startsWith(cleanEl)` requires `cleanEl.length >= 4` (or recognized tech acronyms like `"sim"`, `"vpn"`, `"sos"`, `"nfc"`). This prevents single-letter alphabet index items (such as the letter `"D"` on app drawers or contacts lists) from falsely matching long target elements like `"Display & brightness"`.
    - **Underlying App Window Poller & Launcher Recognition:** In `UiTreeAccessibilityService`, the background poller resolves foreground application packages using `AppWindowResolver.getUnderlyingAppRoot(this, packageName)` instead of raw `rootInActiveWindow`. When the launcher (`com.android.launcher` or package containing `launcher`) is detected, it immediately updates the HUD to `ON HOME: Open <TargetApp>` and shuts down visual markers.
22. **Strict Root Origin Restoration, Divergent Tap Marker Invalidation & Cross-Package Scroll Dispatch:**
    - **Strict Root Origin Restoration:** In `NavigationStateMachine.onScreenChanged()`, when `originTitle` is root (`"settings"` or `targetAppLabel`), `returnedToOrigin` requires `isEffectiveRootScreen` or exact match. It must NEVER use loose `fuzzyMatch(screenNorm, originTitle)` because substring matching causes sub-screens containing the word "settings" (e.g. "Notifications & Quick Settings", "Privacy settings", "Device settings") to falsely claim they returned to the Settings homepage, wiping out wrong-path tracking.
    - **Divergent Tap Marker Invalidation:** In `NavigationStateMachine.onElementTapped()`, when the user taps an interactive menu option that does not match `currStep` or any future step, guidance markers MUST be hidden immediately (`marker?.hideMarker()`), in-flight searches cancelled (`MarkerScout.shutdown()`), and `currentTrackerState` transitioned into `TrackerState.Diverged`. Checking whether `currStep` is momentarily lingering in the accessibility tree during the tap window causes the marker to stay visible and freeze across screen transitions, painting phantom boxes on unrelated elements (e.g. "Review settings").
    - **Cross-Package Scroll Transition Dispatch:** In `UiTreeAccessibilityService.handleScroll()`, if `actualPackage != expectedPackage` during the debounce window (such as launching an APEX module or sub-activity like `com.google.android.permissioncontroller`), the event must NOT be dropped as a `Debounce skip`. Instead, it extracts `screenTitle` via `ScreenTitleResolver` and immediately dispatches `NavigationStateMachine.onScreenChanged()`.
    - **Settings Satellite Suite Recognition:** In `NavigationStateMachine.isTargetApp()`, `com.google.android.permissioncontroller` and `com.android.permissioncontroller` are recognized as components of the Settings app ecosystem, allowing the state machine to evaluate sub-screen deviations and display precise recovery guidance.

---

## 6. Telemetry & Live Dashboard Rules

1. **Non-Blocking Telemetry:**
   - All telemetry emissions via `DebugTelemetryClient` must run on background coroutines (`Dispatchers.IO`) with short timeouts (connect: 800ms, read: 1500ms).
   - Telemetry failures must fail silently without ever interrupting core app execution.
2. **User-Controlled Dashboard:**
   - Do NOT force-open Chrome on the host laptop unless requested by the user.
   - The dashboard must reflect real device connection state (`🟢 PHONE CONNECTED` vs `🔴 NO PHONE OR APP CONNECTED`) based on live heartbeats.
3. **Local Flight Recorder (Black Box Event Logger):**
   - `FlightRecorder` writes a persistent, chronological record of every tap, node lock, screen change, scout detection, and recovery calculation into `context.filesDir/flight_recorder.log`.
   - Configured with a high capacity (15 MB) for deep debugging during active testing sessions, rotating to `flight_recorder.log.old` when exceeded.
   - Runs completely non-blocking on `Dispatchers.IO` via an unbounded Kotlin channel with debounced flushing.
   - All telemetry events from `DebugTelemetryClient` automatically mirror into `FlightRecorder`, ensuring zero loss of diagnostic information even when disconnected from the laptop.
   - Pull command: `adb shell "run-as com.example.floatingassistant cat files/flight_recorder.log"`.

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
4. **Chained Regression Verification ($N-1$ Invariant):**
   - Every edit must be explicitly evaluated against all existing features, architectures, and bug fixes ($1 \dots N-1$).
   - A step is incomplete if any previously working behavior is broken, reverted, or bypassed.
