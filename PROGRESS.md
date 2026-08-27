# FloatingAssistant — Progress Tracker

## ⚠ ARCHITECTURE RESET (2026-08-21)
The previous multi-tier filter pipeline (Phases 1–11, Phase D) has been **superseded** by a new
Universal OEM-Agnostic Extraction & Graph Mapping Pipeline. Old source files are preserved on disk
but are no longer called by the accessibility service.

---

## New Architecture — Universal OEM-Agnostic Pipeline

```
AccessibilityService (ON/OFF via ServiceStateManager)
 └─ onAccessibilityEvent
     ├─ MainFilter (drop own app + system UI — unchanged)
     └─ RawDumpWriter
           │
           ▼  [Phase 1 — ACTIVE]
     temp_tree.json   ← full unfiltered tree, overwritten each event
           │
           ▼  [Phase 2 — PENDING]
     clean_page.json  ← pruned, de-duped, OEM-name-normalised elements
           │
           ▼  [Phase 3 — PENDING]
     Room SQLite      ← screens table + transitions table (A* graph)
```

## Package
`com.example.floatingassistant`

---

## Phase Status

| Phase | Title                                             | Status           | Notes                                       |
|-------|---------------------------------------------------|------------------|---------------------------------------------|
| 1     | Raw Dump — Unfiltered Baseline (`temp_tree.json`) | ✅ DONE          | Scroll dedup + root tracking                |
| 2     | Universal UI Extraction (`clean_page.json`)       | ✅ DONE          | 5 rules, OEM naming, container flatten      |
| 3     | Dynamic State Machine & Graph (`nav_graph.db`)    | ✅ DONE          | SQLite graph, FORWARD/BACK detection        |
| 4     | Clean Names + Hierarchical Graph + Graph Filters  | 🔬 IN PROGRESS   | Delivering code now                         |
| —     | *(Old Phases 1–11, Phase D)*                      | 🗄 SUPERSEDED    | Files on disk, not called from service      |

---

## Phase 1 — Raw Dump: Unfiltered Baseline
**Status:** 🔬 IN PROGRESS — awaiting user test results

### Goal
Capture the exact state of every screen as a 100% unfiltered JSON snapshot.
No filtering, no normalisation, no deduplication. This is the ground-truth
baseline for designing Phases 2 and 3.

### Files Created / Modified

| File | Action | Description |
|------|--------|-------------|
| `RawDumpWriter.kt` | **REPLACED** | Traverses `AccessibilityNodeInfo` directly; dumps every field to `temp_tree.json` |
| `UiTreeAccessibilityService.kt` | **REPLACED** | Lean Phase 1 service: MainFilter drop check → RawDumpWriter only |
| `PROGRESS.md` | **MODIFIED** | Architecture reset + this section |

### What is captured per node
| Field | Source |
|-------|--------|
| `text` | `node.text` |
| `content_desc` | `node.contentDescription` |
| `resource_id` | `node.viewIdResourceName` (full, with package prefix) |
| `class_name` | `node.className` (full) |
| `is_clickable` | `node.isClickable` |
| `is_scrollable` | `node.isScrollable` |
| `is_enabled` | `node.isEnabled` |
| `is_visible_to_user` | `node.isVisibleToUser` |
| `is_focusable` | `node.isFocusable` |
| `is_long_clickable` | `node.isLongClickable` |
| `is_checkable / is_checked / is_selected` | respective node fields |
| `bounds` | `getBoundsInScreen()` → left, top, right, bottom, width, height |
| `depth` | tree depth (root = 0) |
| `child_count` | `node.childCount` |
| `children` | recursive array (depth-capped at 30) |

### Event handling
| Event | Debounce | Label in JSON |
|-------|----------|---------------|
| `TYPE_WINDOW_STATE_CHANGED` | None (immediate) | `"NAVIGATION"` |
| `TYPE_VIEW_SCROLLED` | 300 ms | `"SCROLL"` |
| `TYPE_WINDOW_CONTENT_CHANGED` | 300 ms | `"CONTENT_CHANGED"` |

### Output
- **File:** `temp_tree.json` (overwritten on every event)
- **Location:** `/sdcard/Android/data/com.example.floatingassistant/files/`
- **Pull command:**
  ```bash
  adb pull /sdcard/Android/data/com.example.floatingassistant/files/temp_tree.json
  ```

### JSON structure
```json
{
  "meta": {
    "package_name": "com.whatsapp",
    "event_type":   "NAVIGATION",
    "timestamp":    1692039482123,
    "total_nodes":  147
  },
  "tree": {
    "text": "",
    "content_desc": "",
    "resource_id": "com.whatsapp:id/home_screen_framelayout",
    "class_name": "android.widget.FrameLayout",
    "is_clickable": false,
    "is_scrollable": false,
    "is_enabled": true,
    "is_visible_to_user": true,
    "bounds": { "left": 0, "top": 0, "right": 1080, "bottom": 2400, "width": 1080, "height": 2400 },
    "depth": 0,
    "child_count": 2,
    "children": [ "..." ]
  }
}
```

