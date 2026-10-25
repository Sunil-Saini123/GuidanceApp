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
   - **Pre-Proposal Invariant Audit (Never Propose an N-1 Violation):** BEFORE proposing, explaining, or presenting any fix idea to the user, the agent MUST explicitly cross-check the proposed approach against all $1, 2, \dots, N-1$ features and established architectural contracts. If the proposed fix would delete, replace, bypass, or degrade ANY existing feature (e.g. attempting to remove relative element order index comparison, weakening directional scrolling, or breaking Settings routing), the agent MUST IMMEDIATELY REJECT that approach. The agent must revise and formulate a solution that strictly preserves the $N-1$ capability intact before presenting the proposal to the user.
   - **Pre-Change Invariant Verification:** Before touching any code, cross-check against the entire chain of established behaviors (live tree supremacy, active node lock, zero-flicker back unwinding, binder recycling, directional scrolling, flight recording) to guarantee that the change harmonizes without introducing regressions.
   - **Cumulative Contract:** If a new requirement appears to conflict with an existing ($N-1$) behavior, you must explicitly highlight the conflict and ask the user for direction before making changes.
   - **Dynamic Live Count Enforcement ($N$):** The exact number $N$ of active `[PERMANENT FIX]` blocks across the codebase MUST be dynamically counted using a tool/script after EVERY single fix. The agent must NEVER guess, approximate, or repeat a stale/hardcoded count (e.g. "143+").
   - **Monotonic Chain Increment:** Every bug fix or feature implementation must increment the total count ($N 	o N+1$). After every fix, the agent must report the newly incremented live count and verify that all $1 \dots N-1$ blocks remain completely intact.

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
   - `MarkerView.onDraw()` enforces canvas clipping (`canvas.clipRect`) to guarantee the green rectangle and fill never draw over the phone's system navigation buttons, while permitting unclipped highlight display when specifically targeting system navigation bar controls (`isSystemNav == true`).

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
23. **Smart Multi-Tiered Contextual Fuzzy Threshold Architecture:**
    - To eliminate asymmetric matching between finder and state machine and handle compound UI labels (e.g. Android preference rows concatenating Title + Subtitle like `'Chats Chat history, backup'`), fuzzy matching is strictly segregated by operational context:
      - **Current Step Tap (`MatchContext.CURRENT_STEP_TAP`):** Medium 80% similarity threshold, min length ratio 0.65, with compound row prefix matching. If the tapped element begins with the expected step name followed by natural delimiters (`' '`, `'\n'`, `':'`, `'-'`, `','`, etc.), it is accepted as a valid step advancement.
      - **Future Step Skip (`MatchContext.FUTURE_STEP_SKIP`):** Strict 90% similarity threshold, min length ratio 0.80, and compound prefix matching is STRICTLY FORBIDDEN. Prevents user taps on compound labels (e.g. `'Chats Chat history, backup'`) from accidentally skipping ahead to subsequent steps like `'Chat backup'`, and prevents control words (`'Back'`) from matching compound phrases.
      - **Screen Title / Unwinding (`MatchContext.SCREEN_TITLE`):** High 85% threshold, min length ratio 0.70, no prefix matching, ensuring reliable screen recognition and stack unwinding without false root claims.
      - **Control Word Isolation:** System control words (`'back'`, `'home'`, `'menu'`, `'navigate up'`, `'close'`, `'cancel'`, `'null'`, `'nil'`) are strictly blacklisted from matching compound phrases across all contexts.
