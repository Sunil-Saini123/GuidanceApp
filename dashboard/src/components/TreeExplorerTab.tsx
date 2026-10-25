import React, { useState, useMemo, useEffect } from 'react';
import {
  TelemetryEvent,
  BoundingBox,
  NavGraphData,
  NavGraphApp,
  NavGraphScreen,
  NavGraphTransition
} from '../types';
import {
  ChevronRight,
  ChevronDown,
  Info,
  Copy,
  Check,
  Search,
  Code,
  List,
  X,
  FolderOpen,
  GitFork,
  Smartphone,
  ExternalLink,
  Layers
} from 'lucide-react';

interface TreeExplorerTabProps {
  events: TelemetryEvent[];
  navGraph?: NavGraphData | null;
}

export interface TreeElementItem {
  name: string;
  is_clickable?: boolean;
  class_name?: string;
  resource_id?: string;
  bounds?: BoundingBox;
}

interface ScreenGroup {
  id: string;
  screenTitle: string;
  packageName: string;
  timestamp: number;
  elements: TreeElementItem[];
}

// ── Layer Styling Map ─────────────────────────────────────────────────────────

// [PERMANENT FIX] Do not remove this! Code-editor nested tree indentation and color palette.
const getLayerStyle = (layer: number) => {
  switch (layer) {
    case 0:
    case 1:
      return {
        bg: 'bg-[#151a26]',
        hover: 'hover:bg-[#1d2434]',
        borderLeft: 'border-l-[4px] border-emerald-400',
        guideBorder: 'border-l-2 border-emerald-500/40',
        badgeBg: 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/30',
        paddingLeft: 'pl-4',
        layerName: 'Root'
      };
    case 2:
      return {
        bg: 'bg-[#10141e]',
        hover: 'hover:bg-[#171d2b]',
        borderLeft: 'border-l-[4px] border-sky-400',
        guideBorder: 'border-l-2 border-sky-500/40',
        badgeBg: 'bg-sky-500/20 text-sky-300 border border-sky-500/30',
        paddingLeft: 'pl-6',
        layerName: 'Depth 2'
      };
    case 3:
      return {
        bg: 'bg-[#0c0e16]',
        hover: 'hover:bg-[#121622]',
        borderLeft: 'border-l-[4px] border-indigo-400',
        guideBorder: 'border-l-2 border-indigo-500/40',
        badgeBg: 'bg-indigo-500/20 text-indigo-300 border border-indigo-500/30',
        paddingLeft: 'pl-8',
        layerName: 'Depth 3'
      };
    default:
      return {
        bg: 'bg-[#08090f]',
        hover: 'hover:bg-[#0e101a]',
        borderLeft: 'border-l-[4px] border-purple-400',
        guideBorder: 'border-l-2 border-purple-500/40',
        badgeBg: 'bg-purple-500/20 text-purple-300 border border-purple-500/30',
        paddingLeft: 'pl-10',
        layerName: `Depth ${layer}`
      };
  }
};

// ── Main Component ────────────────────────────────────────────────────────────