### Expected Logcat
```
I/UiTreeService: Service connected [Phase 1 - Raw Dump]
I/UiTreeService: Output: /sdcard/Android/data/com.example.floatingassistant/files/temp_tree.json
I/UiTreeService: Capture ON

I/RawDumpWriter: [NAVIGATION] com.whatsapp -> temp_tree.json  (147 nodes, 42KB)
I/RawDumpWriter: [SCROLL] com.whatsapp -> temp_tree.json  (183 nodes, 51KB)
I/RawDumpWriter: [NAVIGATION] com.android.settings -> temp_tree.json  (89 nodes, 24KB)

# Scroll down — new items:
I/RawDumpWriter: [SCROLL] com.whatsapp/ConversationListActivity +23 new (170 nodes total)

# Scroll back up — nothing new:
V/RawDumpWriter: [SCROLL] com.whatsapp — 0 new nodes, scroll-up ignored
```

### Phase 1 v2 changes (scroll dedup + root tracking)
- **NAVIGATION**: clears all state for package, serialises full tree fresh
- **SCROLL/CONTENT**: appends only NEW nodes (by stable dedup key); 0 new = no write
- **Dedup key**: `resource_id` → `text|class` → `content_desc|class` → `bounds|class` (interactive only) → null (skip)
- **Debounce**: packageName captured pre-debounce; `rootInActiveWindow` fetched fresh post-debounce with package cross-check

---

## Phase 2 — Universal UI Extraction (`clean_page.json`)
**Status:** 🔬 IN PROGRESS — awaiting user test results

### Goal
Read the accumulated flat node list from Phase 1 (in-memory, no file round-trip) and
extract clean, actionable, static navigation elements — no duplicates, no dynamic data,
OEM-agnostic naming.

### Files Created / Modified

| File | Action | Description |
|------|--------|-------------|
| `CleanPageProcessor.kt` | **CREATED** | Phase 2 extraction: all 5 rules, writes `clean_page.json` |
| `UiTreeAccessibilityService.kt` | **UPDATED** | Wired Phase 2 trigger after nav + scroll-with-new-nodes |
| `RawDumpWriter.kt` | **UPDATED** | v2: scroll accumulation, per-package state, `getSnapshot()` API |

### Rules Applied (in order)

| Rule | Action |
|------|--------|
| 1 — Prune Invalid | Drop if `width/height <= 0` or `is_visible_to_user == false` |
| 2 — Drop Dynamic | Drop names > 55 chars, purely numeric, phone/date/time/relative-time/status patterns |
| 3 — Container Flatten | Clickable layout + no text → aggregate descendant texts (up to 5), mark children absorbed |
| 4 — Universal Naming | `content_desc` → `text` → stripped `resource_id` (OEM prefix removed) → spatial label |
| 5 — Name Dedup | Same name (case-insensitive) → keep first occurrence only |

### OEM Prefix Strips
`bbk_`, `sec_`, `originui_`, `miui_`, `vivo_`, `oppo_`, `samsung_`, `huawei_`, `oneplus_`, `xiaomi_`, `realme_`

Example: `com.bbk.launcher:id/bbk_search_btn` → **"search btn"**

### Spatial Labels (when no text/id available)
| Zone | Result |
|------|--------|
| Top 12%, left edge, square + clickable | `Back_Button` |
| Top 12%, right edge, square + clickable | `Menu_Button` |
| Top 12%, wide + EditText | `Search_Bar` |
| Top 12%, wide + clickable | `Action_Bar` |
| Bottom 12%, square + clickable | `Bottom_Nav_Item` |
| Bottom 12%, wide + clickable | `Bottom_Bar` |
| Any, scrollable | `Scrollable_List` |

### Output
- **File:** `clean_page.json` (overwritten each time; Phase 1 accumulation means content grows on scroll)
- **Location:** `/sdcard/Android/data/com.example.floatingassistant/files/`
- **Pull command:**
  ```bash
  adb pull /sdcard/Android/data/com.example.floatingassistant/files/clean_page.json
  ```

### JSON structure
```json
{
  "meta": {
    "package_name":  "com.whatsapp",
    "root_name":     "ConversationListActivity",
    "timestamp":     1692039482123,
    "element_count": 12
  },
  "elements": [
    { "name": "Chats",    "is_clickable": true,  "class_name": "android.widget.TextView",  "source": "direct"    },
    { "name": "Status",   "is_clickable": true,  "class_name": "android.widget.TextView",  "source": "direct"    },
    { "name": "New Chat", "is_clickable": true,  "class_name": "android.widget.ImageButton","source": "container" },
    { "name": "Search_Bar","is_clickable": true, "class_name": "android.widget.EditText",  "source": "direct"    }
  ]
}
```