24. **Universal Ambiguous Control Guard, Strict Diverged State Lock & Full-Context Reroute:**
    - **Strict Diverged State Lock:** While diverged (`currentTrackerState is TrackerState.Diverged || lastWrongTapText != null || isCurrentScreenUnrecognized`), future-step skip-ahead matching is STRICTLY FORBIDDEN. Prevents user actions on wrong sub-screens (e.g. tapping 3-dots in Alice's chat) from falsely matching a future step on the intended route (e.g. 3-dots in Rohit's chat) and wiping out the `WRONG PATH` alert.
    - **Universal Ambiguous Control Guard:** Generic controls that exist across multiple screens (`"more options"`, `"more"`, `"search"`, `"menu"`, `"settings"`, `"back"`, `"add"`, `"done"`) can NEVER skip-advance unless the active screen title matches the expected parent screen for that step in `executionTree`.
    - **Full-Context AI Reroute (.db + Screen Title):** When Reroute is clicked, `FloatingOverlayService` and `PromptBuilder` inject the active screen title and local SQLite graph database structure (`connectedScreens`, `connectedTransitions`). This gives the AI full architectural knowledge of the app, ensuring it knows the user's exact screen position and disambiguates generic controls across different screen scopes.
    - **WhatsApp Chat Popup Menu Disambiguation:** In `ScreenTitleResolver`, popup menus inside conversation screens are authoritatively resolved as `"Chat Menu"`, keeping them completely segregated from the root `"Menu"` (`New group`, `Settings`).
25. **Dynamic Live Tree Sub-Screen Unwinding & Live Location Dashboard Flagging:**
    - **Mid-App Cold Start / Recents Launch Unwinding:** When navigation is initiated while the target app is already running on an off-route leaf sub-screen (e.g. `About phone` / `About device` in Settings), or when the user resumes the app from Recents / background during navigation:
      - The state machine inspects the live accessibility tree via `AppWindowResolver` and `ScreenTitleResolver`.
      - If the active screen is NOT the app root (`!isEffectiveRootScreen`) and does NOT match any step in the path (`nearestIdx == -1`), the state machine does NOT skip the root step.
      - Instead, it dynamically prepends `"BACK"` to `pathSteps` (`pathSteps.add(0, "BACK")`), displays `RETURN TO ROOT: Press Back to return to <App>`, and prompts `Currently on '<screen>' — return to root first`.
      - `ElementFinderEngine` discovers Back buttons across both the system navigation bar (3-button navbar via `com.android.systemui:id/back`) and on-screen toolbar Back arrows (gesture navigation fallback via `contentDescription = "Navigate up"` or `"Back"`), locking the visual green marker directly onto the Back button.
      - **Wrong-Path Visual Back Guidance:** Whenever the user enters an unrecognized screen, diverged state, or `WRONG PATH` alert, `NavigationStateMachine.highlightBackAction()` actively locates and highlights the system navbar or toolbar Back button with the green marker instead of leaving the screen unguided.
      - Once the user taps Back (or system Back) and lands on root (or a valid route screen), the `"BACK"` step is authoritatively fulfilled, advancing smoothly to the forward target (`Display & brightness`).
    - **Reconstructed Dijkstra Shortest Path BACK Label Preservation:** In `SearchPathEngine.findPathInGraph()`, when Dijkstra traverses an inverse return edge (`actionLabel == "BACK"`), the action label `"BACK"` is preserved as the step string instead of being replaced by the parent screen's title. This allows multi-hop unwinding paths to instruct Back actions rather than looking for non-existent root titles on sub-screens.
    - **Live Phone Location Dashboard Flagging:** `NavigationStateMachine` streams real-time `SCREEN_CHANGED` and `STEP_ADVANCED` telemetry events containing `screenTitle`, `packageName`, and `currentStep`. `TreeExplorerTab.tsx` identifies the active screen and displays a prominent, pulsing `📍 PHONE HERE NOW` badge on that screen node across both Nav Graph and Live Tree modes, surfaces a top-level Live Phone Location status banner, and marks the active target element with `🎯 TARGET STEP`.

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

## 7. WhatsApp & Multi-App Navigation Rules

1. **Canonical Package Normalization (`GraphStateMachine.getPackageNameForApp`):**
   - All components (`FloatingOverlayService`, `NavigationStateMachine`, `NavGraphDatabase`, `SearchPathEngine`) MUST resolve shorthand app names (`"whatsapp"`, `"settings"`) to full canonical Android package names (`"com.whatsapp"`, `"com.android.settings"`).
   - Prevents split-brain package duplicates in SQLite and dashboard app dropdowns.
   - `NavGraphDatabase.mergeDuplicatePackagesAndCleanScreens()` auto-heals pre-existing duplicate entries on startup.
