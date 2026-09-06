# GuidanceApp — Architecture, Progress & Audit

**Package:** `com.example.floatingassistant`  
**Build:** minSdk 24 · targetSdk 37 · Kotlin + Java (mixed) · Compose UI · no Room/kapt

---

## Checklist Key
| Symbol | Meaning |
|--------|---------|
| `[x]` | Implemented and actively wired into the live pipeline |
| `[-]` | Built and compiles, but has **zero active call sites** from the pipeline |
| `[ ]` | Architectural requirement — **no code exists yet** |

---

## Track 1 — Screen Capture & Graph Hierarchy

> Goal: Continuously observe every app the user touches and build a structured,
> hierarchical navigation graph (Macro → Meso → Micro).

### Filtering & Tree Generation
- [x] **Main Filter** — drops system UI, status bar, own app package (`MainFilter.kt`)
- [x] **Intermediate Filter** — OEM prefix stripping, virtual-view supplementalSearch (`CleanPageProcessor.kt`)
- [x] **Secondary Filter** — Rule-based extraction: prune invisible nodes, drop dynamic data, flatten containers (`CleanPageProcessor.kt`)
- [x] **Static vs. Dynamic Separation** — `isDynamic()` rejects phone numbers, timestamps, message previews; static anchors kept (`CleanPageProcessor.kt`)
- [x] **`temp_tree.json`** — Phase 1 raw DFS flat dump; cleared on navigation, accumulates on scroll; deduped by `computeKey` (`RawDumpWriter.kt`)
- [x] **`clean_page.json`** — Phase 2 clean extraction; written after every navigation/scroll event (`CleanPageProcessor.kt`)
- [x] **OEM ROM Detection** — MIUI / OriginOS / ColorOS / One UI via system properties reflection (`OemRomDetector.kt`)
- [x] **Device Info capture** → `device_info.json` (`DeviceInfoWriter.kt`)

### Hierarchical Graph Construction
- [x] **Macro level** — package-to-display-name mapping for all major apps + Vivo/Samsung/Xiaomi OEM packages (`GraphStateMachine.KNOWN_APP_NAMES`)
- [x] **Meso level** — per-package screen nodes with deterministic `screenId = "$pkg::$screenTitle"` (`GraphStateMachine.kt`)
- [x] **Meso transitions** — FORWARD (push + CLICK edge) / BACK (pop + BACK edge) detection; `traversal_count` weighted edges (`GraphStateMachine.kt`, `NavGraphDatabase.kt`)
- [x] **Screen title resolution** — strips ` · ` composites, `cleanRootClass()` fallback, top-20% zone scan (`GraphStateMachine.resolveScreenTitle`)
- [x] **Element accumulation** — Meso nodes store all clean elements; revisit merges by name (no overwrite) (`NavGraphDatabase.mergeElements`)
- [x] **UNIQUE transition constraint** — DB v2 UNIQUE index; INSERT OR IGNORE + UPDATE transaction (no duplicate edges) (`NavGraphDatabase.kt`)
- [x] **`nav_graph.db`** — SQLite persistent graph; schema v2; screens + transitions tables
- [x] **`nav_graph.json`** — Human-readable hierarchical snapshot across all apps; rewritten after every event
- [ ] **Micro level** — Element nodes with stable unique identifiers (resource_id hash, bounds fingerprint) stored as interaction targets; currently elements are stored as name-strings only, not as queryable graph nodes

### Path Search on Local Graph (Tier 1)
- [x] **BFS / Dijkstra on `nav_graph.db`** — Query: given current `screenId` + target screen title/element, find shortest weighted path through recorded transitions (`SearchPathEngine.kt`)
- [x] **Path serializer** — Convert DB transition rows into a human-readable step sequence (`WhatsApp → 3 dots → Settings → Profile`)
- [x] **"No path" detection** — Return `null` cleanly when target is unreachable in local graph to trigger Tier 2 fallback

---

## Track 2 — Floating UI & Intent Parsing

