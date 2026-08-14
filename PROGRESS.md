# FloatingAssistant — Progress Tracker

## Architecture Overview
```
App Open
 └─ Check Accessibility Permission
     ├─ NO  → Prompt user to enable in Settings
     └─ YES → Show Main UI
                └─ ON/OFF Switch
                    ├─ OFF → Idle
                    └─ ON  → Loop: Get Current UI Tree
                                └─ Main Filter        (drop system UI)
                                    └─ Inbetween Filter  (parse + FNV-1a hash)
                                        └─ Secondary Filter  (context root, scroll dedup, JSON write)
```

## Package
`com.example.floatingassistant`

---

## Phase Status

| Phase | Title                                    | Status         | Notes                      |
|-------|------------------------------------------|----------------|----------------------------|
| 1     | Project Scaffolding & Main UI            | ✅ DONE        | Awaiting user test         |
| 2     | Accessibility Service & Main Filter      | ✅ DONE        | Awaiting user test         |
| 3     | Inbetween Filter (Parsing & Hashing)     | ✅ DONE        | Awaiting user test         |
| 4     | Secondary Filter & Selective Tree Storage| ✅ DONE        | Awaiting user test         |
| 5     | Clean Per-Page Extractor (Tier 2)        | ✅ DONE        | Awaiting user test         |
| 6     | Persistent Navigation Graph (Tier 3)     | ✅ DONE        | Awaiting user test         |
| 7     | Floating Bubble Overlay & State Machine  | ✅ DONE        | Awaiting user test         |
| 8     | Custom OS Detection & Metadata           | ✅ DONE        | Awaiting user test         |
| 9     | Floating Bubble Touch & State Overhaul   | ✅ DONE        | Awaiting user test         |
| 11    | Static vs. Dynamic Content Filtering     | ✅ DONE        | Improved UI Filter (v4)    |

---

## Phase 1 — Project Scaffolding & Main UI
**Status:** ✅ Complete — awaiting user test confirmation

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
