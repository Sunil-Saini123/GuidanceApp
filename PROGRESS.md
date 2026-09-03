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
- [ ] **BFS / Dijkstra on `nav_graph.db`** — Query: given current `screenId` + target screen title/element, find shortest weighted path through recorded transitions
- [ ] **Path serializer** — Convert DB transition rows into a human-readable step sequence (`WhatsApp → 3 dots → Settings → Profile`)
- [ ] **"No path" detection** — Return `null` cleanly when target is unreachable in local graph to trigger Tier 2 fallback

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
| Phase 2 | Tier 1 — BFS/Dijkstra on local `nav_graph.db` | ⏳ PENDING |
| Phase 3 | Tier 2 — Firestore `CloudPathDatabase.lookup()` wired as fallback | ✅ DONE |
| Phase 4 | Tier 3 — Groq `PathGenerator.generatePath()` + `CloudPathDatabase.addEntry()` | ✅ DONE |

### Phase 1 — Structured Intent Classification
- [x] `GeminiCommandParser.ParsedCommand` extended to 3 fields: `targetApp`, `destinationScreen`, `exactTask`
- [x] System prompt enforces 3-line response format
- [x] `parseResponse()` extracts all 3 fields with resilient fallback
- [x] `[PathFinder]` log: `Parsed Intent -> App: X, Screen: Y, Task: Z`
- [x] `FloatingOverlayService.handleSubmittedQuery()` uses new structured payload

### Phase 2 — Tier 1: Local Graph Search
- [ ] `SearchPathEngine.kt` — BFS on `nav_graph.db` transitions
- [ ] Input: current `screenId` from `GraphStateMachine` + `destinationScreen` from Phase 1
- [ ] Output: ordered list of `action_label` strings or `null`
- [ ] `[PathFinder]` log: `Tier 1 Local DB: Path Found / Miss`
- [ ] Wired in `handleSubmittedQuery` before Tier 2

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

## Track 4 — State Machine & Visual Guidance Engine

> Goal: Receive a resolved path and walk the user through it step-by-step,
> verifying each transition via live accessibility events.

- [-] **`NavigationStateMachine`** (old) — Called from `FloatingOverlayService` (`NavigationStateMachine.start(path)` and `.stop()`), but is a Phase 7 **stub** with no real step-tracking or UI feedback logic
- [ ] **Step-by-step path display** — Show the current step prominently in the bubble panel; advance automatically when the accessibility service detects the expected navigation event
- [ ] **Step verification via `UiTreeAccessibilityService`** — Cross-check incoming `TYPE_WINDOW_STATE_CHANGED` events against expected next screen in path; mark step complete
- [ ] **Element highlight / tap guidance** — Optional: visually indicate which element to tap using an overlay highlight at Micro-level bounds
- [ ] **Backtrack handling** — If user navigates wrong, detect divergence and re-route or alert
- [ ] **Completion signal** — When final step matched, dismiss guide and show success in bubble

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