### Expected Logcat (Phase 1 + 2)
```
I/UiTreeService:     Service connected [Phase 1+2 - Raw Dump + Clean Extraction]
I/RawDumpWriter:     [NAVIGATION] com.whatsapp/ConversationListActivity — 147 nodes
I/RawDumpWriter:     Written temp_tree.json: com.whatsapp/ConversationListActivity (147 nodes, 38KB)
I/CleanPageProcessor: Clean page written: com.whatsapp/ConversationListActivity (9 elements, 2KB)

I/RawDumpWriter:     [SCROLL] com.whatsapp/ConversationListActivity +18 new (165 nodes total)
I/CleanPageProcessor: Clean page written: com.whatsapp/ConversationListActivity (14 elements, 3KB)

V/RawDumpWriter:     [SCROLL] com.whatsapp — 0 new nodes, scroll-up ignored
# (no Phase 2 write on scroll-up)
```

---

## Phase 3 — Dynamic State Machine & Graph Mapping
**Status:** 🔬 IN PROGRESS — awaiting user test results

### Goal
Map the clean elements from Phase 2 into a persistent directed graph
(Nodes = Screens, Edges = Clicks/Backs) stored in SQLite. The graph structure
supports A* pathfinding for future autonomous navigation.

### Files Created / Modified

| File | Action | Description |
|------|--------|-------------|
| `NavGraphDatabase.kt` | **CREATED** | SQLiteOpenHelper — `screens` + `transitions` tables, no Room/kapt |
| `GraphStateMachine.kt` | **CREATED** | In-memory navigation stacks, screen-ID computation, FORWARD/BACK detection |
| `CleanPageProcessor.kt` | **UPDATED** | Added `extractSync()` + `writeToFile()` — Phase 3 re-uses Phase 2 extraction result |
| `UiTreeAccessibilityService.kt` | **UPDATED** | Added `TYPE_VIEW_CLICKED`, init `GraphStateMachine`, chained pipeline |

### Screen Identity (Deterministic Hash)

```
screenId = "$packageName::$screenTitle"
```

`screenTitle` = the topmost non-clickable element in the top 20% of the screen whose
name is not a spatial label (`Back_Button`, `Menu_Button`, etc.).
Fallback: the Activity class name from the navigation event.

> This is content-based, not class-based — fully OEM-agnostic.

### Navigation Stack Machine

Each package has its own `ArrayDeque<String>` (screen IDs).

| Condition | Action |
|-----------|--------|
| New `screenId` == top of stack | Content refresh — upsert elements only |
| New `screenId` NOT in stack | **FORWARD** → push, draw `CLICK` edge from previous top |
| New `screenId` found below current in stack | **BACK** → draw `BACK` edge, pop everything above |
| SCROLL / CONTENT_CHANGED event | No stack change — upsert elements only |

### Click Label Capture

- `TYPE_VIEW_CLICKED` is now listened to in the service
- Tapped element's `contentDescription` (or `text`) is stored per package as `lastClickedLabel`
- On the next FORWARD navigation, this label becomes the `action_label` of the CLICK edge
- Cleared after each navigation event to prevent stale labels
- Fallback if no click recorded: the destination screen's title

### Database Schema

**screens**
| Column | Type | Description |
|--------|------|-------------|
| `id` | TEXT PK | `$packageName::$screenTitle` |
| `package_name` | TEXT | |
| `screen_title` | TEXT | Header text used to derive the ID |
| `root_class` | TEXT | Activity class name |
| `elements_json` | TEXT | JSON array of Phase 2 clean elements |
| `visit_count` | INTEGER | Incremented on each revisit |
| `first_seen` / `last_seen` | INTEGER | Epoch ms |

**transitions**
| Column | Type | Description |
|--------|------|-------------|
| `id` | INTEGER PK AUTOINCREMENT | |
| `from_screen_id` | TEXT FK | |
| `to_screen_id` | TEXT FK | |
| `action_label` | TEXT | Element tapped, or `"BACK"` |
| `action_type` | TEXT | `"CLICK"` or `"BACK"` |
| `traversal_count` | INTEGER | Incremented on re-traversal |
| `weight` | REAL | A* edge cost (default 1.0) |
| `first_seen` / `last_seen` | INTEGER | Epoch ms |

### Output Files

| File | Format | Description |
|------|--------|-------------|
| `nav_graph.db` | SQLite | Persistent graph database |
| `nav_graph.json` | JSON | Human-readable graph snapshot, updated after every event |

**Pull commands:**
```bash
adb pull /sdcard/Android/data/com.example.floatingassistant/files/nav_graph.json
adb pull /sdcard/Android/data/com.example.floatingassistant/files/nav_graph.db
```

### nav_graph.json structure
```json
{
  "package_name": "com.whatsapp",
  "timestamp": 1692039482123,
  "current_stack": [
    "com.whatsapp::Chats",
    "com.whatsapp::John Smith"
  ],
  "stack_depth": 2,
  "screens": [
    { "id": "com.whatsapp::Chats",      "screen_title": "Chats",      "visit_count": 3, "element_count": 9  },
    { "id": "com.whatsapp::John Smith", "screen_title": "John Smith", "visit_count": 1, "element_count": 12 }
  ],
  "transitions": [
    { "from": "com.whatsapp::Chats", "to": "com.whatsapp::John Smith", "action_label": "John Smith", "action_type": "CLICK", "traversal_count": 1, "weight": 1.0 },
    { "from": "com.whatsapp::John Smith", "to": "com.whatsapp::Chats", "action_label": "BACK",       "action_type": "BACK",  "traversal_count": 1, "weight": 1.0 }
  ]
}
```