> Goal: Non-intrusive overlay captures user intent in natural language and
> classifies it into a structured `{targetApp, destinationScreen, exactTask}` payload.

### Floating Bubble Service
- [x] **Floating bubble** — Draggable `TYPE_APPLICATION_OVERLAY` window; idle/typing flag modes; drag locks at 12 px slop (`FloatingOverlayService.kt`)
- [x] **Control panel** — EditText + Submit + Stop buttons; keyboard management; outside-tap dismiss
- [x] **Stop multi-tap flow** — Tap 1 stops navigation; Tap 2 shows exit AlertDialog
- [x] **Client-side pre-validation** — `CommandValidator.validate()` runs synchronously before any network call; rejects blank/short/non-command input

### Intent Classification
- [x] **Gemini 2.0 Flash parser** — HTTP call to Gemini API; 5-retry exponential back-off; parses `Target App / Intent` two-line response (`GeminiCommandParser.kt`)
- [x] **Gemini wired to bubble** — `handleSubmittedQuery()` calls `GeminiCommandParser.parse()` on `Dispatchers.IO` ✅ active call site
- [-] **Offline intent classifier** — Full BM25 + semantic retrieval, multi-factor ranking, Groq fallback, 3-index adaptive learning (`intent/IntentClassificationEngine.java` + 9 sub-packages)
  - Only instantiated inside `intent/evaluation/EvaluationRunner.java` (test harness)
  - **Zero call sites** from `FloatingOverlayService` or any other active file
  - Intended to replace / complement Gemini for offline-first classification; not yet wired
- [ ] **Structured intent payload** — Gemini currently returns two free-text lines (`targetApp`, `intent`). A canonical structured payload `{target_app, destination_screen, exact_task}` is not yet produced or consumed

---

## Track 3 — Path Resolution Strategy (3-Tier Fallback)

> Tier 1 (local graph) → Tier 2 (Firestore crowd-source) → Tier 3 (Groq LLM).
> Each tier is tried in order; the first hit is dispatched to the State Machine.

### Current Actual Wiring in `FloatingOverlayService.handleSubmittedQuery()`
```
Step A  GeminiCommandParser.parse()           [x] WIRED
Step B  Show parsed result in status text     [x] WIRED
Step C  PathDatabase.lookup()                 ❌ WRONG — calls OLD local keyword DB
Step D  NavigationStateMachine.start(path)    ❌ STUB — old Phase 7 placeholder
```

### Tier 1 — Local Graph (Dijkstra)
- [x] Graph data exists in `nav_graph.db` with weighted transitions
- [ ] **Dijkstra / BFS query function** — Not implemented anywhere; `nav_graph.db` is written but never read for pathfinding
- [ ] **Tier 1 call site** in `handleSubmittedQuery` — currently skipped entirely

### Tier 2 — Firestore Cloud Database
- [x] **`CloudPathDatabase`** — Full Firestore implementation; anonymous Firebase Auth; `device_paths` collection; canonical intent key lookup (`CloudPathDatabase.kt`)
- [x] **`ensureSignedIn()`** — Called in `FloatingOverlayService.onCreate()` ✅
- [-] **`CloudPathDatabase.lookup()`** — Built and correct, but **never called**; `handleSubmittedQuery` calls `PathDatabase.lookup()` (old local) instead ❌
- [-] **`CloudPathDatabase.addEntry()`** — Built and correct, but **never called** (new paths from Groq are never stored back to Firestore) ❌

### Tier 3 — Groq LLM via Vercel Proxy
- [x] **`GroqProxyClient`** — HTTP client; `https://navigation-app-server.vercel.app/api/navigate`; separate connect/read timeouts (`pathgenerator/GroqProxyClient.java`)
- [x] **`PathGenerator`** — Orchestrates: device info → prompt → Groq call → parse → result (`pathgenerator/PathGenerator.java`)
- [x] **`PromptBuilder`** — Builds structured prompt with device info + nav_graph context + intent (`pathgenerator/PromptBuilder.java`)
- [x] **`GroqResponseParser`** — Parses Groq JSON → `NavigationPath` (`pathgenerator/GroqResponseParser.java`)
- [-] **`PathGenerator.generatePath()`** — **Zero call sites** from any active pipeline file; never triggered ❌
- [-] **`IntentProvider`** — Predefined intent fallback catalog; only used in pathgenerator internal tests ❌