2. **WhatsApp Profile Name Suppression & Settings Resolution (`ScreenTitleResolver`):**
   - On WhatsApp Settings, the user's personal profile card (e.g. `"Rohit Thatikonda"`, `"You"`) appears at the top without a static `"Settings"` toolbar title.
   - Screen resolution must detect characteristic settings rows (`Account`, `Privacy`, `Chats`, `Notifications`, `Storage and data`) and resolve the screen title strictly as **`"Settings"`**, never the user's personal profile name.
3. **WhatsApp Sub-Page Disambiguation (`Chats` vs `Chats Settings`):**
   - In WhatsApp, the root conversations tab is titled `"Chats"` (has NO back button).
   - The settings sub-page containing Chat backup and wallpaper is ALSO headered `"Chats"` (has a back button).
   - `ScreenTitleResolver` resolves sub-screens with a back button as **`"Chats Settings"`**, preventing it from colliding with or overwriting the root conversation list.
4. **Target App Root Disambiguation (`isRootScreen`):**
   - In `NavigationStateMachine`, `isRootScreen` must evaluate root status relative to `targetPackage`:
     - For `com.android.settings`: Root is `"Settings"`.
     - For `com.whatsapp`: Root is `"Chats"` or `"WhatsApp"`. WhatsApp's `"Settings"` is a sub-page, never a root screen.
5. **Direct Intent Fast-Path for Chat Backup:**
   - User queries containing `"chat"` and (`"backup"` or `"back up"`) bypass LLM ambiguity and directly resolve to `targetApp = "com.whatsapp"`, `destinationScreen = "chats_settings"`, `exactTask = "chat_backup"`.
6. **Compulsory Settings & Multi-App Non-Regression Invariant:**
   - When developing or refining features for WhatsApp or other third-party apps, all established Settings navigation contracts (active node lock, multi-source directional scroll intelligence, zero-flicker back unwinding, satellite cluster routing, flight recording) MUST remain 100% active, preserved, and regression-free.
7. **WhatsApp 3-Dots Popup Window Disambiguation (`Menu`):**
   - The 3-dots overflow popup window has no Toolbar or Back button.
   - It contains `menuitem_settings`, `menuitem_new_group`, `menuitem_payments`, etc.
   - It is authoritatively resolved as **`"Menu"`**, preventing circular transitions (`WhatsApp opens WhatsApp`) and duplicate app entries.
8. **WhatsApp Chat List Noise & Message Snippet Suppression:**
   - Ephemeral chat row timestamps (`Yesterday`, `Today`, `\d{1,2}:\d{2}\s*(am|pm)`), message previews (`You:`, `Video`, `Photo`, `Audio`), and internal view IDs (`single_msg_tv`, `conversations_row_date`, `pin_indicator`, `conversations_archive_header`, `contact_row_container`) are filtered out of graph extraction.
9. **WhatsApp Strict Hierarchy & Swallowed Screen Healing:**
   - WhatsApp navigation to Chat Backup has 3 distinct levels:
     - Level 1: `com.whatsapp::Settings` (Main Settings)
     - Level 2: `com.whatsapp::Chats Settings` (Chats sub-page)
     - Level 3: `com.whatsapp::Chat backup` (Chat backup leaf screen)
   - Elements from these screens must never be swallowed or lumped together into a single screen ID.
   - `NavGraphDatabase.mergeDuplicatePackagesAndCleanScreens()` automatically separates pre-existing swallowed elements and re-establishes clean bidirectional transitions.
10. **Foreground-Aware Dijkstra Start Screen Resolution:**
    - If the user initiates navigation from outside the target app (e.g. from GuidanceApp search panel or Home Launcher), `SearchPathEngine` must NEVER use a stale background sub-page from `GraphStateMachine.currentScreenId()`.
    - `activeScreenId` is set to `null` so Dijkstra authoritatively routes from the canonical root home screen (`Chats` or `Settings`), preventing 1-step phantom shortcuts and false scroll alerts.