### Expected Logcat (all 3 phases)
```
I/UiTreeService:      Service connected [Phase 1 + 2 + 3]
I/GraphStateMachine:  Initialised — DB: nav_graph.db  JSON: nav_graph.json

# User opens WhatsApp → Chats screen
I/RawDumpWriter:      [NAVIGATION] com.whatsapp/ConversationListActivity — 147 nodes
I/CleanPageProcessor: Clean page written: com.whatsapp/ConversationListActivity (9 elements, 2KB)
I/GraphStateMachine:  [FORWARD] (first screen) com.whatsapp::Chats
I/GraphStateMachine:  nav_graph.json: com.whatsapp — 1 screens, 0 transitions

# User scrolls down — new contacts
I/RawDumpWriter:      [SCROLL] com.whatsapp/ConversationListActivity +18 new (165 nodes total)
I/CleanPageProcessor: Clean page written: com.whatsapp/ConversationListActivity (14 elements, 3KB)

# User taps "John Smith" contact
V/GraphStateMachine:  Click recorded: com.whatsapp → John Smith

# Navigation fires → new screen
I/RawDumpWriter:      [NAVIGATION] com.whatsapp/ConversationActivity — 89 nodes
I/GraphStateMachine:  [FORWARD] com.whatsapp::Chats -[John Smith]-> com.whatsapp::John Smith
I/GraphStateMachine:  nav_graph.json: com.whatsapp — 2 screens, 1 transitions

# User presses Back
I/RawDumpWriter:      [NAVIGATION] com.whatsapp/ConversationListActivity — 147 nodes
I/GraphStateMachine:  [BACK] com.whatsapp::John Smith → com.whatsapp::Chats  (popped 0 screens)
I/GraphStateMachine:  nav_graph.json: com.whatsapp — 2 screens, 2 transitions
```

---

## Archived — Old Pipeline (Phases 1–11 / Phase D)
> These sections are preserved for reference. The code files still exist on disk
> but are no longer called by the accessibility service.

---

### Old Phase 1 — Project Scaffolding & Main UI *(archived)*

### Files Created / Modified
| File | Action | Description |
|------|--------|-------------|
| `PROGRESS.md` | CREATED | This file |
| `app/src/main/AndroidManifest.xml` | MODIFIED | Added BIND_ACCESSIBILITY_SERVICE permission; service entry wired in Phase 2 |
| `app/src/main/java/.../ServiceStateManager.kt` | CREATED | Singleton holding shared isServiceEnabled StateFlow |
| `app/src/main/java/.../MainActivity.kt` | MODIFIED | Full Compose UI: permission banner + ON/OFF switch |
| `app/src/main/res/values/strings.xml` | MODIFIED | Added accessibility-related string resources |

### Logic Implemented
- On onCreate, checks whether Accessibility Service is enabled via Settings.Secure.
- NOT enabled → shows banner with "Grant Permission" button → deep-links to ACTION_ACCESSIBILITY_SETTINGS.
- Enabled → shows main card with a Switch (ON / OFF).
- Switch state stored in ServiceStateManager.isServiceEnabled (MutableStateFlow).
- onResume re-evaluates permission so UI refreshes after user returns from Settings.

---

## Phase 2 — Accessibility Service & Main Filter
**Status:** ⏳ Pending

### Planned
- UiTreeAccessibilityService.kt
- accessibility_service_config.xml
- AndroidManifest.xml service registration
- Main Filter: drop com.android.systemui; log "Cannot access"

---

## Phase 3 — Inbetween Filter (Parsing & Hashing)
**Status:** ⏳ Pending

### Planned
- UiNode.kt (lightweight data class, FNV-1a hash ID)
- FnvHash.kt (pure-Kotlin FNV-1a 32/64-bit)
- UiTreeParser.kt (recursive AccessibilityNodeInfo traversal)

---

## Phase 4 — Secondary Filter & Selective Tree Storage
**Status:** ⏳ Pending

### Planned
- ContextRootTracker.kt
- ScrollDeduplicator.kt
- JsonTreeWriter.kt (non-blocking coroutine writer → ui_tree_temp.json)

---

## Phase 2 — Accessibility Service & Main Filter
**Status:** ✅ Complete — awaiting user test confirmation

### Files Created / Modified
| File | Action | Description |
|------|--------|-------------|
| `UiTreeAccessibilityService.kt` | REPLACED | Full service: StateFlow observer, event hook, Main Filter hand-off |
| `MainFilter.kt` | CREATED | Isolated filter object with sealed FilterResult |
| `res/xml/accessibility_service_config.xml` | UPDATED | Expanded comments; config unchanged |