---

## 4-Phase Path Resolution Wiring Plan

> All logging uses the `[PathFinder]` tag for end-to-end Logcat tracing.

| Phase | Goal | Status |
|-------|------|--------|
| Phase 1 | Structured intent from Gemini (`target_app` + `destination_screen` + `exact_task`) | ✅ DONE |
| Phase 2 | Tier 1 — BFS/Dijkstra on local `nav_graph.db` | ✅ DONE |
| Phase 3 | Tier 2 — Firestore `CloudPathDatabase.lookup()` wired as fallback | ✅ DONE |
| Phase 4 | Tier 3 — Groq `PathGenerator.generatePath()` + `CloudPathDatabase.addEntry()` | ✅ DONE |

### Phase 1 — Structured Intent Classification
- [x] `GeminiCommandParser.ParsedCommand` extended to 3 fields: `targetApp`, `destinationScreen`, `exactTask`
- [x] System prompt enforces 3-line response format
- [x] `parseResponse()` extracts all 3 fields with resilient fallback
- [x] `[PathFinder]` log: `Parsed Intent -> App: X, Screen: Y, Task: Z`
- [x] `FloatingOverlayService.handleSubmittedQuery()` uses new structured payload

### Phase 2 — Tier 1: Local Graph Search
- [x] `SearchPathEngine.kt` — BFS / Dijkstra on `nav_graph.db` transitions
- [x] Input: current `screenId` from `GraphStateMachine` + `destinationScreen` / `exactTask` from Phase 1
- [x] Output: ordered list of `action_label` strings or `null`
- [x] `[PathFinder]` log: `Tier 1 Local DB: Path Found / Miss`
- [x] Wired in `FloatingOverlayService.handleSubmittedQuery()` with overlay status and Logcat reporting

### Phase 3 — Tier 2: Firestore Cloud Lookup
- [x] `CloudPathDatabase.lookup(targetApp, exactTask)` — new 3-level path: `entries.{appKey}.{taskKey}`
- [x] `CloudPathDatabase.addEntry(targetApp, exactTask, path)` — nested write; `update()` + `set(merge)` fallback
- [x] Firebase schema updated: `entries → {appKey} → {taskKey} → path string`
- [x] `[PathFinder]` log: `Tier 2 Firestore: Match Found / Miss`
- [x] Wired in `handleSubmittedQuery` after Tier 1 stub; returns on hit with `return@launch`

### Phase 4 — Tier 3: Groq LLM Fallback
- [x] `PromptBuilder.buildGeminiDrivenPrompt(targetApp, exactTask, cleanPageContent, deviceInfo)` — new focused prompt
- [x] `GroqProxyClient.sendDirectRequest(systemPrompt, userPrompt)` — direct string-based Groq call
- [x] `clean_page.json` read from `getExternalFilesDir(null)` and passed as current screen context
- [x] `DeviceInfoGatherer.gather()` called for device/ROM context
- [x] `GroqResponseParser.parse()` → `NavigationPath.toPathString()` for display
- [x] `CloudPathDatabase.addEntry()` called after success — auto-stores path to Firestore for Tier 2 next time
- [x] `[PathFinder]` log: `Tier 3 Groq: Generated path / Failed`
- [x] Wired in `handleSubmittedQuery` after Tier 2 miss


---

## Track 4 — Navigation State Machine & Visual Guidance Engine

> Goal: Receive a resolved path (from any tier) and walk the user through it step-by-step,
> with a persistent on-screen HUD, real-time step advancement via accessibility events,
> and dynamic self-healing when the user goes off-track.

### Track 4 Checklist Key
| Symbol | Meaning |
|--------|---------|
| `[x]` | Implemented and wired into the live pipeline |
| `[/]` | In progress / partially wired |
| `[ ]` | Architectural requirement — no code exists yet |