11. **Generic Control Button Exclusion in Target Scoring:**
    - Common navigation control buttons (`"Back"`, `"Cancel"`, `"Close"`, `"OK"`, `"Done"`) are barred from matching task action strings in `calculateMatchScore`, preventing `"Back"` from falsely matching `"chat_backup"` due to partial substring overlap.
12. **Pure App-Specific Graph Reset (`resetWhatsAppGraphOnce`):**
    - Wipes corrupt legacy screens exclusively for `com.whatsapp` (`DELETE FROM screens WHERE package_name = 'com.whatsapp'`), seeding verified canonical screens and bidirectional edges while leaving 100% of Settings records, satellite packages, and features completely intact ($N-1$ Invariant).
13. **Root Screen Absolute Priority, Sub-Page Exclusion & Code-Editor Tree Nesting:**
    - When the user is on the canonical Root Screen of an app (e.g. "Chats" on WhatsApp or "Settings" on Settings), `isCurrentScreenRoot` takes absolute precedence over `nearestIdx >= 0` in `startNavigation`.
    - `findNearestStep()` is strictly forbidden from matching internal sub-page steps (idx > 0) when the active screen is a root screen, preventing false fast-forward over initial actions (e.g. 3-dots "More options" and "Settings").
    - In `ElementFinderEngine`, prefix matching where target extends element (`cleanTarget.startsWith(cleanEl)`) strictly requires at least 70% length coverage ratio, preventing short single-word tabs (like "Chats") from matching long compound sub-page targets (like "Chats Chat history, backup").
    - On the web dashboard (`TreeExplorerTab.tsx`), active phone location uses high-contrast glowing beacon styling (`bg-gradient-to-r from-emerald-950/90`, `border-2 border-emerald-400`, `shadow-[0_0_24px]`) and code-editor style continuous nested guide lines (`├──`, `└──`, vertical guide borders) to mirror IDE nested block syntax.
14. **Canonical Root Screen Verification in `onScreenChanged` & Dashboard Alias Flagging:**
    - In `NavigationStateMachine.onScreenChanged`, `isRootScreen` MUST call `isCurrentScreenRoot(activeScreenName, targetPackage, targetAppLabel)` so that app home screens (such as "Chats" on WhatsApp) are recognized as true root screens rather than foreign wrong-path screens.
    - When `inTargetApp && isEffectiveRootScreen` is true, wrong-path tracking is cleared and the system is strictly barred from instructing the user to "Press Back to return to <App>".
    - On the web dashboard (`TreeExplorerTab.tsx`), `isCurrentLiveScreen` matches canonical root aliases (e.g. live toolbar "WhatsApp" vs tree root "Chats"), rendering a high-contrast glowing card with `📍 CURRENT ON THIS PAGE HERE`.
15. **Stale Cross-App Title Sanitization & WhatsApp Root Canonicalization:**
    - In `NavigationStateMachine.startNavigation()`, `activeScreenName` is authoritatively initialized to the target app's canonical root title (`"Chats"` for WhatsApp, `"Settings"` for Settings) whenever `liveTitle` is null or from an unmatching package. This strictly prevents stale screen titles from previously opened apps (such as "Telegram") from leaking into WhatsApp navigation and triggering false "BACK" step prepends.
    - In `ScreenTitleResolver.canonicalizeTitle()`, "WhatsApp" on root is canonicalized to "Chats" for `com.whatsapp`, guaranteeing 100% synchronization between live accessibility titles and the SQLite navigation graph.