### Logic Implemented
- **ON/OFF gate**: `captureEnabled` cached from `ServiceStateManager.isServiceEnabled` StateFlow via `collectLatest`. Zero-cost check on every event.
- **Event sub-type guard**: only `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOW_CONTENT_CHANGED` (subtree/text sub-types only) wake the pipeline.
- **MainFilter rule 1 — Cannot Access**: root node is null → logs `W/MainFilter: Cannot access [pkg]`, returns.
- **MainFilter rule 2 — Own app**: own package → dropped silently.
- **MainFilter rule 3 — System UI**: prefix-match against 13 common system/launcher/IME packages → logs `D/MainFilter: Dropped [pkg] — System UI`, returns.
- **Passed frame**: logs `I/UiTreeService: [Phase2] Live root for '...' childCount=N → awaiting Phase 3`, then recycles node.
- **Lifecycle**: `serviceScope` (SupervisorJob + Main.immediate) cancelled in `onDestroy`.

---

## Phase 3 — Inbetween Filter (Parsing & Hashing)
**Status:** ✅ Complete — awaiting user test confirmation

### Files Created / Modified
| File | Action | Description |
|------|--------|-------------|
| `FnvHash.kt` | CREATED | Pure-Kotlin FNV-1a 64-bit: hash64() and zero-alloc hash64of() |
| `UiNode.kt` | CREATED | Immutable data class: nodeId, text, resourceId, className, isClickable, children |
| `UiTreeParser.kt` | CREATED | Recursive parser; depth cap 25; recycles every child NodeInfo obtained |
| `UiTreeAccessibilityService.kt` | MODIFIED | Replaced Phase 2 TODO with real UiTreeParser.parse() call; Phase 4 TODO inserted |
| `MainFilter.kt` | MODIFIED | Added com.vivo.systemuiplugin, com.vivo.SystemPlugin, com.bbk.launcher to drop list |

### Hash Key Design
```
nodeId = FNV-1a64( resourceId | text | className )
```
Position (bounds) intentionally excluded → same logical node = same ID across scroll offsets.

### Normalisation Rules
| Raw field | Normalisation |
|-----------|--------------|
| text | Use contentDescription if text is empty |
| resourceId | Strip `package:id/` prefix → keep only the id name |
| className | Strip package prefix → simple name only (e.g. `TextView`) |

### Expected Logcat (Phase 3)
```
D/InbetweenFilter: Parsed 'com.android.settings' → 47 nodes (root childCount=17)
I/UiTreeService:   [Phase3] 'com.android.settings' → 47 nodes parsed
```

---

## Phase 4 — Secondary Filter & Selective Tree Storage
**Status:** ✅ Complete — awaiting user test confirmation

### Files Created / Modified
| File | Action | Description |
|------|--------|-------------|
| `ContextRootTracker.kt` | CREATED | Per-package nav stack; forward/back detection from className |
| `SecondaryFilter.kt` | CREATED | Viewport hash dedup + scroll accumulation via LinkedHashSet |
| `JsonTreeWriter.kt` | CREATED | Non-blocking IO coroutine; atomic write to cacheDir |
| `UiTreeAccessibilityService.kt` | REPLACED | Full Phase 4 pipeline with debounce |

### Key Behaviours
| Behaviour | Mechanism |
|-----------|-----------|
| Stop over-scanning | 250ms debounce on CONTENT_CHANGED; navigation fires immediately |
| No duplicate frames | XOR viewport hash — skip if identical to last frame |
| Scroll down: append | LinkedHashSet per screen; new nodeIds → appended to ordered list |
| Scroll up: skip | 0 new nodeIds found → ProcessResult.Skipped |
| Cross-app: separate | appStates keyed by packageName; fully isolated |
| Back navigation: restore | ContextRootTracker pops stack; existing ScreenState reused |

### JSON Output
- Location: `<cacheDir>/ui_tree_temp.json`
- Pull via: `adb shell run-as com.example.floatingassistant cat /data/user/0/com.example.floatingassistant/cache/ui_tree_temp.json`
- Structure: `{ packageName: { rootName: { items: [...] } } }`
- Written only on real changes (Skipped results do not trigger a write)

### Expected Logcat
```
# Navigate to Settings
I/UiTreeService: [Phase4] NEW 'com.android.settings/Settings' — 8 items captured

# Scroll down → new items appear
I/UiTreeService: [Phase4] SCROLL 'com.android.settings/Settings' — +5 new (13 total)

# Scroll back up → no new items
(silence — Skipped, no log)

# Tap Bluetooth → new screen
I/UiTreeService: [Phase4] NEW 'com.android.settings/Bluetooth' — 4 items captured

# Press back → back to Settings
I/UiTreeService: [Phase4] BACK 'com.android.settings/Settings' — 13 items total
```


---

## Phase 5 — Clean Per-Page Extractor (Tier 2)
**Status:** ✅ Complete — awaiting user test confirmation