---

### Stage 1 — Top Floating Pill HUD (UI overlay, positioning, styling, update API) ✅ DONE (+ Bug-fixed)

- [x] **`NavigationHudOverlay.kt`** — Dedicated overlay controller; `TYPE_APPLICATION_OVERLAY`; `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN`
- [x] **Positioning** — `Gravity.TOP | Gravity.CENTER_HORIZONTAL`; 36dp top margin; `WRAP_CONTENT` × `WRAP_CONTENT` auto-sizes to text
- [x] **Visual styling** — Translucent dark-charcoal pill; 1dp `#33FFFFFF` border; 16dp corner radius; 16dp H-pad / 10dp V-pad
- [x] **Typography** — `stepLabel` (11sp bold uppercase `#A0A0A0`); `actionLabel` (14sp bold `#FFFFFF`)
- [x] **`updateHud(stepHeader, actionInstruction, subTag?)`** — Sets text + calls `show()` in one atomic `postToMain` block
- [x] **`show()` / `hide()` / `destroy()`** — Thread-safe; lifecycle-safe
- [x] **`NavigationStateMachine.attachHud()`** — Wired in `FloatingOverlayService.onCreate()` so Stage 2 can drive the HUD
- [x] **Bug Fix 1 — Blank text:** Views built in `init{}` (not lazily) so `stepLabel`/`actionLabel` refs are always non-null when `updateHud()` assigns text
- [x] **Bug Fix 2 — Panel not closing:** `hidePanelAndRestoreIdle()` called on Tier 1/2/3 path success; panel stays open only on failure (error display)
- [x] **Bug Fix 3 — State loss:** Path handed to `NavigationStateMachine.startNavigation()` (Service-singleton); not held in coroutine-local variables
- [x] **Bug Fix 4 — Inconsistent show/hide:** `hudContainer.isAttachedToWindow` (real system state) used instead of `@Volatile isAttached` flag to prevent stale-flag races after `WindowManager` exceptions

---

### Stage 2 — Core State Machine Loop (Prev / Curr / Next tracking & `is_correct_page` check) ✅ DONE

- [x] **`NavigationStateMachine` rewritten** — Full singleton; replaces Phase 7 stub
- [x] **State variables** — `targetPackage`, `pathSteps: List<String>`, `currentIndex`, `prevStep`, `currStep`, `nextStep`, `lastCorrectStep`, `isNavigating`
- [x] **`startNavigation(resolvedPath, pkgTarget)`** — Initialises all state, logs full step sequence, calls `hud.updateHud("STEP 1 OF N", currStep)` + `hud.show()`
- [x] **`stopNavigation()` / `stop()`** — Resets all pointers, calls `hud.hide()`; legacy alias kept for existing call sites
- [x] **`attachHud(overlay)`** — Called by `FloatingOverlayService.onCreate()` to wire HUD into the singleton
- [x] **`onScreenChanged(activePackage, currentScreenName)`** — 5-branch alignment decision tree:
  - Branch 1 — Package mismatch → `"WRONG APP"` HUD
  - Branch 2 — Fuzzy match on `currStep` → `"STEP N OF M — Look for '$nextStep'"` HUD
  - Branch 3 — Forward leap (`screenName == nextStep`) → advance index → update HUD
  - Branch 4 — Last step reached → `"COMPLETE ✓"` HUD + auto-hide after 3 s
  - Branch 5 — Off-track in correct app → `"OFF TRACK — Press Back to '$lastCorrectStep'"` HUD
- [x] **`fuzzyMatch(actual, expected)`** — 3-level: exact → contains → word-level (handles OEM screen label abbreviations)
- [x] **Event binding in `UiTreeAccessibilityService`** — Every `TYPE_WINDOW_STATE_CHANGED` (NAVIGATION) event calls `NavigationStateMachine.onScreenChanged(pkg, rootClass)`; guarded by `isNavigating` for zero-cost fast-exit when idle
- [x] **All state mutations on main thread** — `postToMain` wrapper ensures thread safety