16. **Multi-Path Collection, Ranking & Web Dashboard Telemetry:**
    - In `SearchPathEngine.findPathInGraph()`, the engine does NOT halt on the very first path found. Instead, it systematically evaluates all start screens and candidate destinations, collecting all reachable routes into `allDiscoveredPaths`.
    - Routes are sorted by hop count and cost (shortest first), with the optimal route marked as `isShortest = true`.
    - `FloatingOverlayService` streams the entire multi-route array via `DebugTelemetryClient` (`INTENT_PARSED` payload with `allPaths`), allowing the web dashboard (`TreeExplorerTab.tsx`) to render a dedicated "All Local Query Paths" section with rank badges (`⭐ BEST ROUTE (ACTIVE)` vs `ALTERNATIVE ROUTE`), step breadcrumbs, hop counts, and Dijkstra costs.
17. **Name + Position Verification Guard & Notification Badge Stripping:**
    - In `ScreenTitleResolver.stripBadgeNoise()`, notification badges and unread message counters (e.g. `", \d+ new notification[s]?"`, `", \d+ unread"`, `"(\d+)"`) are stripped from tab labels and window titles before canonicalization, preventing `"Chats, 1 new notification"` from failing root screen recognition.
    - In `NavigationStateMachine.onScreenChanged()`, returning from `TrackerState.OffApp` back into the target app on a known root screen immediately restores `currentTrackerState = TrackerState.OnRoute`, keeping Active Node Lock engaged.
    - In `ElementFinderEngine.findElementBoundsInLiveTree()`, elements are verified by BOTH label and screen position:
      - WhatsApp "More options" (Three Dots ⋮) must reside in the top action bar area (`bounds.top <= 400 && bounds.right >= screenWidth * 0.60f`).
      - WhatsApp "Chats" inside Settings must be a list preference row (`bounds.top > 200 && bounds.bottom < screenHeight - 200`), strictly rejecting bottom navigation bar tabs (`bounds.top > screenHeight - 300`).
      - On WhatsApp root screen ("Chats"), the bottom navigation tab "Chats" is strictly prevented from matching as a navigation action.
18. **DevTools Graph & JSON Clear Synchronization with Web Dashboard:**
    - In `FloatingOverlayService.kt`, `btnClearJson` deletes `nav_graph.json` along with `clean_page.json` and `temp_tree.json`, and emits a `CLEAR_GRAPH` telemetry event.
    - `btnClearGraph` deletes both `nav_graph.db` and exported `nav_graph.json` from `context.filesDir`, emitting a `CLEAR_GRAPH` event.
    - In `dashboard/server.js`, receiving `CLEAR_GRAPH` resets in-memory `cachedNavGraph` to empty and broadcasts `CLEAR_HISTORY` and `NAV_GRAPH_DATA` to all connected browser clients, ensuring the website tree immediately clears in real-time.
19. **WhatsApp Conversation Screen & Compound Chat Row Transition Preservation:**
    - In `ScreenTitleResolver`, never blindly coerce WhatsApp contact names or conversation titles containing "rohit" or "you" to "Settings". Genuine WhatsApp Settings is detected hierarchically via `isWhatsAppSettingsScreen(root)`.
    - Conversation screens retain their true contact name (e.g. "Rohit Thatikonda (You)").
    - In `GraphStateMachine.sanitizeEdgeLabel`, compound chat row metadata (e.g. "You Rohit Thatikonda (You) Yesterday Video Pinned chat") containing the destination screen title resolves directly to the clean contact name so the transition edge (`Chats -["Rohit Thatikonda (You)"]-> Rohit Thatikonda (You)`) matches the screen element 100%.
    - In `NavGraphDatabase.sanitizeCorruptedRootTransitions()`, chat conversation screens mistakenly saved as `com.whatsapp::Settings` are automatically purged on startup.
    - On the web dashboard (`TreeExplorerTab.tsx`), matching transitions renders an expandable link (`🔗 opens <ContactName>`) with code-style nested block indentation, and live phone location highlights the active conversation screen with `📍 CURRENT ON THIS PAGE HERE`.