### Architecture
| Tier | File | Lifecycle |
|------|------|-----------|
| 1 | `ui_tree_temp.json` | Wiped on app exit / full mapping done |
| 2 | `ui_clean_page.json` | Wiped on navigation, appended on scroll |
| 3 | Graph DB | Future / Persistent |

### Files Created / Modified
| File | Action | Description |
|------|--------|-------------|
| `CleanPageExtractor.kt` | CREATED | DFS walker; Rule1=text→keep+stop, Rule2=clickable+rid→keep+stop, Rule3=empty→recurse |
| `CleanPageWriter.kt` | CREATED | Async IO writer; append mode reads→merges→writes |
| `UiTreeAccessibilityService.kt` | MODIFIED | Added `cleanPageFile`, `triggerCleanWrite()`, wired into all ProcessResult branches |

### Cleaning Rules Applied
| Rule | Action |
|------|--------|
| Non-empty `text` | KEEP as `name`, STOP recursing |
| No text + clickable + non-structural rid | KEEP `rid` as `name`, STOP recursing |
| Empty text + empty/structural rid | SKIP node, RECURSE into children |
| Dedup by `nodeId` | Hard dedup — same node never written twice |
| Dedup by `name` string | Semantic dedup — same label from sibling branches skipped |

### Expected Clean Output
```json
{
  "root": "SettingsHomepage",
  "elements": [
    { "id": -83783365167035458, "name": "More connections" },
    { "id": 5582002470868646074, "name": "Security & privacy" },
    { "id": 1234567890123456789, "name": "Apps" }
  ]
}
```

### Pull Command
```
adb pull /sdcard/Android/data/com.example.floatingassistant/files/ui_clean_page.json
```


---

## Phase 7 — Floating Bubble Overlay & Navigation State Machine
**Status:** ✅ Complete — awaiting user test confirmation

### Files Created / Modified
| File | Action | Description |
|------|--------|-------------|
| `FloatingOverlayService.kt` | CREATED | Foreground service: draggable bubble, control panel, Submit/Stop |
| `NavigationStateMachine.kt` | CREATED | StateFlow-backed guide state: start/advance/stop |
| `PathDatabase.kt` | CREATED | Stub DB: lookup(query) → path string |
| `AndroidManifest.xml` | MODIFIED | SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE_SPECIAL_USE, service entry |

---

## Phase 11 — Static vs. Dynamic Content Filtering (Improved UI Filter — v4)
**Status:** ✅ Complete (v4) — awaiting user test confirmation

### Architecture
```
SecondaryFilter.process()
       ↓   items (List<UiNode>)
  StaticDynamicFilter.classify()   ← v4 Logic (this file)
       ↓   static-only items
  CleanPageExtractor.extract()     ← Rule 1/2/3 unchanged
       ↓
  CleanPageWriter  /  NavGraph
```

### Files Modified (v4 revision)
| File | Action | Description |
|------|--------|-------------|
| `StaticDynamicFilter.kt` | REPLACED | Full rewrite implementing the new 4-step logic with bounds/position checks |
| `UIKeywords.kt` | CREATED | Extracted keyword lists (Navigation, Settings, Actions, Social) into one regex |
| `UiNode.kt` | MODIFIED | Added `boundsInScreen: Rect?` to support positional and size checks |
| `UiTreeParser.kt` | MODIFIED | Extracts and assigns `boundsInScreen` during `UiNode` instantiation |

---