---

### Stage 3 — Element Finder Pipeline (`clean_page` → graph scope → `temp_tree` → scroll directive) 🔜

- [ ] **Element lookup in `clean_page.json`** — For the current step's action label, scan `clean_page.json` to find a matching element node (by text, contentDescription, or resource_id fragment)
- [ ] **Graph-scope narrowing** — Cross-reference `nav_graph.db` Micro-level element records (when available) to prefer stable resource-id fingerprints over fuzzy name matching
- [ ] **`temp_tree.json` fallback** — If `clean_page.json` has no match, scan `temp_tree.json` raw dump (broader, less filtered)
- [ ] **Scroll directive** — If the target element is not in the current viewport, emit a scroll instruction: `navigationHud.updateHud("STEP N OF M", "Scroll down to find '${label}'")`
- [ ] **Highlight bounds for Stage 4** — Record the found node's `bounds` for the future on-screen highlight overlay

---

### Stage 4 — Groq Dynamic Healing & Firestore Crowd-Sourcing 🔜

- [ ] **Off-track auto-heal** — When `OffTrack` state is detected for > 2 navigations, invoke Groq re-route with fresh `clean_page.json` context; update path and resume from corrected step
- [ ] **`device_paths` crowd-sourcing** — On guide `Complete`, if path was AI-generated (Tier 3), prompt user to confirm ("Did this work?") and persist to `CloudPathDatabase.addEntry()` for Tier 2 next time (already partially implemented; tie into state machine completion signal)
- [ ] **Healing HUD status** — While Groq re-routes, show `navigationHud.updateHud("AI HEALING", "Finding alternate path…")`
- [ ] **Micro-level element highlight overlay** — Draw a translucent coloured rectangle over the target `AccessibilityNodeInfo` bounds using a second `TYPE_APPLICATION_OVERLAY` window


---

## Output Files (on device)

```
/sdcard/Android/data/com.example.floatingassistant/files/
  temp_tree.json      Phase 1: raw flat DFS node list (current page only)
  clean_page.json     Phase 2: clean static elements (current page)
  nav_graph.json      Phase 3: human-readable hierarchical graph (all apps)
  nav_graph.db        Phase 3: SQLite graph (persistent, accumulates across sessions)
  device_info.json    Manufacturer / model / Android version / OEM ROM
```

```bash
adb pull /sdcard/Android/data/com.example.floatingassistant/files/
```

---

## Superseded Files (on disk, not wired)

| File | Was | Replaced By |
|------|-----|-------------|
| `CleanPageExtractor.kt` | Old Phase 5 | `CleanPageProcessor.kt` |
| `CleanPageWriter.kt` | Old Phase 5 | `CleanPageProcessor.kt` |
| `ContextRootTracker.kt` | Old Phase 4 | `GraphStateMachine.kt` |
| `JsonTreeWriter.kt` | Old Phase 4 | `RawDumpWriter.kt` |
| `SecondaryFilter.kt` | Old Phase 4 | `CleanPageProcessor.kt` |
| `StaticDynamicFilter.kt` | Old Phase 11 | `CleanPageProcessor.kt` |
| `UIKeywords.kt` | Old Phase 11 | `CleanPageProcessor.kt` |
| `UiNode.kt` | Old Phase 3 | JSON nodes in `RawDumpWriter` |
| `UiTreeParser.kt` | Old Phase 3 | `RawDumpWriter.kt` |
| `FnvHash.kt` | Old Phase 3 | `computeKey()` in `RawDumpWriter` |
| `RawTreeWriter.kt` | Phase D debug | `RawDumpWriter.kt` |
| `NavGraph.kt` | Old Phase 6 | `NavGraphDatabase.kt` |
| `NavGraphWriter.kt` | Old Phase 6 | `GraphStateMachine.kt` |
| `PathDatabase.kt` | Old Phase 7 | `CloudPathDatabase.kt` ← **still called in overlay, needs swap** |