20. **Text-First Node Keying, Sender Preview Suppression & Untruncated Group Title Recovery:**
    - In `RawDumpWriter.computeKey(node)`: Text and contentDescription are prioritized over `resource_id` (`txt:$text|$cls`, `cd:$cd|$cls`). WhatsApp and other list layouts use the identical resource ID (`conversations_row_contact_name`) for all rows; keying on resource ID caused every contact row after the first one to be dropped as a duplicate! Text-first keying guarantees every chat contact row is preserved.
    - In `CleanPageProcessor.extractCleanElements`: Message sender name previews (`msg_from_tv`, e.g. `"Vicky Pukale (VIT):"`), date labels (`conversations_row_date`), and message preview lines (`single_msg_tv`) are strictly dropped from extraction, ensuring group chats are never falsely named after the sender of the last message.
    - In `ScreenTitleResolver` & `GraphStateMachine`: Action buttons like `"Clear all"` or `"Select all"` are treated as non-navigable noise words so they never become screen titles. Long conversation titles truncated with ellipses (`…`) in the action bar are automatically recovered using the clean clicked contact row name.
    - In `TreeExplorerTab.tsx`: Typographic, emoji, and ellipsis differences are normalized before label matching so group chat names match their transition edges seamlessly.
21. **App-Aware Tree Root Determination & Universal Screen Visibility Guard:**
    - In `TreeExplorerTab.tsx`, `rootScreen` prioritizes app-specific canonical entry screens: for `com.whatsapp`, the root is ALWAYS `Chats` (or `WhatsApp`), NEVER `Settings`. For `com.android.settings`, the root is `Settings`.
    - Uses topological zero in-degree (`forwardTargets`) so that the true entry point of any graph is identified as the root trunk.
    - **Universal Screen Visibility Guard:** Any screens in `activeApp.screens` that are not reached in the root tree are rendered in a dedicated *"Additional / Standalone Screens"* section so no screen or conversation branch can ever vanish from the user's view.
    - **Direct Navigation Shortcuts:** Unmapped forward `CLICK` transitions on any screen are rendered as expandable shortcuts, ensuring sub-pages reached via menu options or intent links can always be expanded.
    - In `UiTreeAccessibilityService.kt` and `RawDumpWriter.kt`, `passed.semanticRootName` is passed through scroll and pipeline triggers so popup menus retain their clean title (`"Menu"`) instead of falling back to raw class names (`android.widget.FrameLayout`) or guessing titles from popup menu items (`"Menuitem New Group"`).
    - In `dashboard/server.js`, `cachedNavGraph` is preserved across temporary USB disconnections or screen locks instead of being cleared to 0 apps.

---

## 8. Anti-Hallucination & Code Edit Protocol

1. **Inspect Before Changing:**
   - Always read the actual target file and surrounding context using tools before making any modification.
   - Do not guess about function signatures, class members, imports, or variable names.
2. **Exact Drop-in Replacements:**
   - When using replacement tools, targets must match existing code whitespace and characters exactly.
   - Verify changes immediately after editing.
3. **Continuous Updating:**
   - As new bugs, OEM edge cases, or architectural patterns are discovered, this `GEMINI.md` document must be updated to keep all rules active and persistent.
4. **Pre-Proposal $N-1$ Gate & Chained Regression Verification:**
   - **Pre-Proposal Audit:** Never present a fix idea to the user that replaces, removes, or degrades an existing $1 \dots N-1$ capability. The agent must verify $N-1$ harmony during the ideation phase, discarding regressive approaches before proposing.
   - **Pre-Execution Check:** Every edit must be explicitly evaluated against all existing features, architectures, and bug fixes ($1 \dots N-1$).
   - A step is incomplete if any previously working behavior is broken, reverted, or bypassed.
5. **Mandatory Post-Fix Check & Live Count Reporting:**
   - After EVERY single code edit, the agent MUST:
     1. Execute a live scan of all `[PERMANENT FIX]` occurrences across `app/src/main/java`.
     2. Report the exact, updated total count $N$ and its distribution.
     3. Verify zero previous `[PERMANENT FIX]` blocks were deleted, renamed, or simplified.
     4. Verify compilation with `./gradlew compileDebugKotlin`.