### Step 1: Basic Info
- `unified_name` checks `contentDescription`, then `text`, then the last part of `viewIdResourceName`.
- If `unified_name` is empty/null AND `isClickable == false`, the node is dropped immediately (unless it's a structural container holding valid children).

---

### Step 2: Priority KEEP (Whitelist)
If the node matches ANY of these rules, keep it and skip Steps 3 and 4:
- **Wordlist**: `unified_name` matches the `UIKeywords` standalone list.
- **Top/Bottom Bars**: `isClickable == true` AND located in the top 15% or bottom 15% of the screen height.
- **Square Icons/Buttons**: `isClickable == true` AND width ≤ 250px, height ≤ 250px, and width/height ratio is between 0.7 and 1.3.
- **Important UI Classes**: `className` contains `ImageButton`, `Chip`, `FloatingActionButton`, `TabLayout`, or `BottomNavigationView`.
- **App Icons (Home Screen)**: `app_package` contains `launcher`, is clickable, and `unified_name` length ≤ 25.

---

### Step 3: Priority DROP (Blacklist)
Checked if not kept by Step 2. Matches result in an immediate drop:
- **Too Long**: Text contains a newline (`\n`) OR `unified_name` length > 35.
- **Dynamic Numbers/Dates**: Matches regex for time (10:50), dates (8/11/26), or relative time (5 hours ago).
- **Dynamic Metrics**: Matches regex for counts (e.g., 6 unread, 1.2M views, 5 likes).
- **Status/Media Sentences**: Contains phrases like ` - Go to channel`, ` - play video`, or `, unread status`.

---

### Step 4: The Final Catch-All
For middle-screen items that made it to this step:
- **KEEP** if it is `isClickable == true` AND `unified_name` length ≤ 25.
- **DROP** everything else.

---

### Expected Logcat (Phase 11 — v4)
```
D/StaticDynFilter: classify [com.whatsapp]: kept=... dropped=...
V/StaticDynFilter: S2 KEEP: "Settings" cls=TextView
V/StaticDynFilter: S3 DROP (Too Long/Multi-line): len=42
V/StaticDynFilter: S4 KEEP (Catch-All): "Archive"
V/StaticDynFilter: S4 DROP (Catch-All fallback): "Hey Alice!"
```

### Pull Commands
```bash
adb pull /sdcard/Android/data/com.example.floatingassistant/files/ui_clean_page.json
adb pull /sdcard/Android/data/com.example.floatingassistant/files/ui_nav_graph.json
```


---


## Phase 9 — Floating Bubble Touch & State Overhaul
**Status:** ✅ Complete — awaiting user test confirmation

### Files Created / Modified
| File | Action | Description |
|------|--------|-------------|
| `FloatingOverlayService.kt` | REPLACED | Full rewrite: flag modes, drag priority, two-tap Stop/exit |

### Window Flag Modes
| Mode | Flags | Effect |
|------|-------|--------|
| **Idle** | `FLAG_NOT_FOCUSABLE \| FLAG_NOT_TOUCH_MODAL \| FLAG_LAYOUT_IN_SCREEN` | Touches outside bubble pass to system UI |
| **Typing** | `FLAG_NOT_TOUCH_MODAL \| FLAG_WATCH_OUTSIDE_TOUCH \| FLAG_LAYOUT_IN_SCREEN` | IME attaches; outside taps detectable |

### Drag Priority Logic
- On `ACTION_MOVE`, once accumulated movement > `TAP_SLOP_PX` → `dragLocked = true`
- While drag locked: keyboard hidden → panel removed → idle flags restored → bubble moves
- `dragLocked` resets on every `ACTION_DOWN`

### Stop Button Multi-Tap Flow
| Tap | Action |
|-----|--------|
| 1st tap | `NavigationStateMachine.stop()` → status = "Stopped" |
| 2nd tap (consecutive) | `AlertDialog`: "Would you like to exit the app completely?" |
| Dialog Yes | `NavigationStateMachine.stop()` + remove views + `FloatingOverlayService.stop()` + `exitProcess(0)` |
| Dialog No | Dismiss; `stopTapCount` reset to 0 |

### AlertDialog in Service
Uses `ContextThemeWrapper(applicationContext, Theme_DeviceDefault_Light_Dialog_Alert)` + `dialog.window?.setType(TYPE_APPLICATION_OVERLAY)` — the standard approach for overlay services. Wrapped in try/catch; any OEM restriction logs an error and continues.

### Expected Logcat (Phase 9)
```
# Normal stop
I/FloatingOverlay: Stop tap 1 — navigation stopped

# Second tap → dialog
I/FloatingOverlay: Stop tap 2 — showing exit dialog

# User confirms exit
I/FloatingOverlay: Exit confirmed by user — shutting down
I/FloatingOverlay: Overlay service destroyed

# Drag priority triggered
(panel removed, keyboard dismissed — no log by design, smooth drag continues)
```

---

## Phase 6 — Persistent Navigation Graph (Tier 3)
**Status:** ✅ Complete — awaiting user test confirmation

### Architecture (Complete 3-tier pipeline)
| Tier | File | Lifecycle | Content |
|------|------|-----------|---------|
| 1 | `ui_tree_temp.json` | Volatile | Raw bloated tree, all structural nodes |
| 2 | `ui_clean_page.json` | Per-navigation | Clean flat list for current page only |
| 3 | `ui_nav_graph.json` | Persistent | Growing graph, all apps, all sessions |

### Files Created / Modified
| File | Action | Description |
|------|--------|-------------|
| `NavGraph.kt` | CREATED | In-memory graph: global node registry + screens + directed edges |
| `NavGraphWriter.kt` | CREATED | Load on startup, save via snapshot+IO pattern |
| `UiTreeAccessibilityService.kt` | REPLACED | `processCleanAndGraph()` feeds Tier2+Tier3 from one extraction |

### Graph JSON structure
```json
{
  "version": 1,
  "stats": { "nodes": 42, "screens": 8, "edges": 5 },
  "nodes": { "<id>": "<name>", ... },
  "apps": {
    "com.android.settings": {
      "screens": {
        "SettingsHomepage": { "root": "SettingsHomepage", "nodes": [id, id, ...] },
        "Bluetooth": { "root": "Bluetooth", "nodes": [id, ...] }
      },
      "edges": [
        { "from": "SettingsHomepage", "to": "Bluetooth", "via": null, "count": 3 }
      ]
    }
  }
}
```

### Pull all 3 tiers
```bash
adb pull /sdcard/Android/data/com.example.floatingassistant/files/ui_tree_temp.json
adb pull /sdcard/Android/data/com.example.floatingassistant/files/ui_clean_page.json
adb pull /sdcard/Android/data/com.example.floatingassistant/files/ui_nav_graph.json
```

---

## Phase D — Debug: Raw Tree Extraction (Temporary)
**Status:** 🔬 IN PROGRESS — awaiting user analysis

> ⚠ **This is a temporary debug phase.** Phase 11 (StaticDynamicFilter) is fully
> preserved and will be re-enabled by flipping one boolean back to `false`.

### Purpose
The Phase 11 filter algorithms need to be tuned based on real-world data. Before
refining the whitelist/blacklist rules, we need to see the **complete, raw
accessibility trees** for each app screen — every node, every property, no
omissions. This phase captures that data.

### Toggle
```kotlin
// UiTreeAccessibilityService.kt — companion object
const val DEBUG_SAVE_RAW_TREES = true   // ← flip to false to restore production
```

### What Changes (and What Doesn't)

| Component | When DEBUG=true | When DEBUG=false |
|-----------|-----------------|------------------|
| `MainFilter` | ✅ Runs normally | ✅ Runs normally |
| `UiTreeParser` | ✅ Runs normally (full tree) | ✅ Runs normally |
| `ContextRootTracker` | ✅ Runs normally | ✅ Runs normally |
| `SecondaryFilter` | ⏭ **SKIPPED** | ✅ Runs normally |
| `StaticDynamicFilter` | ⏭ **SKIPPED** | ✅ Runs normally |
| `CleanPageExtractor` | ⏭ **SKIPPED** | ✅ Runs normally |
| `CleanPageWriter` | ⏭ **SKIPPED** | ✅ Runs normally |
| `NavGraph` / `NavGraphWriter` | ⏭ **SKIPPED** | ✅ Runs normally |
| `RawTreeWriter` | ✅ **ACTIVE** | ⏭ Not called |

### Files Created / Modified

| File | Action | Description |
|------|--------|-------------|
| `RawTreeWriter.kt` | **CREATED** | Serializes the full `UiNode` tree to a unique JSON file per page |
| `UiTreeAccessibilityService.kt` | **MODIFIED** | Added `DEBUG_SAVE_RAW_TREES` flag + debug bypass block |
| `PROGRESS.md` | **MODIFIED** | This entry |

### Output Files
- **Location:** `/sdcard/Android/data/com.example.floatingassistant/files/`
- **Naming:** `raw_page_<package>_<epoch_seconds>.json`
  - e.g. `raw_page_com_whatsapp_1692039482.json`
- **One file per captured page** — no overwriting between screens
- **Pretty-printed** with 2-space indentation for manual readability

### Pull Command
```bash
# Pull ALL raw dumps at once:
adb pull /sdcard/Android/data/com.example.floatingassistant/files/

# Or list what's there first:
adb shell ls /sdcard/Android/data/com.example.floatingassistant/files/ | grep raw_page
```

### JSON Structure
```json
{
  "meta": {
    "package": "com.whatsapp",
    "root_name": "ConversationListActivity",
    "event_type": "NAVIGATION",
    "timestamp_ms": 1692039482123,
    "total_nodes": 147
  },
  "tree": {
    "id": -3984756123456789,
    "text": "WhatsApp",
    "resource_id": "com.whatsapp:id/toolbar",
    "class": "Toolbar",
    "clickable": false,
    "bounds": { "left": 0, "top": 0, "right": 1080, "bottom": 196, "width": 1080, "height": 196 },
    "children": [ "..." ]
  }
}
```

### Expected Logcat (Phase D)
```
W/UiTreeService: ╔══════════════════════════════════════════════════════════╗
W/UiTreeService: ║  DEBUG_SAVE_RAW_TREES = true                             ║
W/UiTreeService: ║  All filters bypassed. Raw trees saved to: ...           ║
W/UiTreeService: ╚══════════════════════════════════════════════════════════╝

D/InbetweenFilter: Parsed 'com.whatsapp' → 147 nodes (root childCount=3)
D/UiTreeService: [RAW] Dumping com.whatsapp/ConversationListActivity (NAVIGATION, 147 nodes)
I/RawTreeWriter: RAW DUMP → .../raw_page_com_whatsapp_1692039482.json  (18KB, 147 nodes)

D/InbetweenFilter: Parsed 'com.whatsapp' → 152 nodes (root childCount=3)
D/UiTreeService: [RAW] Dumping com.whatsapp/ConversationListActivity (SCROLL/CONTENT, 152 nodes)
I/RawTreeWriter: RAW DUMP → .../raw_page_com_whatsapp_1692039483.json  (19KB, 152 nodes)
```

### How to Revert (Resume Phase 11)
1. Open `UiTreeAccessibilityService.kt`
2. Change line: `const val DEBUG_SAVE_RAW_TREES = true` → `false`
3. Rebuild and install — the full 3-tier production pipeline resumes automatically.
   `RawTreeWriter.kt` remains in the codebase but is never called.