export const TreeExplorerTab: React.FC<TreeExplorerTabProps> = ({ events, navGraph }) => {
  // View Modes: 'nav_graph' (Real Navigation Tree from .db), 'live' (Current screen capture), 'json'
  const [viewMode, setViewMode] = useState<'nav_graph' | 'live' | 'json'>(() => {
    return navGraph && navGraph.apps && navGraph.apps.length > 0 ? 'nav_graph' : 'nav_graph';
  });

  const [copied, setCopied] = useState(false);
  const [copiedItemIdx, setCopiedItemIdx] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');

  // Selected App in Nav Graph Mode
  const [selectedAppPkg, setSelectedAppPkg] = useState<string>(() => {
    if (navGraph && navGraph.apps && navGraph.apps.length > 0) {
      // Prioritize Settings or first app with multiple screens
      const settings = navGraph.apps.find((a) => a.package_name.includes('settings'));
      if (settings) return settings.package_name;
      const multi = navGraph.apps.find((a) => a.screens.length > 1);
      return multi ? multi.package_name : navGraph.apps[0].package_name;
    }
    return '';
  });

  // Keep selectedAppPkg in sync when navGraph loads or updates
  useEffect(() => {
    if (navGraph && navGraph.apps && navGraph.apps.length > 0) {
      setSelectedAppPkg((prev) => {
        if (prev && navGraph.apps.some((a) => a.package_name === prev)) return prev;
        const settings = navGraph.apps.find((a) => a.package_name.includes('settings'));
        if (settings) return settings.package_name;
        return navGraph.apps[0].package_name;
      });
    }
  }, [navGraph]);

  // Track expanded transition nodes in Nav Graph
  // Key: transition_key, Value: boolean
  const [expandedTransitions, setExpandedTransitions] = useState<Record<string, boolean>>({});

  // Active info modal for element details
  const [selectedElement, setSelectedElement] = useState<{
    element: any;
    screenTitle: string;
  } | null>(null);

  // [PERMANENT FIX] Do not remove this! Cumulative Live UI Element Capture & Scroll Merging.
  // Instead of dropping subsequent telemetry events for an existing screen, merge newly scrolled elements
  // into the screen group. Ensures sub-screens like WhatsApp Settings show all elements (Chats, Appearance, Notifications)
  // across vertical scroll passes.
  const liveScreens: ScreenGroup[] = useMemo(() => {
    const list: ScreenGroup[] = [];
    const screensMap = new Map<string, ScreenGroup>();

    for (const event of events) {
      if (event.elements && event.elements.length > 0) {
        const title = event.screenTitle || 'Unknown Screen';
        const pkg = event.packageName || 'unknown.app';
        const dedupeKey = `${pkg}::${title}`;

        let group = screensMap.get(dedupeKey);
        if (!group) {
          group = {
            id: dedupeKey,
            screenTitle: title,
            packageName: pkg,
            timestamp: event.timestamp,
            elements: [...event.elements]
          };
          screensMap.set(dedupeKey, group);
          list.push(group);
        } else {
          // Merge newly scrolled elements into existing screen group
          const seenNames = new Set(group.elements.map((e) => e.name?.trim().toLowerCase()));
          for (const el of event.elements) {
            const normName = el.name?.trim().toLowerCase();
            if (normName && !seenNames.has(normName)) {
              seenNames.add(normName);
              group.elements.push(el);
            }
          }
        }
      }
    }
    return list;
  }, [events]);

  // [PERMANENT FIX] Do not remove this! Real-time phone location flagging with live screen and target step indicator.
  // events[0] is the newest event (newest-first array from App.tsx). Iterating newest-first prevents locking onto stale root events.
  const latestLiveLocation = useMemo(() => {
    // 1. Check live telemetry events newest-first (events[0] is the latest)
    for (const ev of events) {
      if (ev.screenTitle || ev.packageName) {
        return {
          screenTitle: ev.screenTitle?.trim(),
          packageName: ev.packageName?.trim(),
          currentStep: ev.currentStep?.trim(),
          stepIndex: ev.stepIndex,
          totalSteps: ev.totalSteps,
          timestamp: ev.timestamp
        };
      }
    }

    // 2. Authoritative Fallback from navGraph.active_stacks:
    // When the phone navigates through screens (e.g. Chats -> Menu -> Settings),
    // active_stacks tracks the live back stack. The top element is the physical screen on device!
    if (navGraph && (navGraph as any).active_stacks && selectedAppPkg) {
      const stacks = (navGraph as any).active_stacks;
      const stack = stacks[selectedAppPkg];
      if (Array.isArray(stack) && stack.length > 0) {
        const topScreenId: string = stack[stack.length - 1];
        const screenTitle = topScreenId.includes('::') ? topScreenId.split('::')[1] : topScreenId;
        return {
          screenTitle,
          packageName: selectedAppPkg,
          currentStep: undefined,
          stepIndex: undefined,
          totalSteps: undefined,
          timestamp: Date.now()
        };
      }
    }

    // 3. Fallback from live captured screens
    if (liveScreens.length > 0) {
      return {
        screenTitle: liveScreens[0].screenTitle?.trim(),
        packageName: liveScreens[0].packageName?.trim(),
        currentStep: undefined,
        stepIndex: undefined,
        totalSteps: undefined,
        timestamp: Date.now()
      };
    }

    return null;
  }, [events, navGraph, selectedAppPkg, liveScreens]);

  const [expandedLiveScreens, setExpandedLiveScreens] = useState<Record<string, boolean>>(() => {
    const initial: Record<string, boolean> = {};
    if (liveScreens.length > 0) {
      initial[liveScreens[0].id] = true;
    }
    return initial;
  });

  const toggleLiveScreen = (screenId: string) => {
    setExpandedLiveScreens((prev) => ({
      ...prev,
      [screenId]: !prev[screenId]
    }));
  };

  // [PERMANENT FIX] Do not remove this! Global Expand/Collapse All and Active Phone Screen Unfolding.
  const [isAllExpanded, setIsAllExpanded] = useState<boolean>(false);
  const [isAllLiveExpanded, setIsAllLiveExpanded] = useState<boolean>(true);

  const toggleExpandAll = () => {
    setIsAllExpanded((prev) => {
      const next = !prev;
      setExpandedTransitions({});
      return next;
    });
  };

  const toggleAllLiveExpanded = () => {
    const nextState = !isAllLiveExpanded;
    setIsAllLiveExpanded(nextState);
    const updated: Record<string, boolean> = {};
    liveScreens.forEach((s) => {
      updated[s.id] = nextState;
    });
    setExpandedLiveScreens(updated);
  };

  const toggleTransition = (key: string) => {
    setExpandedTransitions((prev) => {
      const current = isAllExpanded ? (prev[key] !== false) : (prev[key] === true);
      return {
        ...prev,
        [key]: !current
      };
    });
  };

  const handleCopy = (data: any, key: string) => {
    navigator.clipboard.writeText(JSON.stringify(data, null, 2));
    setCopiedItemIdx(key);
    setTimeout(() => setCopiedItemIdx(null), 1500);
  };

  // Nav Graph calculations
  const activeApp: NavGraphApp | undefined = useMemo(() => {
    if (!navGraph || !navGraph.apps) return undefined;
    if (selectedAppPkg) {
      const found = navGraph.apps.find((a) => a.package_name === selectedAppPkg);
      if (found) return found;
    }
    return navGraph.apps[0];
  }, [navGraph, selectedAppPkg]);

  // [PERMANENT FIX] Do not remove this! Extract multi-path candidates suggested by local Dijkstra search from telemetry events
  interface DiscoveredRoute {
    pathString: string;
    steps: string[];
    cost: number;
    startScreen: string;
    destinationScreen: string;
    targetElement?: string;
    isShortest?: boolean;
    score?: number;
    description?: string;
  }

  const latestPathSuggestions = useMemo<{
    selectedPath?: string;
    routes: DiscoveredRoute[];
    timestamp?: number;
  }>(() => {
    for (const ev of events) {
      if (
        ev.type === 'INTENT_PARSED' &&
        ev.payload?.allPaths &&
        Array.isArray(ev.payload.allPaths) &&
        ev.payload.allPaths.length > 0
      ) {
        return {
          selectedPath: ev.payload.pathString || ev.description,
          routes: ev.payload.allPaths,
          timestamp: ev.timestamp
        };
      }
    }
    return { routes: [] };
  }, [events]);

  // Index ALL screens across all apps in navGraph by ID (so cross-package transitions resolve!)
  const screensById = useMemo(() => {
    const map = new Map<string, NavGraphScreen>();
    if (navGraph && navGraph.apps) {
      navGraph.apps.forEach((app) => {
        app.screens.forEach((s) => map.set(s.id, s));
      });
    }
    return map;
  }, [navGraph]);

  // Group apps: primary root apps vs satellite sub-screen packages
  const { primaryApps, satelliteApps, clusterCountMap } = useMemo(() => {
    if (!navGraph || !navGraph.apps) {
      return { primaryApps: [], satelliteApps: [], clusterCountMap: new Map<string, number>() };
    }

    // Map all target screen IDs that are pointed to by cross-package transitions
    const crossPackageTargetScreenIds = new Set<string>();
    const clusterMap = new Map<string, number>();

    for (const app of navGraph.apps) {
      for (const screen of app.screens) {
        for (const t of screen.transitions) {
          if (t.type === 'CLICK' && !t.to_id.startsWith(app.package_name + '::')) {
            crossPackageTargetScreenIds.add(t.to_id);
          }
        }
      }
    }

    const primaries: NavGraphApp[] = [];
    const satellites: NavGraphApp[] = [];

    for (const app of navGraph.apps) {
      const isPureSatellite =
        app.package_name !== 'com.android.settings' &&
        app.screens.length > 0 &&
        app.screens.every((s) => crossPackageTargetScreenIds.has(s.id));

      if (isPureSatellite) {
        satellites.push(app);
      } else {
        primaries.push(app);
      }
    }

    // Calculate connected cluster count for primary apps
    for (const p of primaries) {
      const clusterScreenIds = new Set<string>(p.screens.map((s) => s.id));
      let expanded = true;
      while (expanded) {
        expanded = false;
        for (const sId of Array.from(clusterScreenIds)) {
          const s = screensById.get(sId);
          if (s) {
            for (const t of s.transitions) {
              if (t.type === 'CLICK' && !clusterScreenIds.has(t.to_id) && screensById.has(t.to_id)) {
                clusterScreenIds.add(t.to_id);
                expanded = true;
              }
            }
          }
        }
      }
      clusterMap.set(p.package_name, clusterScreenIds.size);
    }

    return { primaryApps: primaries, satelliteApps: satellites, clusterCountMap: clusterMap };
  }, [navGraph, screensById]);

  // [PERMANENT FIX] Do not remove this! Typographic, Emoji & Ellipsis normalizer for transition matching.
  const normalizeLabel = (text: string): string => {
    if (!text) return '';
    return text
      .replace(/[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]/g, '-')
      .replace(/[\u00A0\u202F\u2007\u200B]/g, ' ')
      .split(/\s*[\u00B7\u2022\u22C5\u2014]\s*/)[0]
      .replace(/\s+(on|off|high|low|medium|connected|disconnected|disabled|enabled)\s*$/i, '')
      .replace(/\s+\d+$/, '')
      .replace(/[…\.]/g, '')
      .replace(/[\uD800-\uDBFF][\uDC00-\uDFFF]/g, '')
      .trim()
      .toLowerCase();
  };

  // [PERMANENT FIX] Do not remove this! App-Aware Root Resolution & Topological In-Degree Root Detection.
  // In WhatsApp (com.whatsapp), the root screen is ALWAYS "Chats" or "WhatsApp" - Settings is a sub-page, NEVER a root screen!
  // Prevents Settings from hijacking the root of the WhatsApp navigation tree and causing all chat conversations to vanish.
  const rootScreen = useMemo(() => {
    if (!activeApp || activeApp.screens.length === 0) return undefined;
    const pkg = (activeApp.package_name || '').toLowerCase();
    const displayName = (activeApp.display_name || '').toLowerCase();

    // 1. App-specific canonical root screens:
    if (pkg.includes('whatsapp') || displayName.includes('whatsapp')) {
      const chats = activeApp.screens.find(
        (s) => s.title.toLowerCase() === 'chats' || s.title.toLowerCase() === 'whatsapp'
      );
      if (chats) return chats;
    }

    if (pkg.includes('settings') || displayName.includes('setting')) {
      const settings = activeApp.screens.find(
        (s) => s.title.toLowerCase() === 'settings' || s.title.toLowerCase() === 'settings home'
      );
      if (settings) return settings;
    }

    // 2. Exact match to app display name
    const exactNameMatch = activeApp.screens.find(
      (s) => s.title.toLowerCase() === displayName
    );
    if (exactNameMatch) return exactNameMatch;

    // 3. Known general entry titles
    const generalEntryTitles = ['home', 'main', 'conversations', 'dialer', 'phone', 'camera'];
    for (const title of generalEntryTitles) {
      const entry = activeApp.screens.find((s) => s.title.toLowerCase() === title);
      if (entry) return entry;
    }

    // 4. True topological root: Screen with ZERO incoming forward CLICK transitions
    const forwardTargets = new Set<string>();
    activeApp.screens.forEach((s) => {
      s.transitions.forEach((t) => {
        if (t.type === 'CLICK' && t.action.toLowerCase() !== 'back') {
          forwardTargets.add(t.to_id);
          forwardTargets.add(t.to_title.toLowerCase());
        }
      });
    });
    const zeroInDegree = activeApp.screens.find(
      (s) => !forwardTargets.has(s.id) && !forwardTargets.has(s.title.toLowerCase())
    );
    if (zeroInDegree) return zeroInDegree;

    // 5. Fallback: Screen with highest visit count
    const sorted = [...activeApp.screens].sort(
      (a, b) => (b.visit_count || 0) - (a.visit_count || 0)
    );
    return sorted[0];
  }, [activeApp]);

  // [PERMANENT FIX] Do not remove this! Auto-Expand Active Phone Screen Path in Nav Graph.
  // When phone moves to a sub-screen (e.g. Settings, Chats Settings, Chat backup),
  // automatically trace the transition path from rootScreen and unfold parent nodes
  // so the active screen and its elements (Chats, Appearance, Notifications) are immediately visible.
  useEffect(() => {
    if (!activeApp || !rootScreen || !latestLiveLocation?.screenTitle) return;
    const targetTitle = normalizeLabel(latestLiveLocation.screenTitle.replace(/,\s*\d+\s+new.*$/i, ''));
    if (!targetTitle) return;

    if (normalizeLabel(rootScreen.title) === targetTitle) return;

    // BFS to find path from rootScreen to targetTitle
    const visited = new Set<string>();
    visited.add(rootScreen.id);
    const parentMap = new Map<string, { parentId: string; transKey: string }>();
    const queue: string[] = [rootScreen.id];

    let foundScreenId: string | null = null;

    while (queue.length > 0) {
      const currId = queue.shift()!;
      const currScreen = screensById.get(currId);
      if (!currScreen) continue;

      if (normalizeLabel(currScreen.title) === targetTitle) {
        foundScreenId = currId;
        break;
      }

      // Check transitions
      currScreen.elements.forEach((elemName, eIdx) => {
        const normElem = normalizeLabel(elemName);
        const trans = currScreen.transitions.find((t) => {
          if (t.type !== 'CLICK') return false;
          const normAction = normalizeLabel(t.action);
          const normToTitle = normalizeLabel(t.to_title);
          return normAction === normElem || normToTitle === normElem ||
                 (normElem.length >= 3 && normAction.includes(normElem)) ||
                 (normAction.length >= 3 && normElem.includes(normAction)) ||
                 (normToTitle && normToTitle.length >= 3 && (normElem.includes(normToTitle) || normToTitle.includes(normElem)));
        });

        let targetId = trans?.to_id;
        if (!targetId) {
          const matchScreen = activeApp.screens.find((s) => {
            if (s.id === currScreen.id) return false;
            const sTitle = normalizeLabel(s.title);
            return sTitle === normElem || (sTitle.length >= 3 && normElem.includes(sTitle));
          });
          if (matchScreen) targetId = matchScreen.id;
        }

        if (targetId && !visited.has(targetId)) {
          visited.add(targetId);
          const transKey = `${currScreen.id}_to_${elemName}_${eIdx}`;
          parentMap.set(targetId, { parentId: currScreen.id, transKey });
          queue.push(targetId);
        }
      });

      currScreen.transitions.forEach((t, idx) => {
        if (t.type === 'CLICK' && t.to_id && !visited.has(t.to_id)) {
          visited.add(t.to_id);
          const transKey = `${currScreen.id}_unmapped_${t.action}_${idx}`;
          parentMap.set(t.to_id, { parentId: currScreen.id, transKey });
          queue.push(t.to_id);
        }
      });
    }

    if (foundScreenId && parentMap.has(foundScreenId)) {
      const keysToExpand: Record<string, boolean> = {};
      let curr: string | undefined = foundScreenId;
      while (curr && parentMap.has(curr)) {
        const step = parentMap.get(curr)!;
        keysToExpand[step.transKey] = true;
        curr = step.parentId;
      }
      setExpandedTransitions((prev) => ({
        ...prev,
        ...keysToExpand
      }));
    }
  }, [activeApp, rootScreen, latestLiveLocation?.screenTitle, screensById]);

  // Recursive NavGraph Screen Renderer (True Multi-Screen Tree!)
  const renderNavGraphScreen = (
    screen: NavGraphScreen,
    depth: number = 1,
    visitedScreens: Set<string> = new Set(),
    renderedScreenIds?: Set<string>
  ) => {
    renderedScreenIds?.add(screen.id);
    const isLoop = visitedScreens.has(screen.id);
    const nextVisited = new Set(visitedScreens);
    nextVisited.add(screen.id);

    const style = getLayerStyle(depth);

    // [PERMANENT FIX] Do not remove this! Dynamic scroll element merging for real-time tree expansion on scroll.
    // Seamlessly merges elements discovered during live phone scrolling into the nav graph tree in real-time.
    const liveGroup = liveScreens.find(
      (ls) => ls.id === screen.id || (ls.packageName === screen.package_name && ls.screenTitle?.toLowerCase() === screen.title.toLowerCase())
    );
    const combinedElements = [...screen.elements];
    if (liveGroup) {
      const existingNorms = new Set(combinedElements.map((e) => e.trim().toLowerCase()));
      for (const el of liveGroup.elements) {
        const name = el.name?.trim();
        if (name && !existingNorms.has(name.toLowerCase())) {
          existingNorms.add(name.toLowerCase());
          combinedElements.push(name);
        }
      }
    }

    // [PERMANENT FIX] Do not remove this! Filter Navigation Return Controls and Header Title Redundancies.
    // 1. Back controls (Back, Navigate up, Arrow back, Close) are stack unwinding controls, NOT forward tree links.
    // 2. Header titles (e.g. "Chats" and "Chat settings" under "Chats Settings") are screen titles, NOT interactive actions.
    const seenElementBases = new Set<string>();
    const NAV_RETURN_LABELS = new Set(['back', 'navigate up', 'up', 'go back', 'arrow_back', 'close', 'cancel']);

    const sanitizedElements = combinedElements.filter((el) => {
      const trimmed = el.trim();
      if (!trimmed) return false;
      const lower = trimmed.toLowerCase();
      if (lower === screen.title.toLowerCase()) return false;
      if (lower === 'search box') return false;
      if (NAV_RETURN_LABELS.has(lower)) return false;
      
      // Header noise on sub-screens (e.g. "Chats" and "Chat settings" under "Chats Settings").
      // Do NOT filter out "Chats" on the root "Settings" screen where it is a valid menu item that opens Chats Settings!
      if (screen.title.toLowerCase() === 'chats settings' && (lower === 'chats' || lower === 'chat settings')) return false;
      if (screen.title.toLowerCase() !== 'settings' && screen.title.toLowerCase().endsWith('settings') && (lower === 'chats' || lower === 'chat settings')) return false;
      if (screen.title.toLowerCase().startsWith(lower) && lower.length <= 15 && screen.title.toLowerCase() !== lower && screen.title.toLowerCase() !== 'settings') return false;

      const normNoise = lower.replace(/_/g, ' ');
      if (normNoise === 'bottom bar' || normNoise === 'action bar' || normNoise === 'scrollable list' || normNoise === 'touch outside' || normNoise === 'scrim') return false;
      const base = normalizeLabel(trimmed);
      if (!base) return false;
      if (seenElementBases.has(base)) return false;
      seenElementBases.add(base);
      return true;
    });

    // Filter elements if search is active
    const q = searchQuery.trim().toLowerCase();
    const visibleElements = q
      ? sanitizedElements.filter((el) => el.toLowerCase().includes(q))
      : sanitizedElements;

    // [PERMANENT FIX] Do not remove this! Canonical Root Screen Alias Matching & Highlight for Dashboard Tree
    const liveTitleNorm = latestLiveLocation?.screenTitle
      ? normalizeLabel(latestLiveLocation.screenTitle.replace(/,\s*\d+\s+new.*$/i, ''))
      : '';
    const screenTitleNorm = normalizeLabel(screen.title.replace(/,\s*\d+\s+new.*$/i, ''));

    const isCurrentLiveScreen = Boolean(
      liveTitleNorm &&
      (!latestLiveLocation?.packageName || screen.id.toLowerCase().startsWith(latestLiveLocation.packageName.toLowerCase()) || activeApp?.package_name.toLowerCase() === latestLiveLocation.packageName.toLowerCase()) &&
      (
        screenTitleNorm === liveTitleNorm ||
        (screenTitleNorm === 'chats' && (liveTitleNorm === 'whatsapp' || liveTitleNorm === 'chats')) ||
        (screenTitleNorm === 'whatsapp' && (liveTitleNorm === 'chats' || liveTitleNorm === 'whatsapp')) ||
        (screenTitleNorm === 'settings' && liveTitleNorm.includes('settings')) ||
        (screenTitleNorm.length >= 3 && liveTitleNorm.includes(screenTitleNorm)) ||
        (liveTitleNorm.length >= 3 && screenTitleNorm.includes(liveTitleNorm))
      )
    );

    return (
      <div key={screen.id} className="space-y-1.5">
        {/* Screen Header - [PERMANENT FIX] High-contrast glowing live location marker box */}
        <div
          className={`flex items-center justify-between px-4 py-2.5 rounded-lg select-none transition-all ${
            isCurrentLiveScreen
              ? 'bg-gradient-to-r from-emerald-950/90 via-[#04281e] to-dark-900 border-2 border-emerald-400 ring-2 ring-emerald-500/40 shadow-[0_0_24px_rgba(52,211,153,0.4)]'
              : style.bg + ' ' + style.borderLeft + ' border-b border-dark-800/50'
          }`}
        >
          <div className="flex items-center space-x-2.5">
            <FolderOpen className={`w-4 h-4 ${isCurrentLiveScreen ? 'text-emerald-300' : 'text-emerald-400'}`} />
            <span className={`text-xs font-bold ${isCurrentLiveScreen ? 'text-emerald-100' : 'text-gray-100'}`}>
              {screen.title}
            </span>
            {isCurrentLiveScreen && (
              <span className="flex items-center space-x-1.5 text-[10px] px-2.5 py-0.5 rounded-full font-bold font-mono bg-emerald-400 text-black border border-emerald-300 shadow-[0_0_12px_rgba(52,211,153,0.7)] animate-pulse">
                <span className="w-1.5 h-1.5 rounded-full bg-black inline-block animate-ping" />
                <span>📍 CURRENT ON THIS PAGE HERE</span>
              </span>
            )}
            <span
              className={`text-[9px] px-2 py-0.5 rounded font-mono font-semibold ${style.badgeBg}`}
            >
              {style.layerName}
            </span>
            <span className="text-[11px] text-gray-400 font-mono">
              ({screen.elements.length} elements, {screen.transitions.length} links)
            </span>
          </div>

          <span className="text-[10px] font-mono text-gray-400">
            Visits: {screen.visit_count || 1}
          </span>
        </div>

        {/* If cycle detected, stop recursion */}
        {isLoop ? (
          <div className={`${style.paddingLeft} py-1 text-xs text-amber-400/80 font-mono`}>
            ↳ (Circular reference back to {screen.title})
          </div>
        ) : (
          /* Elements inside this screen */
          <div className="divide-y divide-dark-800/20 pl-2">
            {visibleElements.length === 0 ? (
              <div className="py-2 text-xs text-gray-500 pl-4">No matching elements</div>
            ) : (
              visibleElements.map((elemName, eIdx) => {
                // [PERMANENT FIX] Do not remove this! Universal screen link matching for contact chats & sub-screens.
                // Matches via forward CLICK transitions, or by matching element name to existing screen titles,
                // or where another screen has a BACK transition returning to this screen (e.g. Rohit Thatikonda (You) -[BACK]-> Chats).
                const normElem = normalizeLabel(elemName);
                const transition = screen.transitions.find((t) => {
                  if (t.type !== 'CLICK') return false;
                  const normAction = normalizeLabel(t.action);
                  const normToTitle = normalizeLabel(t.to_title);

                  if (!normElem || !normAction) return false;
                  if (normAction === normElem || normToTitle === normElem) return true;
                  if (normElem.length >= 3 && normAction.includes(normElem)) return true;
                  if (normAction.length >= 3 && normElem.includes(normAction)) return true;
                  if (normToTitle && normToTitle.length >= 3 && (normElem.includes(normToTitle) || normToTitle.includes(normElem))) return true;
                  return false;
                });

                let targetScreen = transition && screensById.has(transition.to_id)
                  ? screensById.get(transition.to_id)
                  : undefined;

                if (!targetScreen && activeApp) {
                  // [PERMANENT FIX] Do not remove this! Tight Title Matching without Loose Substring Collisions.
                  // Generic words (e.g. "chats") must never match compound options ("Keep chats archived", "Transfer chats").
                  // Matching to rootScreen or visited ancestors is strictly forbidden to eliminate infinite replication loops.
                  targetScreen = activeApp.screens.find((s) => {
                    if (s.id === screen.id) return false;
                    if (rootScreen && s.id === rootScreen.id) return false;
                    if (visitedScreens.has(s.id)) return false;

                    const sTitleNorm = normalizeLabel(s.title);
                    if (!sTitleNorm || !normElem) return false;

                    // Require exact match or exact multi-word contact name match (never match substring of short generic titles)
                    const matchesName = sTitleNorm === normElem ||
                      (sTitleNorm.length >= 5 && normElem.startsWith(sTitleNorm + ' '));
                    
                    const returnsToThis = s.transitions.some(
                      (t) => (t.type === 'BACK' || t.action === 'BACK') &&
                             (t.to_id === screen.id || t.to_title.toLowerCase() === screen.title.toLowerCase())
                    );
                    return matchesName && returnsToThis;
                  });
                }

                // [PERMANENT FIX] Do not remove this! Anti-Circular Navigation & Root Screen Recursion Guard.
                // A forward child screen must NEVER link back to rootScreen or to any ancestor currently in the visited path.
                // When user presses Back, the live location marker shifts up to the parent page; it NEVER adds a child page below!
                if (targetScreen && (rootScreen && targetScreen.id === rootScreen.id || visitedScreens.has(targetScreen.id))) {
                  targetScreen = undefined;
                }

                const hasTargetScreen = Boolean(targetScreen);

                const transKey = `${screen.id}_to_${elemName}_${eIdx}`;
                const isExpanded = isAllExpanded ? expandedTransitions[transKey] !== false : expandedTransitions[transKey] === true;
                const isTargetStep = Boolean(
                  latestLiveLocation?.currentStep &&
                  elemName.trim().toLowerCase() === latestLiveLocation.currentStep.trim().toLowerCase()
                );
                const isLastElem = eIdx === visibleElements.length - 1;

                return (
                  <div key={`${screen.id}_elem_${eIdx}`} className="py-0.5">
                    {/* Element Row - [PERMANENT FIX] High-contrast target step marker box & code nesting */}
                    <div
                      onClick={() => {
                        if (hasTargetScreen) {
                          toggleTransition(transKey);
                        }
                      }}
                      className={`flex items-center justify-between pr-4 py-2 ${style.paddingLeft} rounded-lg transition-all select-none ${
                        isTargetStep
                          ? 'bg-gradient-to-r from-amber-950/90 via-[#2f2008] to-dark-900 border-2 border-amber-400 ring-2 ring-amber-400/50 shadow-[0_0_22px_rgba(251,191,36,0.45)] my-1'
                          : hasTargetScreen
                          ? 'cursor-pointer hover:bg-sky-500/15 border border-transparent'
                          : 'hover:bg-dark-800/50 border border-transparent'
                      }`}
                    >
                      {/* Left: Indicator, Name, Link Badge */}
                      <div className="flex items-center space-x-2.5 min-w-0 mr-3">
                        {hasTargetScreen ? (
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              toggleTransition(transKey);
                            }}
                            className="p-1 rounded text-sky-400 hover:text-sky-200 hover:bg-sky-500/20 transition-colors shrink-0"
                            title={`Click to expand child screen: ${targetScreen?.title}`}
                          >
                            {isExpanded ? (
                              <ChevronDown className="w-3.5 h-3.5" />
                            ) : (
                              <ChevronRight className="w-3.5 h-3.5" />
                            )}
                          </button>
                        ) : (
                          <span className="text-gray-500 font-mono text-[11px] select-none shrink-0 w-4 text-center">
                            {isLastElem ? '└──' : '├──'}
                          </span>
                        )}

                        {/* Name */}
                        <span
                          className={`text-xs truncate ${
                            isTargetStep
                              ? 'text-amber-100 font-extrabold text-sm'
                              : hasTargetScreen
                              ? 'text-sky-300 font-semibold'
                              : 'text-gray-200 font-medium'
                          }`}
                        >
                          {elemName}
                        </span>

                        {/* Live Target Step Badge */}
                        {isTargetStep && (
                          <span className="inline-flex items-center space-x-1.5 px-2.5 py-0.5 rounded-full text-[10px] font-mono font-black bg-amber-400 text-black border border-amber-300 shadow-[0_0_12px_rgba(251,191,36,0.6)] animate-pulse">
                            <span>🎯</span>
                            <span>TARGET STEP</span>
                          </span>
                        )}

                        {/* Transition Link Badge */}
                        {hasTargetScreen && targetScreen && (
                          <span className="inline-flex items-center space-x-1 px-2 py-0.5 rounded-md text-[10px] font-mono bg-sky-500/15 text-sky-300 border border-sky-500/30">
                            <ExternalLink className="w-2.5 h-2.5" />
                            <span>opens {targetScreen.title}</span>
                          </span>
                        )}
                      </div>

                      {/* Right: Badge & Info */}
                      <div className="flex items-center space-x-2 shrink-0">
                        <span
                          className={`text-[10px] px-2 py-0.5 rounded font-medium ${
                            hasTargetScreen
                              ? 'text-sky-400 bg-sky-500/15 border border-sky-500/30'
                              : 'text-emerald-400 bg-emerald-500/15 border border-emerald-500/30'
                          }`}
                        >
                          {hasTargetScreen ? 'Screen Link' : 'Action'}
                        </span>

                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            setSelectedElement({
                              element: {
                                name: elemName,
                                screen: screen.title,
                                leadsTo: targetScreen?.title || null,
                                actionType: transition?.type || 'STATIC'
                              },
                              screenTitle: screen.title
                            });
                          }}
                          className="text-gray-400 hover:text-gray-100 p-1 rounded hover:bg-dark-750 transition-colors"
                        >
                          <Info className="w-3.5 h-3.5" />
                        </button>
                      </div>
                    </div>

                    {/* Child Screen (Expanded recursively under this link!) - [PERMANENT FIX] Code-editor nested block indentation */}
                    {hasTargetScreen && targetScreen && isExpanded && (
                      <div className="mt-2 mb-3 ml-6 relative border-l-2 border-sky-400/60 pl-4 py-2 bg-[#0d121c]/90 rounded-r-xl border-y border-r border-sky-500/20 shadow-xl">
                        {/* Code-style indentation indicator line */}
                        <div className="text-[10px] font-mono text-sky-300/80 mb-2 flex items-center space-x-2 pb-1 border-b border-sky-500/20">
                          <span className="text-sky-400 font-bold">┌─ nested block</span>
                          <span className="text-gray-500">•</span>
                          <span className="text-gray-400">sub-page:</span>
                          <code className="text-sky-200 font-bold bg-sky-950/60 px-1.5 py-0.5 rounded border border-sky-500/30">{targetScreen.title}</code>
                        </div>
                        {renderNavGraphScreen(targetScreen, depth + 1, nextVisited, renderedScreenIds)}
                      </div>
                    )}
                  </div>
                );
              })
            )}

            {/* [PERMANENT FIX] Do not remove this! Direct / Unmapped Screen Transitions.
                If a screen records a forward CLICK transition whose label wasn't in visibleElements,
                display it as an expandable link so deep child screens are never lost. */}
            {(() => {
              const matchedActionNorms = new Set(visibleElements.map((e) => normalizeLabel(e)));
              const unmapped = screen.transitions.filter((t) => {
                if (t.type !== 'CLICK') return false;
                const normA = normalizeLabel(t.action);
                const normT = normalizeLabel(t.to_title);
                return !matchedActionNorms.has(normA) && !matchedActionNorms.has(normT);
              });
              if (unmapped.length === 0) return null;
              return (
                <div className="pt-2 pb-1 pl-4 space-y-1 border-t border-dark-800/40">
                  <div className="text-[10px] font-mono font-semibold text-gray-400 uppercase tracking-wider">
                    Direct Navigation Links ({unmapped.length})
                  </div>
                  {unmapped.map((t, idx) => {
                    let targetScreen = screensById.get(t.to_id);
                    // [PERMANENT FIX] Do not remove this! Direct transition recursion guard.
                    if (targetScreen && ((rootScreen && targetScreen.id === rootScreen.id) || visitedScreens.has(targetScreen.id))) {
                      targetScreen = undefined;
                    }
                    if (!targetScreen) return null;

                    const transKey = `${screen.id}_unmapped_${t.action}_${idx}`;
                    const isExpanded = isAllExpanded ? expandedTransitions[transKey] !== false : expandedTransitions[transKey] === true;
                    return (
                      <div key={transKey} className="py-0.5">
                        <div
                          onClick={() => {
                            if (targetScreen) toggleTransition(transKey);
                          }}
                          className={`flex items-center justify-between pr-4 py-1.5 pl-3 rounded-lg ${
                            targetScreen ? 'cursor-pointer hover:bg-sky-500/10' : ''
                          } border border-dark-800/60 bg-dark-850/40 text-xs transition-colors`}
                        >
                          <div className="flex items-center space-x-2">
                            <span className="text-gray-300 font-medium">{t.action}</span>
                            <span className="inline-flex items-center space-x-1 px-1.5 py-0.5 rounded text-[10px] font-mono bg-sky-500/15 text-sky-300 border border-sky-500/30">
                              <ExternalLink className="w-2.5 h-2.5" />
                              <span>opens {t.to_title}</span>
                            </span>
                          </div>
                          <span className="text-[10px] font-mono text-sky-400">Direct Link</span>
                        </div>
                        {targetScreen && isExpanded && (
                          <div className="mt-2 mb-3 ml-4 relative border-l-2 border-sky-400/60 pl-3 py-2 bg-[#0d121c]/90 rounded-r-xl border-y border-r border-sky-500/20 shadow-xl">
                            {renderNavGraphScreen(targetScreen, depth + 1, nextVisited, renderedScreenIds)}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              );
            })()}
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="flex-1 flex flex-col min-h-0 bg-dark-900 px-6 py-4">
      {/* Top Header Bar */}
      <div className="flex flex-wrap items-center justify-between pb-3 border-b border-dark-800 gap-3">
        <div className="flex items-center space-x-3">
          <div className="p-1.5 rounded-lg bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
            <GitFork className="w-4 h-4" />
          </div>
          <div>
            <h2 className="text-sm font-semibold text-gray-100 flex items-center gap-2">
              Navigation Graph & UI Tree Explorer
            </h2>
            <p className="text-[11px] text-gray-400">
              True verified parent-child screen transitions directly from{' '}
              <code className="text-emerald-400 font-mono">nav_graph.db</code>
            </p>
          </div>
        </div>

        {/* View Mode Toggle: Nav Graph vs Live Capture vs Raw JSON */}
        <div className="flex items-center space-x-2">
          <div className="flex items-center bg-dark-800 rounded-lg p-0.5 border border-dark-700">
            <button
              onClick={() => setViewMode('nav_graph')}
              className={`flex items-center space-x-1.5 px-3 py-1 rounded-md text-xs font-medium transition-colors ${
                viewMode === 'nav_graph'
                  ? 'bg-dark-700 text-emerald-300 shadow-sm border border-emerald-500/30'
                  : 'text-gray-400 hover:text-gray-200'
              }`}
            >
              <GitFork className="w-3.5 h-3.5" />
              <span>Nav Graph (from .db)</span>
            </button>

            <button
              onClick={() => setViewMode('live')}
              className={`flex items-center space-x-1.5 px-3 py-1 rounded-md text-xs font-medium transition-colors ${
                viewMode === 'live'
                  ? 'bg-dark-700 text-gray-100 shadow-sm'
                  : 'text-gray-400 hover:text-gray-200'
              }`}
            >
              <Smartphone className="w-3.5 h-3.5" />
              <span>Live Phone Screen</span>
            </button>

            <button
              onClick={() => setViewMode('json')}
              className={`flex items-center space-x-1.5 px-3 py-1 rounded-md text-xs font-medium transition-colors ${
                viewMode === 'json'
                  ? 'bg-dark-700 text-gray-100 shadow-sm'
                  : 'text-gray-400 hover:text-gray-200'
              }`}
            >
              <Code className="w-3.5 h-3.5" />
              <span>Raw JSON</span>
            </button>
          </div>

          <button
            onClick={() => handleCopy(navGraph || events, 'full_data')}
            className="flex items-center space-x-1.5 px-3 py-1 rounded-lg bg-dark-800 hover:bg-dark-750 text-xs font-medium text-gray-300 border border-dark-700 transition-colors"
          >
            {copiedItemIdx === 'full_data' ? (
              <Check className="w-3.5 h-3.5 text-emerald-400" />
            ) : (
              <Copy className="w-3.5 h-3.5" />
            )}
            <span>{copiedItemIdx === 'full_data' ? 'Copied' : 'Copy JSON'}</span>
          </button>
        </div>
      </div>

      {/* Live Phone Location Status Banner */}
      {latestLiveLocation && (
        <div className="flex flex-wrap items-center justify-between px-4 py-2.5 mb-3 bg-gradient-to-r from-rose-950/40 via-[#151922] to-dark-900 border border-rose-500/30 rounded-lg text-xs font-mono text-gray-200 shadow-md">
          <div className="flex items-center space-x-2.5">
            <span className="relative flex h-2.5 w-2.5">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-rose-400 opacity-75"></span>
              <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-rose-500"></span>
            </span>
            <span className="font-semibold text-rose-300">LIVE PHONE LOCATION:</span>
            <span className="text-gray-100 font-bold bg-rose-500/20 px-2 py-0.5 rounded border border-rose-500/40">
              {latestLiveLocation.screenTitle || 'Active Screen'}
            </span>
            {latestLiveLocation.packageName && (
              <span className="text-gray-400 text-[11px]">({latestLiveLocation.packageName})</span>
            )}
          </div>
          {latestLiveLocation.currentStep && (
            <div className="flex items-center space-x-1.5 text-amber-300 mt-1 sm:mt-0">
              <span className="text-gray-400">Target:</span>
              <span className="font-semibold bg-amber-500/15 px-2 py-0.5 rounded border border-amber-500/30 text-amber-200">
                '{latestLiveLocation.currentStep}'
              </span>
              {latestLiveLocation.totalSteps && (
                <span className="text-gray-400 text-[10px]">
                  [Step {(latestLiveLocation.stepIndex ?? 0) + 1}/{latestLiveLocation.totalSteps}]
                </span>
              )}
            </div>
          )}
        </div>
      )}

      {/* [PERMANENT FIX] Do not remove this! All Local Query Paths Multi-Route Explorer Card */}
      {latestPathSuggestions.routes.length > 0 && (
        <div className="mb-3 p-3.5 rounded-xl bg-gradient-to-r from-dark-850 via-[#131722] to-dark-900 border border-emerald-500/30 shadow-lg space-y-2.5">
          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-2">
              <span className="p-1 rounded bg-emerald-500/20 text-emerald-400 text-xs">🧭</span>
              <h3 className="text-xs font-bold text-gray-100 flex items-center gap-1.5 font-mono">
                LOCAL QUERY PATH SUGGESTIONS
                <span className="text-[10px] px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-300 font-semibold border border-emerald-500/30">
                  {latestPathSuggestions.routes.length} candidate route{latestPathSuggestions.routes.length > 1 ? 's' : ''} evaluated
                </span>
              </h3>
            </div>
            {latestPathSuggestions.timestamp && (
              <span className="text-[10px] font-mono text-gray-400">
                {new Date(latestPathSuggestions.timestamp).toLocaleTimeString()}
              </span>
            )}
          </div>

          <div className="space-y-2">
            {latestPathSuggestions.routes.map((route, rIdx) => {
              const isBest = route.isShortest || rIdx === 0;
              return (
                <div
                  key={rIdx}
                  className={`p-2.5 rounded-lg border transition-all ${
                    isBest
                      ? 'bg-emerald-950/30 border-emerald-500/50 ring-1 ring-emerald-500/20'
                      : 'bg-dark-800/60 border-dark-700/60 hover:bg-dark-800'
                  }`}
                >
                  <div className="flex items-center justify-between mb-1.5">
                    <div className="flex items-center space-x-2">
                      <span
                        className={`text-[9px] px-2 py-0.5 rounded font-mono font-bold ${
                          isBest
                            ? 'bg-emerald-400 text-black shadow-sm'
                            : 'bg-dark-700 text-gray-400 border border-dark-600'
                        }`}
                      >
                        {isBest ? '⭐ BEST ROUTE (ACTIVE)' : `ROUTE #${rIdx + 1}`}
                      </span>
                      <span className="text-[11px] text-gray-300 font-medium">
                        {route.startScreen} → {route.destinationScreen}
                      </span>
                    </div>
                    <div className="flex items-center space-x-2 text-[10px] font-mono text-gray-400">
                      <span>{route.steps.length} step{route.steps.length > 1 ? 's' : ''}</span>
                      <span>•</span>
                      <span>Cost: {route.cost.toFixed(1)}</span>
                    </div>
                  </div>

                  <div className="flex flex-wrap items-center gap-1.5 text-xs font-mono">
                    {route.steps.map((step, sIdx) => (
                      <React.Fragment key={sIdx}>
                        <span
                          className={`px-2 py-0.5 rounded text-[11px] font-medium border ${
                            isBest
                              ? 'bg-emerald-500/15 text-emerald-200 border-emerald-500/30'
                              : 'bg-dark-700/80 text-gray-300 border-dark-600'
                          }`}
                        >
                          {sIdx + 1}. {step}
                        </span>
                        {sIdx < route.steps.length - 1 && (
                          <span className="text-gray-500 text-[10px]">→</span>
                        )}
                      </React.Fragment>
                    ))}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* App Selector & Search Controls (Nav Graph Mode) */}
      {viewMode === 'nav_graph' && navGraph && navGraph.apps && (
        <div className="flex flex-wrap items-center justify-between gap-3 my-3">
          {/* App Selector Dropdown */}
          <div className="flex items-center space-x-2">
            <span className="text-xs text-gray-400 font-medium">Select App:</span>
            <select
              value={selectedAppPkg}
              onChange={(e) => setSelectedAppPkg(e.target.value)}
              className="bg-dark-800 border border-dark-700 text-xs text-gray-200 rounded-lg px-3 py-1.5 focus:outline-none focus:border-emerald-500 font-medium"
            >
              {primaryApps.map((app) => (
                <option key={app.package_name} value={app.package_name}>
                  {app.display_name} ({clusterCountMap.get(app.package_name) || app.screens.length} screens in tree)
                </option>
              ))}
              {satelliteApps.length > 0 && (
                <optgroup label="Sub-Screen Packages">
                  {satelliteApps.map((app) => (
                    <option key={app.package_name} value={app.package_name}>
                      ↳ {app.display_name} ({app.screens.length} screen)
                    </option>
                  ))}
                </optgroup>
              )}
            </select>
            {activeApp && (
              <span className="text-[11px] font-mono text-gray-500">
                {activeApp.package_name}
              </span>
            )}
          </div>

          {/* Search Filter */}
          <div className="relative min-w-[200px] max-w-xs">
            <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-gray-500" />
            <input
              type="text"
              placeholder="Search elements in graph..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full bg-dark-800 border border-dark-700 rounded-lg pl-9 pr-3 py-1 text-xs text-gray-200 placeholder-gray-500 focus:outline-none focus:border-gray-500"
            />
          </div>
        </div>
      )}

      {/* Live Mode Search & Expand All */}
      {viewMode === 'live' && liveScreens.length > 0 && (
        <div className="my-3 flex items-center justify-between gap-3">
          <div className="max-w-xs relative flex-1">
            <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-gray-500" />
            <input
              type="text"
              placeholder="Filter live elements..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full bg-dark-800 border border-dark-700 rounded-lg pl-9 pr-3 py-1 text-xs text-gray-200 placeholder-gray-500 focus:outline-none focus:border-gray-500"
            />
          </div>
          <button
            onClick={toggleAllLiveExpanded}
            className="flex items-center space-x-1.5 px-2.5 py-1 rounded bg-dark-800 hover:bg-dark-700 text-emerald-400 hover:text-emerald-300 border border-dark-700 transition-colors text-[11px] font-medium shrink-0"
          >
            <Layers className="w-3.5 h-3.5" />
            <span>{isAllLiveExpanded ? 'Collapse All Screens' : 'Expand All Screens'}</span>
          </button>
        </div>
      )}

      {/* Main Content Area */}
      <div className="flex-1 overflow-y-auto rounded-lg border border-dark-800 bg-dark-950 p-3">
        {/* VIEW 1: NAV GRAPH (from nav_graph.db) */}
        {viewMode === 'nav_graph' && (
          <div>
            {!navGraph || !activeApp ? (
              <div className="flex flex-col items-center justify-center py-20 text-center text-gray-500 text-xs">
                No nav_graph loaded yet. Open apps on your phone to build the graph.
              </div>
            ) : !rootScreen ? (
              <div className="py-10 text-center text-gray-500 text-xs">
                No screens found for this app.
              </div>
            ) : (
              <div className="space-y-4">
                {/* Information banner */}
                <div className="px-3 py-2 rounded-lg bg-dark-850/80 border border-dark-800 flex items-center justify-between text-xs text-gray-400">
                  <div className="flex items-center space-x-2">
                    <span className="w-2 h-2 rounded-full bg-emerald-400"></span>
                    <span>
                      Navigation Tree rooted at{' '}
                      <strong className="text-gray-200 font-semibold">
                        {rootScreen.title}
                      </strong>
                    </span>
                  </div>
                  <div className="flex items-center space-x-3">
                    <button
                      onClick={toggleExpandAll}
                      className="flex items-center space-x-1.5 px-2.5 py-1 rounded bg-dark-800 hover:bg-dark-700 text-sky-400 hover:text-sky-300 border border-dark-700 transition-colors text-[11px] font-medium"
                      title={isAllExpanded ? "Collapse all tree nodes" : "Expand all tree nodes to view every sub-screen"}
                    >
                      <Layers className="w-3.5 h-3.5" />
                      <span>{isAllExpanded ? 'Collapse All' : 'Expand All'}</span>
                    </button>
                    <span className="text-[11px] font-mono text-gray-500 hidden sm:inline">
                      Click any <span className="text-sky-300">🔗 opens screen</span> link to expand sub-pages
                    </span>
                  </div>
                </div>

                {/* [PERMANENT FIX] Do not remove this! Render Root Screen + Additional Unconnected Screens Guard.
                    Guarantees that every single screen in the app's database is visible in the tree explorer,
                    preventing any screens from ever vanishing even if disconnected from the root tree. */}
                {(() => {
                  // Compute all screens reachable from rootScreen via forward transitions or child screen matching
                  const reachableScreenIds = new Set<string>();
                  reachableScreenIds.add(rootScreen.id);
                  const queue: string[] = [rootScreen.id];

                  while (queue.length > 0) {
                    const currId = queue.shift()!;
                    const currScreen = screensById.get(currId);
                    if (!currScreen) continue;

                    // Direct forward transitions
                    for (const t of currScreen.transitions) {
                      if (t.type === 'CLICK' && !reachableScreenIds.has(t.to_id) && screensById.has(t.to_id)) {
                        reachableScreenIds.add(t.to_id);
                        queue.push(t.to_id);
                      }
                    }

                    // Element to screen title matches
                    for (const el of currScreen.elements) {
                      const normE = normalizeLabel(el);
                      for (const s of activeApp.screens) {
                        if (!reachableScreenIds.has(s.id)) {
                          const normT = normalizeLabel(s.title);
                          if (normT === normE || (normT.length >= 3 && normE.includes(normT)) || (normE.length >= 3 && normT.includes(normE))) {
                            reachableScreenIds.add(s.id);
                            queue.push(s.id);
                          }
                        }
                      }
                    }
                  }

                  const renderedScreenIds = new Set<string>();
                  const rootTree = renderNavGraphScreen(rootScreen, 1, new Set(), renderedScreenIds);
                  const unrenderedScreens = activeApp.screens.filter(
                    (s) => !reachableScreenIds.has(s.id) && s.id !== rootScreen.id
                  );
                  return (
                    <div className="space-y-6">
                      {rootTree}
                      {unrenderedScreens.length > 0 && (
                        <div className="pt-4 border-t border-dark-800/80 space-y-3">
                          <div className="flex items-center space-x-2 text-xs font-semibold text-gray-400">
                            <span className="w-2 h-2 rounded-full bg-sky-400"></span>
                            <span>Additional / Standalone Screens ({unrenderedScreens.length})</span>
                          </div>
                          {unrenderedScreens.map((s) => renderNavGraphScreen(s, 1, new Set(), renderedScreenIds))}
                        </div>
                      )}
                    </div>
                  );
                })()}
              </div>
            )}
          </div>
        )}

        {/* VIEW 2: LIVE PHONE SCREEN (Current Screen Capture) */}
        {viewMode === 'live' && (
          <div>
            {liveScreens.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-20 text-center text-gray-500 text-xs">
                No UI elements captured yet. Open a screen on your phone.
              </div>
            ) : (
              <div className="space-y-3">
                {liveScreens.map((screen, sIdx) => {
                  const isScreenOpen = expandedLiveScreens[screen.id] ?? isAllLiveExpanded;

                  const filtered = searchQuery.trim()
                    ? screen.elements.filter((e) =>
                        e.name.toLowerCase().includes(searchQuery.toLowerCase())
                      )
                    : screen.elements;

                  return (
                    <div
                      key={screen.id}
                      className="rounded-lg border border-dark-800 bg-dark-900 overflow-hidden"
                    >
                      {/* Live Screen Header (Layer 0) */}
                      {(() => {
                        const isThisLive = Boolean(
                          latestLiveLocation?.screenTitle &&
                          (
                            screen.screenTitle.trim().toLowerCase() === latestLiveLocation.screenTitle.trim().toLowerCase() ||
                            (screen.screenTitle.trim().toLowerCase() === 'chats' && (latestLiveLocation.screenTitle.toLowerCase() === 'whatsapp' || latestLiveLocation.screenTitle.toLowerCase() === 'chats')) ||
                            (screen.screenTitle.trim().toLowerCase() === 'whatsapp' && (latestLiveLocation.screenTitle.toLowerCase() === 'chats' || latestLiveLocation.screenTitle.toLowerCase() === 'whatsapp'))
                          )
                        );
                        return (
                          <div
                            onClick={() => toggleLiveScreen(screen.id)}
                            className={`flex items-center justify-between px-4 py-2.5 ${
                              isThisLive
                                ? 'bg-gradient-to-r from-emerald-950/90 via-[#04281e] to-dark-900 border-2 border-emerald-400 ring-2 ring-emerald-500/40 shadow-[0_0_24px_rgba(52,211,153,0.4)]'
                                : 'bg-[#1e2433] hover:bg-[#252c3e]'
                            } cursor-pointer select-none border-b border-dark-800/80 transition-all rounded-t-lg`}
                          >
                            <div className="flex items-center space-x-2.5">
                              <span className="text-gray-400">
                                {isScreenOpen ? (
                                  <ChevronDown className="w-4 h-4 text-gray-200" />
                                ) : (
                                  <ChevronRight className="w-4 h-4" />
                                )}
                              </span>
                              <FolderOpen className={`w-4 h-4 ${isThisLive ? 'text-emerald-300' : 'text-emerald-400'}`} />
                              <span className={`text-sm font-bold ${isThisLive ? 'text-emerald-100' : 'text-gray-100'}`}>
                                {screen.screenTitle}
                              </span>
                              {isThisLive && (
                                <span className="flex items-center space-x-1.5 text-[10px] px-2.5 py-0.5 rounded-full font-bold font-mono bg-emerald-400 text-black border border-emerald-300 shadow-[0_0_12px_rgba(52,211,153,0.7)] animate-pulse">
                                  <span className="w-1.5 h-1.5 rounded-full bg-black inline-block animate-ping" />
                                  <span>📍 CURRENT ON THIS PAGE HERE</span>
                                </span>
                              )}
                              <span className="text-[10px] px-1.5 py-0.5 rounded font-mono font-bold bg-dark-800 text-gray-400">
                                Live Screen
                              </span>
                          <span className="text-xs text-gray-400 font-mono">
                            ({screen.elements.length} elements)
                          </span>
                        </div>

                        <span className="text-[11px] font-mono text-gray-500">
                          {screen.packageName}
                        </span>
                      </div>
                        );
                      })()}

                      {/* Live Elements List (Clean Layer 1 siblings, NO fake nesting!) */}
                      {isScreenOpen && (
                        <div className="divide-y divide-dark-800/30">
                          {filtered.map((el, eIdx) => {
                            const isClickable = el.is_clickable === true;
                            return (
                              <div
                                key={`${screen.id}_${eIdx}`}
                                className="flex items-center justify-between px-6 py-2 bg-[#151922] hover:bg-[#1c222e] transition-colors border-l-[3px] border-emerald-500/60"
                              >
                                <div className="flex items-center space-x-2.5">
                                  <span className="text-gray-600 font-mono text-[11px]">
                                    └─
                                  </span>
                                  <span className="text-xs font-medium text-gray-200 truncate">
                                    {el.name}
                                  </span>
                                </div>

                                <div className="flex items-center space-x-2.5">
                                  <span
                                    className={`text-[10px] px-2 py-0.5 rounded font-medium ${
                                      isClickable
                                        ? 'text-emerald-400 bg-emerald-500/10'
                                        : 'text-gray-400 bg-dark-800'
                                    }`}
                                  >
                                    {isClickable ? 'Clickable' : 'Static'}
                                  </span>

                                  <button
                                    onClick={() =>
                                      setSelectedElement({
                                        element: el,
                                        screenTitle: screen.screenTitle
                                      })
                                    }
                                    className="text-gray-400 hover:text-gray-100 p-1 rounded hover:bg-dark-750 transition-colors"
                                  >
                                    <Info className="w-3.5 h-3.5" />
                                  </button>
                                </div>
                              </div>
                            );
                          })}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        )}

        {/* VIEW 3: RAW JSON */}
        {viewMode === 'json' && (
          <pre className="text-gray-300 font-mono text-xs leading-relaxed overflow-x-auto p-2">
            {JSON.stringify(navGraph || events, null, 2)}
          </pre>
        )}
      </div>

      {/* Info Modal */}
      {selectedElement && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
          <div className="bg-dark-850 border border-dark-700 rounded-xl max-w-md w-full p-5 shadow-2xl space-y-4">
            <div className="flex items-center justify-between pb-2 border-b border-dark-700">
              <div>
                <h3 className="text-sm font-semibold text-gray-100">
                  {selectedElement.element.name}
                </h3>
                <span className="text-xs text-gray-500">
                  {selectedElement.screenTitle}
                </span>
              </div>
              <button
                onClick={() => setSelectedElement(null)}
                className="text-gray-400 hover:text-gray-200 p-1 rounded"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="space-y-3 font-mono text-xs text-gray-300">
              <pre className="p-3 bg-dark-900 rounded-lg overflow-x-auto text-[11px] text-gray-300">
                {JSON.stringify(selectedElement.element, null, 2)}
              </pre>
            </div>

            <div className="flex items-center justify-end space-x-2 pt-2">
              <button
                onClick={() =>
                  handleCopy(selectedElement.element, 'single_element')
                }
                className="flex items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-dark-700 hover:bg-dark-600 text-xs font-medium text-gray-200 transition-colors"
              >
                {copiedItemIdx === 'single_element' ? (
                  <Check className="w-3.5 h-3.5 text-emerald-400" />
                ) : (
                  <Copy className="w-3.5 h-3.5" />
                )}
                <span>
                  {copiedItemIdx === 'single_element' ? 'Copied' : 'Copy JSON'}
                </span>
              </button>
              <button
                onClick={() => setSelectedElement(null)}
                className="px-3 py-1.5 rounded-lg bg-dark-800 hover:bg-dark-750 text-xs text-gray-300 transition-colors"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
