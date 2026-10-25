import React, { useState, useMemo, useEffect } from 'react';
import {
  Folder,
  FileCode,
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
  Layers,
  Download,
  AlertTriangle,
  Tag,
  FileText,
  Trash2,
  Filter
} from 'lucide-react';
import { NavGraph, NavGraphApp, NavGraphScreen, NavGraphTransition } from '../types';

interface TreeExplorerTabProps {
  events: any[];
  navGraph: NavGraph | null;
}

export interface TreeElementItem {
  id: string;
  name: string;
  resource_id: string;
  class_name: string;
  bounds: string;
  is_clickable: boolean;
  is_scrollable: boolean;
  type: string;
}

interface ScreenGroup {
  id: string;
  screenTitle: string;
  packageName: string;
  elements: TreeElementItem[];
  timestamp: number;
}

export type ScreenEvaluationStatus = 'correct' | 'wrong';

export interface ScreenEvaluationRecord {
  id: string; // `${packageName}::${screenTitle}`
  screenTitle: string;
  packageName: string;
  status: ScreenEvaluationStatus;
  notes?: string;
  tags?: string[];
  elementsCount: number;
  sampleElements?: string[];
  timestamp?: number;
  updatedAt: number;
}

// [PERMANENT FIX] Do not remove this! Code-editor nested tree indentation and color palette.
const getLayerStyle = (layer: number) => {
  switch (layer) {
    case 1:
      return {
        bg: 'bg-indigo-50/70',
        borderLeft: 'border-l-4 border-indigo-500',
        badgeBg: 'bg-indigo-100 text-indigo-700',
        paddingLeft: 'pl-4'
      };
    case 2:
      return {
        bg: 'bg-sky-50/70',
        borderLeft: 'border-l-4 border-sky-500',
        badgeBg: 'bg-sky-100 text-sky-700',
        paddingLeft: 'pl-8'
      };
    case 3:
      return {
        bg: 'bg-emerald-50/70',
        borderLeft: 'border-l-4 border-emerald-500',
        badgeBg: 'bg-emerald-100 text-emerald-700',
        paddingLeft: 'pl-12'
      };
    case 4:
      return {
        bg: 'bg-amber-50/70',
        borderLeft: 'border-l-4 border-amber-500',
        badgeBg: 'bg-amber-100 text-amber-700',
        paddingLeft: 'pl-16'
      };
    default:
      return {
        bg: 'bg-purple-50/70',
        borderLeft: 'border-l-4 border-purple-500',
        badgeBg: 'bg-purple-100 text-purple-700',
        paddingLeft: 'pl-20'
      };
  }
};

export const TreeExplorerTab: React.FC<TreeExplorerTabProps> = ({ events, navGraph }) => {
  const [viewMode, setViewMode] = useState<'nav_graph' | 'live' | 'json'>(() => {
    return navGraph && navGraph.apps && navGraph.apps.length > 0 ? 'nav_graph' : 'nav_graph';
  });

  const [copiedItemIdx, setCopiedItemIdx] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');

  // ── Screen Evaluation & Quality Labeling State ─────────────────────────────
  const [evaluations, setEvaluations] = useState<Record<string, ScreenEvaluationRecord>>(() => {
    try {
      const saved = localStorage.getItem('guidance_screen_evaluations');
      return saved ? JSON.parse(saved) : {};
    } catch (_) {
      return {};
    }
  });

  // Save evaluations persistently in browser storage
  useEffect(() => {
    try {
      localStorage.setItem('guidance_screen_evaluations', JSON.stringify(evaluations));
    } catch (_) {}
  }, [evaluations]);

  // Filter in Live Screen Mode: 'all' | 'correct' | 'wrong' | 'unreviewed'
  const [evalFilter, setEvalFilter] = useState<'all' | 'correct' | 'wrong' | 'unreviewed'>('all');

  // Study & Analysis Modal state for wrong screens
  const [isStudyModalOpen, setIsStudyModalOpen] = useState(false);
  const [selectedWrongScreenId, setSelectedWrongScreenId] = useState<string | null>(null);

  // Selected App in Nav Graph Mode
  const [selectedAppPkg, setSelectedAppPkg] = useState<string>(() => {
    if (navGraph && navGraph.apps && navGraph.apps.length > 0) {
      const settings = navGraph.apps.find((a) => a.package_name.includes('settings'));
      if (settings) return settings.package_name;
      const multi = navGraph.apps.find((a) => a.screens.length > 1);
      return multi ? multi.package_name : navGraph.apps[0].package_name;
    }
    return '';
  });

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

  // Nav Graph transition expansion state
  const [expandedTransitions, setExpandedTransitions] = useState<Record<string, boolean>>({});

  // Element info modal
  const [selectedElement, setSelectedElement] = useState<{
    element: TreeElementItem;
    screenTitle: string;
    packageName: string;
  } | null>(null);

  // [PERMANENT FIX] Do not remove this! Cumulative Live UI Element Capture & Scroll Merging.
  // Instead of dropping subsequent telemetry events for an existing screen, merge newly scrolled elements
  // into the screen group. Ensures sub-screens like WhatsApp Settings show all elements (Chats, Appearance, etc.)
  const liveScreens: ScreenGroup[] = useMemo(() => {
    const screensMap = new Map<string, ScreenGroup>();

    events.forEach((ev) => {
      if (ev.tag === 'CLEAN_PAGE' && ev.payload) {
        const p = ev.payload;
        const screenTitle = p.screen_title || p.title || 'Unknown Screen';
        const packageName = p.package_name || 'unknown.app';
        const dedupeKey = `${packageName}::${screenTitle}`;

        const incomingElements: TreeElementItem[] = (p.elements || []).map((el: any, idx: number) => ({
          id: el.id || el.resource_id || `el_${idx}`,
          name: el.name || el.text || el.content_desc || '(unnamed)',
          resource_id: el.resource_id || '',
          class_name: el.class_name || '',
          bounds: el.bounds || '',
          is_clickable: Boolean(el.is_clickable),
          is_scrollable: Boolean(el.is_scrollable),
          type: el.type || 'element'
        }));

        let group = screensMap.get(dedupeKey);
        if (!group) {
          group = {
            id: dedupeKey,
            screenTitle,
            packageName,
            elements: [],
            timestamp: ev.timestamp || Date.now()
          };
          screensMap.set(dedupeKey, group);
        }

        const existingNames = new Set(group.elements.map((e) => e.name.trim().toLowerCase()));
        for (const inc of incomingElements) {
          const norm = inc.name.trim().toLowerCase();
          if (norm && !existingNames.has(norm)) {
            existingNames.add(norm);
            group.elements.push(inc);
          }
        }
      }
    });

    return Array.from(screensMap.values());
  }, [events]);

  // [PERMANENT FIX] Do not remove this! Real-time phone location flagging with live screen and target step indicator.
  const latestLiveLocation = useMemo(() => {
    let screenTitle = '';
    let packageName = '';
    let currentStep = '';

    for (let i = events.length - 1; i >= 0; i--) {
      const ev = events[i];
      if (!screenTitle && (ev.tag === 'SCREEN_CHANGED' || ev.tag === 'CLEAN_PAGE') && ev.payload) {
        screenTitle = ev.payload.screen_title || ev.payload.title || ev.payload.new_screen || '';
        packageName = ev.payload.package_name || ev.payload.package || '';
      }
      if (!currentStep && ev.tag === 'STEP_ADVANCED' && ev.payload) {
        currentStep = ev.payload.next_step || ev.payload.step || '';
      }
      if (screenTitle && currentStep) break;
    }
    return { screenTitle, packageName, currentStep };
  }, [events]);

  const [expandedLiveScreens, setExpandedLiveScreens] = useState<Record<string, boolean>>({
    0: true
  });

  // [PERMANENT FIX] Do not remove this! Global Expand/Collapse All and Active Phone Screen Unfolding.
  const [isAllExpanded, setIsAllExpanded] = useState<boolean>(false);
  const [isAllLiveExpanded, setIsAllLiveExpanded] = useState<boolean>(true);

  const toggleLiveScreen = (screenId: string) => {
    setExpandedLiveScreens((prev) => ({
      ...prev,
      [screenId]: !prev[screenId]
    }));
  };

  const toggleTransition = (key: string) => {
    setExpandedTransitions((prev) => ({
      ...prev,
      [key]: !prev[key]
    }));
  };

  const handleCopy = (data: any, key: string) => {
    navigator.clipboard.writeText(typeof data === 'string' ? data : JSON.stringify(data, null, 2));
    setCopiedItemIdx(key);
    setTimeout(() => setCopiedItemIdx(null), 2000);
  };

  // ── Evaluation Helpers ────────────────────────────────────────────────────

  const toggleEvaluation = (
    screen: { id: string; screenTitle: string; packageName: string; elements: TreeElementItem[]; timestamp?: number },
    targetStatus: ScreenEvaluationStatus
  ) => {
    setEvaluations((prev) => {
      const existing = prev[screen.id];
      if (existing && existing.status === targetStatus) {
        const updated = { ...prev };
        delete updated[screen.id];
        return updated;
      }
      return {
        ...prev,
        [screen.id]: {
          id: screen.id,
          screenTitle: screen.screenTitle,
          packageName: screen.packageName,
          status: targetStatus,
          notes: existing?.notes || '',
          tags: existing?.tags || [],
          elementsCount: screen.elements.length,
          sampleElements: screen.elements.slice(0, 10).map((e) => e.name),
          timestamp: screen.timestamp || Date.now(),
          updatedAt: Date.now()
        }
      };
    });
  };

  const toggleNavGraphEvaluation = (screen: NavGraphScreen, targetStatus: ScreenEvaluationStatus) => {
    setEvaluations((prev) => {
      const existing = prev[screen.id];
      if (existing && existing.status === targetStatus) {
        const updated = { ...prev };
        delete updated[screen.id];
        return updated;
      }
      const pkg = screen.id.includes('::') ? screen.id.split('::')[0] : (activeApp?.package_name || 'unknown');
      return {
        ...prev,
        [screen.id]: {
          id: screen.id,
          screenTitle: screen.title,
          packageName: pkg,
          status: targetStatus,
          notes: existing?.notes || '',
          tags: existing?.tags || [],
          elementsCount: screen.elements.length,
          sampleElements: screen.elements.slice(0, 10),
          timestamp: screen.last_seen || Date.now(),
          updatedAt: Date.now()
        }
      };
    });
  };

  const updateWrongScreenNotes = (id: string, notes: string) => {
    setEvaluations((prev) => {
      const rec = prev[id];
      if (!rec) return prev;
      return {
        ...prev,
        [id]: {
          ...rec,
          notes,
          updatedAt: Date.now()
        }
      };
    });
  };

  const toggleWrongScreenTag = (id: string, tag: string) => {
    setEvaluations((prev) => {
      const rec = prev[id];
      if (!rec) return prev;
      const currentTags = rec.tags || [];
      const newTags = currentTags.includes(tag)
        ? currentTags.filter((t) => t !== tag)
        : [...currentTags, tag];
      return {
        ...prev,
        [id]: {
          ...rec,
          tags: newTags,
          updatedAt: Date.now()
        }
      };
    });
  };

  const exportEvaluationData = (type: 'correct' | 'wrong' | 'all') => {
    const list = Object.values(evaluations).filter((rec) => {
      if (type === 'all') return true;
      return rec.status === type;
    });

    const exportPayload = {
      title: `GuidanceApp Screen Evaluations (${type.toUpperCase()})`,
      exportedAt: new Date().toISOString(),
      category: type,
      totalCount: list.length,
      screens: list.map((rec) => {
        const liveMatch = liveScreens.find((s) => s.id === rec.id);
        return {
          ...rec,
          fullElements: liveMatch ? liveMatch.elements : undefined
        };
      })
    };

    const blob = new Blob([JSON.stringify(exportPayload, null, 2)], {
      type: 'application/json'
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `guidance_${type}_screens_${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const clearAllEvaluations = () => {
    if (window.confirm('Are you sure you want to clear all marked Correct and Wrong screen evaluations?')) {
      setEvaluations({});
    }
  };

  // [PERMANENT FIX] Do not remove this! Extract multi-path candidates suggested by local Dijkstra search from telemetry events
  interface DiscoveredRoute {
    pathString: string;
    steps: string[];
    cost: number;
    destinationScreen?: string;
    exactTask?: string;
    timestamp: number;
  }

  const discoveredRoutes = useMemo(() => {
    const routes: DiscoveredRoute[] = [];
    const seen = new Set<string>();

    for (let i = events.length - 1; i >= 0; i--) {
      const ev = events[i];
      if (ev.tag === 'DIJKSTRA_CANDIDATE' || ev.tag === 'SEARCH_PATH' || ev.tag === 'PATH_FOUND') {
        const p = ev.payload || {};
        const pathStr = p.path_string || (Array.isArray(p.path) ? p.path.join(' -> ') : '');
        if (pathStr && !seen.has(pathStr)) {
          seen.add(pathStr);
          routes.push({
            pathString: pathStr,
            steps: Array.isArray(p.path) ? p.path : pathStr.split(' -> ').map((s: string) => s.trim()),
            cost: p.cost !== undefined ? p.cost : 0,
            destinationScreen: p.destination_screen || p.target_screen || '',
            exactTask: p.exact_task || p.task || '',
            timestamp: ev.timestamp || Date.now()
          });
        }
      }
    }
    return routes;
  }, [events]);

  // [PERMANENT FIX] Do not remove this! Typographic, Emoji & Ellipsis normalizer for transition matching.
  const normalizeLabel = (text: string): string => {
    if (!text) return '';
    return text
      .toLowerCase()
      .replace(/[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]/g, '-')
      .replace(/[\u00A0\u202F\u2007]/g, ' ')
      .replace(/[…\.]/g, '')
      .replace(/[\uD800-\uDBFF][\uDC00-\uDFFF]/g, '')
      .replace(/[\u2600-\u27BF]/g, '')
      .replace(/\s+/g, ' ')
      .trim();
  };

  // Nav Graph calculations
  const activeApp: NavGraphApp | undefined = useMemo(() => {
    if (!navGraph || !navGraph.apps) return undefined;
    return navGraph.apps.find((a) => a.package_name === selectedAppPkg);
  }, [navGraph, selectedAppPkg]);

  // Index ALL screens across all apps in navGraph by ID (so cross-package transitions resolve!)
  const screensById = useMemo(() => {
    const map = new Map<string, NavGraphScreen>();
    if (!navGraph || !navGraph.apps) return map;
    for (const app of navGraph.apps) {
      for (const screen of app.screens) {
        map.set(screen.id, screen);
      }
    }
    return map;
  }, [navGraph]);

  // Group apps: primary root apps vs satellite sub-screen packages
  const { primaryApps, satelliteApps, clusterCountMap } = useMemo(() => {
    if (!navGraph || !navGraph.apps) {
      return { primaryApps: [], satelliteApps: [], clusterCountMap: new Map<string, number>() };
    }

    const clusters = new Map<string, number>();
    for (const app of navGraph.apps) {
      clusters.set(app.package_name, app.screens.length);
    }

    const primary: NavGraphApp[] = [];
    const satellite: NavGraphApp[] = [];

    for (const app of navGraph.apps) {
      const isSatellite =
        app.package_name.startsWith('com.oplus.') ||
        app.package_name.startsWith('com.coloros.') ||
        app.package_name.startsWith('com.android.settings.') ||
        (app.screens.length <= 2 && app.package_name.includes('settings'));

      if (isSatellite && app.screens.length <= 5) {
        satellite.push(app);
      } else {
        primary.push(app);
      }
    }

    return { primaryApps: primary, satelliteApps: satellite, clusterCountMap: clusters };
  }, [navGraph]);

  // [PERMANENT FIX] Do not remove this! App-Aware Root Resolution & Topological In-Degree Root Detection.
  const rootScreen = useMemo(() => {
    if (!activeApp || activeApp.screens.length === 0) return undefined;

    const inDegree = new Map<string, number>();
    activeApp.screens.forEach((s) => inDegree.set(s.id, 0));

    activeApp.transitions.forEach((t) => {
      if (t.type === 'CLICK' && inDegree.has(t.to_id)) {
        inDegree.set(t.to_id, (inDegree.get(t.to_id) || 0) + 1);
      }
    });

    const isWhatsApp = activeApp.package_name.includes('whatsapp');
    const isSettings = activeApp.package_name.includes('settings');

    let zeroInDegree = activeApp.screens.filter((s) => (inDegree.get(s.id) || 0) === 0);

    if (isWhatsApp) {
      const waHome = activeApp.screens.find((s) => {
        const titleNorm = normalizeLabel(s.title);
        return titleNorm === 'whatsapp' || titleNorm === 'chats';
      });
      if (waHome) return waHome;
    }

    if (isSettings) {
      const settingsHome = activeApp.screens.find((s) => {
        const titleNorm = normalizeLabel(s.title);
        return titleNorm === 'settings' || titleNorm === 'oplus settings';
      });
      if (settingsHome) return settingsHome;
    }

    if (zeroInDegree.length > 0) {
      zeroInDegree.sort((a, b) => b.elements.length - a.elements.length);
      return zeroInDegree[0];
    }

    return activeApp.screens[0];
  }, [activeApp]);

  // [PERMANENT FIX] Do not remove this! Auto-Expand Active Phone Screen Path in Nav Graph.
  useEffect(() => {
    if (!latestLiveLocation.screenTitle || !activeApp) return;

    const targetTitleNorm = normalizeLabel(latestLiveLocation.screenTitle);
    const targetScreen = activeApp.screens.find(
      (s) => normalizeLabel(s.title) === targetTitleNorm
    );
    if (!targetScreen) return;

    const toExpand: Record<string, boolean> = {};

    activeApp.screens.forEach((parent) => {
      parent.elements.forEach((elem, eIdx) => {
        const transKey = `${parent.id}_to_${elem}_${eIdx}`;
        const hasTransition = activeApp.transitions.some(
          (t) => t.from_id === parent.id && t.to_id === targetScreen.id
        );
        if (hasTransition || normalizeLabel(elem) === targetTitleNorm) {
          toExpand[transKey] = true;
        }
      });
    });

    if (Object.keys(toExpand).length > 0) {
      setExpandedTransitions((prev) => ({ ...prev, ...toExpand }));
    }
  }, [latestLiveLocation.screenTitle, activeApp]);

  // Filtered live screens based on evaluation status & search query
  const displayedLiveScreens = useMemo(() => {
    return liveScreens.filter((screen) => {
      // Evaluation filter
      if (evalFilter === 'correct') {
        if (evaluations[screen.id]?.status !== 'correct') return false;
      } else if (evalFilter === 'wrong') {
        if (evaluations[screen.id]?.status !== 'wrong') return false;
      } else if (evalFilter === 'unreviewed') {
        if (evaluations[screen.id]) return false;
      }

      // Search query filter
      if (searchQuery.trim()) {
        const q = searchQuery.toLowerCase();
        const matchesTitle = screen.screenTitle.toLowerCase().includes(q);
        const matchesPkg = screen.packageName.toLowerCase().includes(q);
        const matchesElement = screen.elements.some((e) => e.name.toLowerCase().includes(q));
        const matchesNotes = evaluations[screen.id]?.notes?.toLowerCase().includes(q);
        if (!matchesTitle && !matchesPkg && !matchesElement && !matchesNotes) return false;
      }

      return true;
    });
  }, [liveScreens, evalFilter, searchQuery, evaluations]);

  const correctCount = useMemo(() => {
    return liveScreens.filter((s) => evaluations[s.id]?.status === 'correct').length;
  }, [liveScreens, evaluations]);

  const wrongCount = useMemo(() => {
    return liveScreens.filter((s) => evaluations[s.id]?.status === 'wrong').length;
  }, [liveScreens, evaluations]);

  const unreviewedCount = useMemo(() => {
    return liveScreens.length - correctCount - wrongCount;
  }, [liveScreens.length, correctCount, wrongCount]);

  const wrongScreensList = useMemo(() => {
    return liveScreens.filter((s) => evaluations[s.id]?.status === 'wrong');
  }, [liveScreens, evaluations]);

  // Recursive NavGraph Screen Renderer (True Multi-Screen Tree!)
  const renderNavGraphScreen = (
    screen: NavGraphScreen,
    depth: number = 1,
    visitedScreens: Set<string> = new Set()
  ) => {
    const isLoop = visitedScreens.has(screen.id);
    const nextVisited = new Set(visitedScreens);
    nextVisited.add(screen.id);

    const style = getLayerStyle(depth);
    const evalRecord = evaluations[screen.id];

    // [PERMANENT FIX] Do not remove this! Dynamic scroll element merging for real-time tree expansion on scroll.
    // Seamlessly merges elements discovered during live phone scrolling into the nav graph tree in memory.
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
    // 1. Back controls (Back, Navigate up, Arrow back, Close) are stack unwinding controls, NOT forward transitions.
    // 2. Header titles (e.g. "Chats" and "Chat settings" under "Chats Settings") are screen titles, NOT nested links.
    const seenElementBases = new Set<string>();
    const NAV_RETURN_LABELS = new Set(['back', 'navigate up', 'up', 'go back', 'arrow_back', 'close', 'done']);

    const sanitizedElements = combinedElements.filter((el) => {
      const trimmed = el.trim();
      if (!trimmed) return false;
      const lower = trimmed.toLowerCase();
      if (lower === screen.title.toLowerCase()) return false;
      if (lower === 'search box') return false;
      if (NAV_RETURN_LABELS.has(lower)) return false;

      // Header noise on sub-screens (e.g. "Chats" and "Chat settings" under "Chats Settings").
      // Do NOT filter out "Chats" on the root "Settings" screen where it is a valid menu item that leads to "Chats Settings".
      if (screen.title.toLowerCase() === 'chats settings' && (lower === 'chats' || lower === 'chat settings')) return false;
      if (screen.title.toLowerCase() !== 'settings' && screen.title.toLowerCase().endsWith('settings') && lower.endsWith('settings')) return false;
      if (screen.title.toLowerCase().startsWith(lower) && lower.length <= 15 && screen.title.toLowerCase() !== 'settings') return false;

      const normNoise = lower.replace(/_/g, ' ');
      if (normNoise === 'bottom bar' || normNoise === 'action bar' || normNoise === 'scrollable list') return false;
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
      (!latestLiveLocation?.packageName || screen.id.toLowerCase().startsWith(latestLiveLocation.packageName.toLowerCase())) &&
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
          className={`flex items-center justify-between px-4 py-2.5 rounded-2xl select-none transition-all duration-200 border ${
            isCurrentLiveScreen
              ? 'bg-gradient-to-r from-emerald-50 via-emerald-100/50 to-teal-50 border-2 border-emerald-500 shadow-md ring-2 ring-emerald-400/20'
              : `${style.bg} ${style.borderLeft} border-slate-200/80 shadow-xs`
          } ${
            evalRecord?.status === 'correct'
              ? 'ring-2 ring-emerald-300/40 border-emerald-300'
              : evalRecord?.status === 'wrong'
              ? 'ring-2 ring-rose-300/40 border-rose-300'
              : ''
          }`}
        >
          <div className="flex items-center space-x-2.5">
            <FolderOpen className={`w-4 h-4 ${isCurrentLiveScreen ? 'text-emerald-600 animate-pulse' : 'text-indigo-600'}`} />
            <span className={`text-xs font-bold ${isCurrentLiveScreen ? 'text-emerald-950 font-black' : 'text-slate-900'}`}>
              {screen.title}
            </span>
            {isCurrentLiveScreen && (
              <span className="text-[10px] px-2 py-0.5 rounded-md font-mono font-bold bg-emerald-600 text-white animate-pulse shadow-sm flex items-center space-x-1">
                <span>📍 ACTIVE PHONE SCREEN</span>
              </span>
            )}
            <span
              className={`text-[9px] px-2 py-0.5 rounded-md font-mono font-bold ${style.badgeBg}`}
            >
              Layer {depth}
            </span>
            {evalRecord?.status === 'correct' && (
              <span className="text-[10px] px-2 py-0.5 rounded-md font-mono font-bold bg-emerald-50 text-emerald-700 border border-emerald-200 flex items-center space-x-1">
                <Check className="w-3 h-3 text-emerald-600" />
                <span>Correct</span>
              </span>
            )}
            {evalRecord?.status === 'wrong' && (
              <span className="text-[10px] px-2 py-0.5 rounded-md font-mono font-bold bg-rose-50 text-rose-700 border border-rose-200 flex items-center space-x-1">
                <X className="w-3 h-3 text-rose-600" />
                <span>Wrong</span>
              </span>
            )}
            <span className="text-[11px] text-slate-400 font-mono">
              ({screen.elements.length} elements, {screen.transitions.length} links)
            </span>
          </div>

          <div className="flex items-center space-x-2.5" onClick={(e) => e.stopPropagation()}>
            {/* Correct & Wrong buttons for Nav Graph screens */}
            <div className="flex items-center space-x-1">
              <button
                onClick={() => toggleNavGraphEvaluation(screen, 'correct')}
                className={`flex items-center space-x-1 px-2.5 py-1 rounded-xl text-xs font-semibold transition-all active:scale-[0.98] ${
                  evalRecord?.status === 'correct'
                    ? 'bg-emerald-500 text-white border border-emerald-600 shadow-sm'
                    : 'bg-slate-50 hover:bg-emerald-50 text-slate-600 hover:text-emerald-700 border border-slate-200/80'
                }`}
                title="Mark this screen as Correct"
              >
                <Check className="w-3 h-3" />
                <span>Correct</span>
              </button>

              <button
                onClick={() => toggleNavGraphEvaluation(screen, 'wrong')}
                className={`flex items-center space-x-1 px-2.5 py-1 rounded-xl text-xs font-semibold transition-all active:scale-[0.98] ${
                  evalRecord?.status === 'wrong'
                    ? 'bg-rose-500 text-white border border-rose-600 shadow-sm'
                    : 'bg-slate-50 hover:bg-rose-50 text-slate-600 hover:text-rose-700 border border-slate-200/80'
                }`}
                title="Mark this screen as Wrong for study"
              >
                <X className="w-3 h-3" />
                <span>Wrong</span>
              </button>
            </div>

            <span className="text-[10px] font-mono text-slate-400">
              Visits: {screen.visit_count || 1}
            </span>
          </div>
        </div>

        {/* If cycle detected, stop recursion */}
        {isLoop ? (
          <div className={`${style.paddingLeft} py-1 text-xs text-amber-600 font-mono`}>
            ↳ (Circular reference back to {screen.title})
          </div>
        ) : (
          /* Elements inside this screen */
          <div className="divide-y divide-slate-100 pl-2">
            {visibleElements.length === 0 ? (
              <div className="py-2 text-xs text-slate-400 pl-4">No matching elements</div>
            ) : (
              visibleElements.map((elemName, eIdx) => {
                // [PERMANENT FIX] Do not remove this! Universal screen link matching for contact chats & sub-screens.
                // Matches via forward CLICK transitions, or by matching element name to existing screen,
                // or where another screen has a BACK transition returning to this screen (e.g. Rohit -> WhatsApp).
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
                  // Generic words (e.g. "chats") must never match compound options ("Keep chats archived").
                  // Matching to rootScreen or visited ancestors is strictly forbidden to eliminate circular loops.
                  targetScreen = activeApp.screens.find((s) => {
                    if (s.id === screen.id) return false;
                    if (rootScreen && s.id === rootScreen.id) return false;
                    if (visitedScreens.has(s.id)) return false;

                    const sTitleNorm = normalizeLabel(s.title);
                    if (!sTitleNorm || !normElem) return false;

                    // Require exact match or exact multi-word contact name match (never match substrings like 'chats' in 'Keep chats archived')
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
                // A forward child screen must NEVER link back to rootScreen or to any ancestor currently in visitedScreens.
                // When user presses Back, the live location marker shifts up to the parent page; it does NOT append child loops!
                if (targetScreen && (rootScreen && targetScreen.id === rootScreen.id || visitedScreens.has(targetScreen.id))) {
                  targetScreen = undefined;
                }

                const hasTargetScreen = Boolean(targetScreen);

                const transKey = `${screen.id}_to_${elemName}_${eIdx}`;
                const isExpanded = isAllExpanded ? expandedTransitions[transKey] !== false : expandedTransitions[transKey];
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
                      className={`flex items-center justify-between pr-4 py-2 ${style.paddingLeft} rounded-xl select-none transition-all ${
                        isTargetStep
                          ? 'bg-gradient-to-r from-amber-50 via-amber-100/50 to-orange-50 border-2 border-amber-500 shadow-md ring-2 ring-amber-400/20'
                          : hasTargetScreen
                          ? 'cursor-pointer hover:bg-indigo-50/60 border border-transparent'
                          : 'hover:bg-slate-50 border border-transparent'
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
                            className="p-1 rounded-md hover:bg-slate-200/60 text-slate-400 hover:text-slate-600 transition-colors"
                          >
                            {isExpanded ? (
                              <ChevronDown className="w-3.5 h-3.5 text-indigo-600" />
                            ) : (
                              <ChevronRight className="w-3.5 h-3.5 text-slate-400" />
                            )}
                          </button>
                        ) : (
                          <span className="text-slate-300 font-mono text-[11px] select-none shrink-0 w-5 text-center">
                            {isLastElem ? '└' : '├'}
                          </span>
                        )}

                        <span
                          className={`text-xs truncate ${
                            isTargetStep
                              ? 'text-amber-950 font-black'
                              : hasTargetScreen
                              ? 'text-indigo-600 font-bold hover:underline'
                              : 'text-slate-700 font-medium'
                          }`}
                        >
                          {elemName}
                        </span>

                        {isTargetStep && (
                          <span className="text-[10px] px-2 py-0.5 rounded-md font-mono font-bold bg-amber-500 text-white animate-pulse shadow-sm flex items-center space-x-1 shrink-0">
                            <span>🎯 NEXT TARGET STEP</span>
                          </span>
                        )}

                        {hasTargetScreen && (
                          <span className="text-[10px] px-2 py-0.5 rounded-md bg-indigo-50 text-indigo-700 border border-indigo-200/80 font-mono font-semibold shrink-0 flex items-center space-x-1">
                            <span>→ {targetScreen?.title}</span>
                          </span>
                        )}
                      </div>

                      {/* Right: Action or Type */}
                      <div className="flex items-center space-x-2 shrink-0">
                        {hasTargetScreen ? (
                          <span className="text-[10px] font-mono text-indigo-600 font-bold bg-indigo-50 px-2 py-0.5 rounded-md border border-indigo-200">
                            SCREEN LINK
                          </span>
                        ) : (
                          <span className="text-[10px] font-mono text-slate-400">
                            action
                          </span>
                        )}
                      </div>
                    </div>

                    {/* Child Screen (Expanded recursively under this link!) - [PERMANENT FIX] Code-editor nested block indentation */}
                    {hasTargetScreen && isExpanded && targetScreen && (
                      <div className="ml-6 pl-4 border-l-2 border-indigo-200/80 my-1.5 transition-all">
                        {renderNavGraphScreen(targetScreen, depth + 1, nextVisited)}
                      </div>
                    )}
                  </div>
                );
              })
            )}

            {/* [PERMANENT FIX] Do not remove this! Direct / Unmapped Screen Transitions.
                If this screen has outgoing transitions that were not matched to individual elements above,
                render them here so the user can still unfold the navigation tree without missing screens! */}
            {(() => {
              const directTransitions = screen.transitions.filter((t) => {
                if (t.type !== 'CLICK') return false;
                const normAction = normalizeLabel(t.action);
                const normToTitle = normalizeLabel(t.to_title);
                return !visibleElements.some((elem) => {
                  const normElem = normalizeLabel(elem);
                  return (
                    normAction === normElem ||
                    normToTitle === normElem ||
                    (normElem.length >= 3 && normAction.includes(normElem)) ||
                    (normAction.length >= 3 && normElem.includes(normAction))
                  );
                });
              });

              if (directTransitions.length === 0) return null;

              return (
                <div className="mt-2 pt-2 border-t border-slate-200/60 pl-4 space-y-1">
                  <div className="text-[10px] font-mono font-bold text-slate-400 uppercase tracking-wider">
                    Additional Direct Transitions ({directTransitions.length})
                  </div>
                  {directTransitions.map((dt, dtIdx) => {
                    const directTarget = screensById.get(dt.to_id);
                    // [PERMANENT FIX] Do not remove this! Direct transition recursion guard.
                    if (!directTarget || (rootScreen && directTarget.id === rootScreen.id) || visitedScreens.has(directTarget.id)) {
                      return null;
                    }

                    const dtKey = `${screen.id}_direct_${dt.to_id}_${dtIdx}`;
                    const isDtExpanded = isAllExpanded ? expandedTransitions[dtKey] !== false : expandedTransitions[dtKey];

                    return (
                      <div key={dtKey} className="py-1">
                        <div
                          onClick={() => toggleTransition(dtKey)}
                          className="flex items-center justify-between p-2 rounded-xl bg-slate-50 hover:bg-indigo-50/50 cursor-pointer border border-slate-200/70 select-none"
                        >
                          <div className="flex items-center space-x-2">
                            {isDtExpanded ? (
                              <ChevronDown className="w-3.5 h-3.5 text-indigo-600" />
                            ) : (
                              <ChevronRight className="w-3.5 h-3.5 text-slate-400" />
                            )}
                            <span className="text-xs font-bold text-slate-800">
                              via "{dt.action || 'CLICK'}"
                            </span>
                            <span className="text-[10px] px-2 py-0.5 rounded-md bg-indigo-50 text-indigo-700 border border-indigo-200 font-mono font-semibold">
                              → {directTarget.title}
                            </span>
                          </div>
                        </div>

                        {isDtExpanded && (
                          <div className="ml-6 pl-4 border-l-2 border-indigo-200/80 my-1.5">
                            {renderNavGraphScreen(directTarget, depth + 1, nextVisited)}
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
    <div className="flex-1 flex flex-col min-h-0 bg-[#f8fafc] px-6 py-4">
      {/* Top Header & View Mode Switcher */}
      <div className="flex flex-wrap items-center justify-between pb-3.5 border-b border-slate-200/80 gap-3">
        <div className="flex items-center space-x-3">
          <div className="p-2 rounded-xl bg-indigo-50 border border-indigo-100 text-indigo-600 shadow-xs">
            <Layers className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-base font-bold text-slate-900 flex items-center gap-2">
              Screen Hierarchy & Nav Tree
              {activeApp && (
                <span className="text-xs px-2.5 py-0.5 rounded-md font-mono font-bold bg-indigo-50 text-indigo-700 border border-indigo-200">
                  {activeApp.app_name || activeApp.package_name}
                </span>
              )}
            </h2>
            <p className="text-xs text-slate-500 font-medium">
              Explore app screens, recursive transitions, and live UI dumps
            </p>
          </div>
        </div>

        {/* View Mode Toggle */}
        <div className="flex items-center space-x-1.5 bg-slate-100 p-1 rounded-xl border border-slate-200/80">
          <button
            onClick={() => setViewMode('nav_graph')}
            className={`flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${
              viewMode === 'nav_graph'
                ? 'bg-white text-indigo-600 shadow-xs border border-slate-200/60'
                : 'text-slate-600 hover:text-slate-900 hover:bg-white/60'
            }`}
          >
            <GitFork className="w-3.5 h-3.5" />
            <span>Nav Graph Tree</span>
          </button>

          <button
            onClick={() => setViewMode('live')}
            className={`flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${
              viewMode === 'live'
                ? 'bg-white text-indigo-600 shadow-xs border border-slate-200/60'
                : 'text-slate-600 hover:text-slate-900 hover:bg-white/60'
            }`}
          >
            <Smartphone className="w-3.5 h-3.5" />
            <span>Live Captures ({liveScreens.length})</span>
          </button>

          <button
            onClick={() => setViewMode('json')}
            className={`flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${
              viewMode === 'json'
                ? 'bg-white text-indigo-600 shadow-xs border border-slate-200/60'
                : 'text-slate-600 hover:text-slate-900 hover:bg-white/60'
            }`}
          >
            <Code className="w-3.5 h-3.5" />
            <span>Raw JSON</span>
          </button>
        </div>
      </div>

      {/* App Selector & Search Controls (Nav Graph Mode) */}
      {viewMode === 'nav_graph' && navGraph && navGraph.apps && navGraph.apps.length > 0 && (
        <div className="flex flex-wrap items-center justify-between gap-3 my-3.5 p-3.5 bg-white rounded-2xl border border-slate-200/80 shadow-xs">
          <div className="flex items-center space-x-3 flex-wrap gap-y-2">
            <span className="text-xs text-slate-500 font-semibold">Select App:</span>
            <select
              value={selectedAppPkg}
              onChange={(e) => setSelectedAppPkg(e.target.value)}
              className="bg-white border border-slate-200/80 text-xs text-slate-800 rounded-xl px-3 py-1.5 focus:outline-none focus:ring-2 focus:ring-indigo-500 font-medium"
            >
              {primaryApps.map((a) => (
                <option key={a.package_name} value={a.package_name}>
                  {a.app_name || a.package_name} ({clusterCountMap.get(a.package_name) || a.screens.length} screens)
                </option>
              ))}
              {satelliteApps.length > 0 && (
                <optgroup label="── Satellite & OEM Sub-Packages ──">
                  {satelliteApps.map((a) => (
                    <option key={a.package_name} value={a.package_name}>
                      {a.app_name || a.package_name} ({a.screens.length} screens)
                    </option>
                  ))}
                </optgroup>
              )}
            </select>

            {activeApp && (
              <span className="text-[11px] font-mono text-slate-400 font-medium">
                {activeApp.screens.length} screens, {activeApp.transitions.length} transitions
              </span>
            )}

            {/* Expand / Collapse All Toggle Button */}
            <button
              onClick={() => setIsAllExpanded(!isAllExpanded)}
              className="px-3 py-1 rounded-xl text-xs font-semibold bg-slate-50 hover:bg-slate-100 text-slate-700 border border-slate-200 transition-all flex items-center space-x-1"
            >
              {isAllExpanded ? (
                <>
                  <ChevronDown className="w-3 h-3 text-indigo-600" />
                  <span>Collapse All</span>
                </>
              ) : (
                <>
                  <ChevronRight className="w-3 h-3 text-indigo-600" />
                  <span>Expand All</span>
                </>
              )}
            </button>
          </div>

          {/* Search Filter */}
          <div className="relative min-w-[220px] max-w-xs">
            <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              type="text"
              placeholder="Search elements in graph..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full bg-white border border-slate-200/80 rounded-xl pl-9 pr-3 py-1.5 text-xs text-slate-800 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500 font-medium"
            />
            {searchQuery && (
              <button
                onClick={() => setSearchQuery('')}
                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
              >
                <X className="w-3 h-3" />
              </button>
            )}
          </div>
        </div>
      )}

      {/* Live Mode Evaluation & Filter Control Bar (Nexus Theme) */}
      {viewMode === 'live' && liveScreens.length > 0 && (
        <div className="my-3.5 p-3.5 bg-white rounded-2xl border border-slate-200/80 shadow-[0_1px_3px_0_rgba(0,0,0,0.02)] space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-3">
            {/* Filter Pills */}
            <div className="flex items-center space-x-1.5 flex-wrap gap-y-1">
              <span className="text-xs text-slate-500 font-semibold mr-1 flex items-center space-x-1">
                <Filter className="w-3.5 h-3.5 text-slate-400" />
                <span>Filter:</span>
              </span>

              <button
                onClick={() => setEvalFilter('all')}
                className={`px-3 py-1 rounded-xl text-xs font-semibold transition-all shadow-xs ${
                  evalFilter === 'all'
                    ? 'bg-indigo-50 text-indigo-700 border border-indigo-200'
                    : 'bg-slate-50 hover:bg-slate-100 text-slate-600 border border-slate-200/60'
                }`}
              >
                All ({liveScreens.length})
              </button>

              <button
                onClick={() => setEvalFilter('correct')}
                className={`flex items-center space-x-1 px-3 py-1 rounded-xl text-xs font-semibold transition-all shadow-xs ${
                  evalFilter === 'correct'
                    ? 'bg-emerald-500 text-white border border-emerald-600 shadow-sm'
                    : 'bg-emerald-50 hover:bg-emerald-100/80 text-emerald-700 border border-emerald-200'
                }`}
              >
                <Check className="w-3.5 h-3.5" />
                <span>Correct ({correctCount})</span>
              </button>

              <button
                onClick={() => setEvalFilter('wrong')}
                className={`flex items-center space-x-1 px-3 py-1 rounded-xl text-xs font-semibold transition-all shadow-xs ${
                  evalFilter === 'wrong'
                    ? 'bg-rose-500 text-white border border-rose-600 shadow-sm'
                    : 'bg-rose-50 hover:bg-rose-100/80 text-rose-700 border border-rose-200'
                }`}
              >
                <X className="w-3.5 h-3.5" />
                <span>Wrong ({wrongCount})</span>
              </button>

              <button
                onClick={() => setEvalFilter('unreviewed')}
                className={`px-3 py-1 rounded-xl text-xs font-semibold transition-all shadow-xs ${
                  evalFilter === 'unreviewed'
                    ? 'bg-slate-200 text-slate-800 border border-slate-300'
                    : 'bg-slate-50 hover:bg-slate-100 text-slate-600 border border-slate-200/60'
                }`}
              >
                Unreviewed ({unreviewedCount})
              </button>
            </div>

            {/* Action Buttons: Export & Study */}
            <div className="flex items-center space-x-2">
              <button
                onClick={() => exportEvaluationData('correct')}
                disabled={correctCount === 0}
                className={`flex items-center space-x-1 px-3 py-1.5 rounded-xl text-xs font-semibold transition-all ${
                  correctCount > 0
                    ? 'bg-emerald-50 hover:bg-emerald-100 text-emerald-700 border border-emerald-200 cursor-pointer shadow-xs'
                    : 'bg-slate-100 text-slate-400 border border-slate-200/60 cursor-not-allowed'
                }`}
                title="Download JSON of all verified correct screens"
              >
                <Download className="w-3.5 h-3.5" />
                <span>Save Correct ({correctCount})</span>
              </button>

              <button
                onClick={() => exportEvaluationData('wrong')}
                disabled={wrongCount === 0}
                className={`flex items-center space-x-1 px-3 py-1.5 rounded-xl text-xs font-semibold transition-all ${
                  wrongCount > 0
                    ? 'bg-rose-50 hover:bg-rose-100 text-rose-700 border border-rose-200 cursor-pointer shadow-xs'
                    : 'bg-slate-100 text-slate-400 border border-slate-200/60 cursor-not-allowed'
                }`}
                title="Download structured JSON of all wrong screens for study and analysis"
              >
                <Download className="w-3.5 h-3.5" />
                <span>Export Wrong ({wrongCount})</span>
              </button>

              {wrongCount > 0 && (
                <button
                  onClick={() => {
                    if (wrongScreensList.length > 0) {
                      setSelectedWrongScreenId(wrongScreensList[0].id);
                    }
                    setIsStudyModalOpen(true);
                  }}
                  className="flex items-center space-x-1 px-3 py-1.5 rounded-xl text-xs font-semibold bg-amber-50 hover:bg-amber-100 text-amber-700 border border-amber-200 transition-all cursor-pointer shadow-xs animate-pulse"
                >
                  <AlertTriangle className="w-3.5 h-3.5 text-amber-600" />
                  <span>Study & Analyze ({wrongCount})</span>
                </button>
              )}

              {/* Expand / Collapse All Live Screens Toggle */}
              <button
                onClick={() => {
                  const nextState = !isAllLiveExpanded;
                  setIsAllLiveExpanded(nextState);
                  const updated: Record<string, boolean> = {};
                  liveScreens.forEach((_, idx) => {
                    updated[idx] = nextState;
                  });
                  setExpandedLiveScreens(updated);
                }}
                className="px-3 py-1.5 rounded-xl text-xs font-semibold bg-slate-50 hover:bg-slate-100 text-slate-700 border border-slate-200 transition-all flex items-center space-x-1"
              >
                {isAllLiveExpanded ? (
                  <>
                    <ChevronDown className="w-3 h-3 text-indigo-600" />
                    <span>Collapse All</span>
                  </>
                ) : (
                  <>
                    <ChevronRight className="w-3 h-3 text-indigo-600" />
                    <span>Expand All</span>
                  </>
                )}
              </button>
            </div>
          </div>

          {/* Search bar inside Live mode */}
          <div className="relative">
            <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              type="text"
              placeholder="Filter live screens by title, package, elements, or notes..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-9 pr-8 py-1.5 text-xs text-slate-800 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500 font-medium"
            />
            {searchQuery && (
              <button
                onClick={() => setSearchQuery('')}
                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
              >
                <X className="w-3 h-3" />
              </button>
            )}
          </div>
        </div>
      )}

      {/* Main Content Area */}
      <div className="flex-1 min-h-0 overflow-y-auto">
        {/* NAV GRAPH TREE MODE */}
        {viewMode === 'nav_graph' && (
          <div className="space-y-4">
            {!activeApp ? (
              <div className="flex flex-col items-center justify-center py-20 text-center text-slate-400">
                <GitFork className="w-10 h-10 stroke-[1.5] mb-2 opacity-40 text-slate-400" />
                <p className="text-sm font-semibold text-slate-700">No App Selected or No Graph Data</p>
                <p className="text-xs text-slate-500 mt-1">Select an app above to view its screen hierarchy</p>
              </div>
            ) : !rootScreen ? (
              <div className="flex flex-col items-center justify-center py-20 text-center text-slate-400">
                <FolderOpen className="w-10 h-10 stroke-[1.5] mb-2 opacity-40 text-slate-400" />
                <p className="text-sm font-semibold text-slate-700">No Screens in App</p>
                <p className="text-xs text-slate-500 mt-1">{activeApp.package_name} has 0 screens recorded</p>
              </div>
            ) : (
              <div className="space-y-3">
                {/* [PERMANENT FIX] Do not remove this! All Local Query Paths Multi-Route Explorer Card */}
                {discoveredRoutes.length > 0 && (
                  <div className="p-3.5 rounded-2xl bg-white border border-slate-200/80 shadow-xs space-y-2">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center space-x-2">
                        <GitFork className="w-4 h-4 text-indigo-600" />
                        <span className="text-xs font-bold text-slate-900">
                          Active Dijkstra Multi-Route Candidates ({discoveredRoutes.length})
                        </span>
                      </div>
                      <span className="text-[10px] font-mono text-slate-400">Local Graph Tier 1</span>
                    </div>
                    <div className="space-y-1.5 max-h-40 overflow-y-auto pr-1">
                      {discoveredRoutes.map((route, rIdx) => (
                        <div
                          key={`route_${rIdx}`}
                          className="flex items-center justify-between p-2 rounded-xl bg-slate-50 border border-slate-200/70 text-xs font-mono"
                        >
                          <div className="flex items-center space-x-2 truncate mr-2">
                            <span className="px-1.5 py-0.5 rounded bg-indigo-50 text-indigo-700 font-bold text-[10px]">
                              Route {rIdx + 1}
                            </span>
                            <span className="text-slate-800 font-semibold truncate">
                              {route.pathString}
                            </span>
                          </div>
                          <div className="flex items-center space-x-2 shrink-0">
                            <span className="text-[10px] text-slate-500">
                              Cost: {route.cost}
                            </span>
                            <span className="text-[9px] px-1.5 py-0.5 rounded bg-slate-200 text-slate-700">
                              {route.steps.length} steps
                            </span>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {/* Graph recursive transitions tree */}
                {renderNavGraphScreen(rootScreen, 1)}

                {/* [PERMANENT FIX] Do not remove this! Render Root Screen + Additional Unconnected Screens Guard.
                    In case some screens in this app are not reachable directly via forward transitions from root,
                    surface them below in an Unconnected / Island Screens section so the user can still see them! */}
                {(() => {
                  const renderedScreens = new Set<string>();
                  const collectRendered = (scr: NavGraphScreen, visited: Set<string>) => {
                    if (visited.has(scr.id)) return;
                    visited.add(scr.id);
                    renderedScreens.add(scr.id);
                    scr.transitions.forEach((t) => {
                      const tgt = screensById.get(t.to_id);
                      if (tgt) collectRendered(tgt, visited);
                    });
                  };
                  collectRendered(rootScreen, new Set());

                  const unconnected = activeApp.screens.filter((s) => !renderedScreens.has(s.id));
                  if (unconnected.length === 0) return null;

                  return (
                    <div className="mt-6 pt-4 border-t-2 border-dashed border-slate-200 space-y-3">
                      <div className="flex items-center space-x-2 text-xs font-bold text-slate-500 uppercase tracking-wider">
                        <Info className="w-4 h-4 text-slate-400" />
                        <span>Additional Sub-Screens ({unconnected.length})</span>
                      </div>
                      <div className="space-y-2">
                        {unconnected.map((islandScreen) => renderNavGraphScreen(islandScreen, 1))}
                      </div>
                    </div>
                  );
                })()}
              </div>
            )}
          </div>
        )}

        {/* LIVE CAPTURES MODE */}
        {viewMode === 'live' && (
          <div className="space-y-3">
            {liveScreens.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-20 text-center text-slate-400">
                <Smartphone className="w-10 h-10 stroke-[1.5] mb-2 opacity-40 text-slate-400" />
                <p className="text-sm font-semibold text-slate-700">No Live Screen Captures</p>
                <p className="text-xs text-slate-500 mt-1">Screen dumps from active phone navigation will appear here in real time</p>
              </div>
            ) : displayedLiveScreens.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-16 text-center text-slate-400">
                <Filter className="w-6 h-6 text-slate-300 mb-1" />
                <p className="text-xs font-semibold text-slate-600">No screens match filter "{evalFilter}"</p>
                <button
                  onClick={() => {
                    setEvalFilter('all');
                    setSearchQuery('');
                  }}
                  className="mt-2 text-xs text-indigo-600 hover:underline font-semibold"
                >
                  Reset filters
                </button>
              </div>
            ) : (
              displayedLiveScreens.map((screen, sIdx) => {
                const isExpanded = expandedLiveScreens[sIdx] !== false;
                const evalRecord = evaluations[screen.id];

                return (
                  <div
                    key={screen.id}
                    className={`rounded-2xl border bg-white overflow-hidden transition-all duration-200 shadow-xs ${
                      evalRecord?.status === 'correct'
                        ? 'border-emerald-300/80 ring-2 ring-emerald-300/20'
                        : evalRecord?.status === 'wrong'
                        ? 'border-rose-300/80 ring-2 ring-rose-300/20'
                        : 'border-slate-200/80'
                    }`}
                  >
                    {/* Screen Card Header */}
                    <div
                      onClick={() => toggleLiveScreen(String(sIdx))}
                      className="flex items-center justify-between px-4 py-3 bg-slate-50/60 hover:bg-slate-100/50 cursor-pointer select-none transition-colors border-b border-slate-200/60"
                    >
                      <div className="flex items-center space-x-2.5">
                        <button className="text-slate-400 hover:text-slate-600">
                          {isExpanded ? (
                            <ChevronDown className="w-4 h-4 text-indigo-600" />
                          ) : (
                            <ChevronRight className="w-4 h-4 text-slate-400" />
                          )}
                        </button>
                        <Folder className="w-4 h-4 text-indigo-600" />
                        <span className="text-xs font-bold text-slate-900">
                          {screen.screenTitle}
                        </span>
                        <span className="text-[10px] font-mono text-slate-500 bg-slate-200/60 px-2 py-0.5 rounded-md">
                          {screen.packageName}
                        </span>
                        <span className="text-[10px] font-mono text-slate-400">
                          ({screen.elements.length} elements)
                        </span>

                        {evalRecord?.status === 'correct' && (
                          <span className="text-[10px] px-2 py-0.5 rounded-md font-mono font-bold bg-emerald-50 text-emerald-700 border border-emerald-200 flex items-center space-x-1">
                            <Check className="w-3 h-3 text-emerald-600" />
                            <span>Correct</span>
                          </span>
                        )}
                        {evalRecord?.status === 'wrong' && (
                          <span className="text-[10px] px-2 py-0.5 rounded-md font-mono font-bold bg-rose-50 text-rose-700 border border-rose-200 flex items-center space-x-1">
                            <X className="w-3 h-3 text-rose-600" />
                            <span>Wrong</span>
                          </span>
                        )}
                      </div>

                      {/* Right Header Actions */}
                      <div className="flex items-center space-x-2" onClick={(e) => e.stopPropagation()}>
                        {/* Evaluation Buttons */}
                        <div className="flex items-center space-x-1">
                          <button
                            onClick={() => toggleEvaluation(screen, 'correct')}
                            className={`flex items-center space-x-1 px-2.5 py-1 rounded-xl text-xs font-semibold transition-all active:scale-[0.98] ${
                              evalRecord?.status === 'correct'
                                ? 'bg-emerald-500 text-white border border-emerald-600 shadow-sm'
                                : 'bg-white hover:bg-emerald-50 text-slate-600 hover:text-emerald-700 border border-slate-200/80 shadow-xs'
                            }`}
                            title="Mark this screen as Correct"
                          >
                            <Check className="w-3 h-3" />
                            <span>Correct</span>
                          </button>

                          <button
                            onClick={() => toggleEvaluation(screen, 'wrong')}
                            className={`flex items-center space-x-1 px-2.5 py-1 rounded-xl text-xs font-semibold transition-all active:scale-[0.98] ${
                              evalRecord?.status === 'wrong'
                                ? 'bg-rose-500 text-white border border-rose-600 shadow-sm'
                                : 'bg-white hover:bg-rose-50 text-slate-600 hover:text-rose-700 border border-slate-200/80 shadow-xs'
                            }`}
                            title="Mark this screen as Wrong for study"
                          >
                            <X className="w-3 h-3" />
                            <span>Wrong</span>
                          </button>
                        </div>

                        <span className="text-[10px] font-mono text-slate-400">
                          Capture #{sIdx + 1}
                        </span>
                      </div>
                    </div>

                    {/* Screen Card Body: Elements List */}
                    {isExpanded && (
                      <div className="p-3 bg-white">
                        {screen.elements.length === 0 ? (
                          <div className="py-2 text-xs text-slate-400 text-center font-medium">0 elements captured</div>
                        ) : (
                          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2">
                            {screen.elements.map((el, elIdx) => (
                              <div
                                key={el.id || `live_el_${elIdx}`}
                                onClick={() =>
                                  setSelectedElement({
                                    element: el,
                                    screenTitle: screen.screenTitle,
                                    packageName: screen.packageName
                                  })
                                }
                                className="flex items-center justify-between p-2 rounded-xl bg-slate-50/70 hover:bg-slate-100/80 border border-slate-200/60 cursor-pointer select-none transition-colors"
                              >
                                <div className="flex items-center space-x-2 truncate mr-2">
                                  <FileCode className="w-3.5 h-3.5 text-slate-400 shrink-0" />
                                  <span className="text-xs font-semibold text-slate-800 truncate">
                                    {el.name}
                                  </span>
                                </div>
                                <div className="flex items-center space-x-1.5 shrink-0">
                                  {el.is_clickable && (
                                    <span className="text-[9px] px-1.5 py-0.5 rounded font-mono font-bold bg-indigo-50 text-indigo-700 border border-indigo-200">
                                      tap
                                    </span>
                                  )}
                                  {el.is_scrollable && (
                                    <span className="text-[9px] px-1.5 py-0.5 rounded font-mono font-bold bg-sky-50 text-sky-700 border border-sky-200">
                                      scroll
                                    </span>
                                  )}
                                </div>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                );
              })
            )}
          </div>
        )}

        {/* RAW JSON MODE */}
        {viewMode === 'json' && (
          <div className="rounded-2xl border border-slate-200/80 bg-white overflow-hidden shadow-xs">
            <div className="flex items-center justify-between px-4 py-2.5 bg-slate-50 border-b border-slate-200/80">
              <span className="text-xs font-bold text-slate-700 font-mono">
                {activeApp ? `${activeApp.package_name}.json` : 'nav_graph.json'}
              </span>
              <button
                onClick={() => handleCopy(activeApp || navGraph, 'json_view')}
                className="flex items-center space-x-1 px-2.5 py-1 rounded-lg text-xs font-semibold bg-white hover:bg-slate-100 text-slate-700 border border-slate-200 shadow-xs transition-all"
              >
                {copiedItemIdx === 'json_view' ? (
                  <>
                    <Check className="w-3 h-3 text-emerald-600" />
                    <span className="text-emerald-600">Copied!</span>
                  </>
                ) : (
                  <>
                    <Copy className="w-3 h-3" />
                    <span>Copy JSON</span>
                  </>
                )}
              </button>
            </div>
            <pre className="p-4 text-xs font-mono text-slate-800 bg-slate-50/50 overflow-x-auto max-h-[70vh]">
              {JSON.stringify(activeApp || navGraph, null, 2)}
            </pre>
          </div>
        )}
      </div>

      {/* Info Modal for Single Element */}
      {selectedElement && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 backdrop-blur-xs p-4">
          <div className="bg-white border border-slate-200 rounded-2xl max-w-md w-full p-5 shadow-2xl space-y-4">
            <div className="flex items-center justify-between pb-3 border-b border-slate-200">
              <div className="flex items-center space-x-2">
                <FileCode className="w-4 h-4 text-indigo-600" />
                <h3 className="text-sm font-bold text-slate-900 truncate">
                  {selectedElement.element.name}
                </h3>
              </div>
              <button
                onClick={() => setSelectedElement(null)}
                className="text-slate-400 hover:text-slate-600 p-1 rounded-lg hover:bg-slate-100"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="space-y-2.5 text-xs">
              <div className="flex justify-between py-1 border-b border-slate-100">
                <span className="text-slate-500 font-semibold">Screen:</span>
                <span className="text-slate-800 font-mono font-medium truncate ml-2">
                  {selectedElement.screenTitle}
                </span>
              </div>
              <div className="flex justify-between py-1 border-b border-slate-100">
                <span className="text-slate-500 font-semibold">Package:</span>
                <span className="text-slate-800 font-mono font-medium truncate ml-2">
                  {selectedElement.packageName}
                </span>
              </div>
              <div className="flex justify-between py-1 border-b border-slate-100">
                <span className="text-slate-500 font-semibold">Resource ID:</span>
                <span className="text-slate-800 font-mono truncate ml-2">
                  {selectedElement.element.resource_id || 'none'}
                </span>
              </div>
              <div className="flex justify-between py-1 border-b border-slate-100">
                <span className="text-slate-500 font-semibold">Class Name:</span>
                <span className="text-slate-800 font-mono truncate ml-2">
                  {selectedElement.element.class_name || 'none'}
                </span>
              </div>
              <div className="flex justify-between py-1 border-b border-slate-100">
                <span className="text-slate-500 font-semibold">Bounds:</span>
                <span className="text-slate-800 font-mono ml-2">
                  {selectedElement.element.bounds || 'none'}
                </span>
              </div>
              <div className="flex justify-between py-1 border-b border-slate-100">
                <span className="text-slate-500 font-semibold">Clickable:</span>
                <span
                  className={`font-semibold font-mono ${
                    selectedElement.element.is_clickable ? 'text-emerald-600' : 'text-slate-400'
                  }`}
                >
                  {selectedElement.element.is_clickable ? 'YES' : 'NO'}
                </span>
              </div>
              <div className="flex justify-between py-1">
                <span className="text-slate-500 font-semibold">Scrollable:</span>
                <span
                  className={`font-semibold font-mono ${
                    selectedElement.element.is_scrollable ? 'text-sky-600' : 'text-slate-400'
                  }`}
                >
                  {selectedElement.element.is_scrollable ? 'YES' : 'NO'}
                </span>
              </div>
            </div>

            <div className="flex items-center justify-between pt-3 border-t border-slate-200">
              <button
                onClick={() => handleCopy(selectedElement.element, 'element_modal')}
                className="flex items-center space-x-1.5 px-3 py-1.5 rounded-xl bg-slate-100 hover:bg-slate-200 text-xs font-semibold text-slate-700 transition-colors"
              >
                {copiedItemIdx === 'element_modal' ? (
                  <>
                    <Check className="w-3.5 h-3.5 text-emerald-600" />
                    <span className="text-emerald-600">Copied!</span>
                  </>
                ) : (
                  <>
                    <Copy className="w-3.5 h-3.5 text-slate-500" />
                    <span>Copy JSON</span>
                  </>
                )}
              </button>
              <button
                onClick={() => setSelectedElement(null)}
                className="px-3.5 py-1.5 rounded-xl bg-slate-100 hover:bg-slate-200 text-xs font-semibold text-slate-700 transition-colors"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Study & Analysis Modal for Wrong Screens (Nexus Light Theme) */}
      {isStudyModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 backdrop-blur-xs p-4">
          <div className="bg-white border border-slate-200 rounded-3xl max-w-4xl w-full max-h-[88vh] flex flex-col shadow-2xl overflow-hidden">
            {/* Modal Header */}
            <div className="flex items-center justify-between px-6 py-4 border-b border-slate-200/80 bg-slate-50/50">
              <div className="flex items-center space-x-3">
                <div className="p-2 rounded-xl bg-rose-50 text-rose-600 border border-rose-100 shadow-xs">
                  <AlertTriangle className="w-4 h-4" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-slate-900 flex items-center gap-2">
                    Screen Extraction Failure Study & Analysis
                    <span className="text-xs px-2.5 py-0.5 rounded-md font-mono font-bold bg-rose-50 text-rose-700 border border-rose-200">
                      {wrongScreensList.length} marked
                    </span>
                  </h3>
                  <p className="text-xs text-slate-500 font-medium">
                    Analyze screens marked as wrong, diagnose extraction errors, tag failure patterns, and export report
                  </p>
                </div>
              </div>
              <button
                onClick={() => setIsStudyModalOpen(false)}
                className="text-slate-400 hover:text-slate-600 p-1.5 rounded-xl hover:bg-slate-200/60 transition-colors"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            {/* Modal Body */}
            <div className="flex-1 min-h-0 flex overflow-hidden">
              {/* Left sidebar: list of wrong screens */}
              <div className="w-1/3 border-r border-slate-200 bg-slate-50/50 overflow-y-auto p-3.5 space-y-2">
                <span className="text-[11px] font-mono uppercase font-bold text-slate-400 px-1">
                  Marked Screens ({wrongScreensList.length})
                </span>
                {wrongScreensList.map((ws) => {
                  const rec = evaluations[ws.id];
                  const isSelected = (selectedWrongScreenId || wrongScreensList[0]?.id) === ws.id;
                  return (
                    <div
                      key={ws.id}
                      onClick={() => setSelectedWrongScreenId(ws.id)}
                      className={`p-3 rounded-xl cursor-pointer transition-all border text-left ${
                        isSelected
                          ? 'bg-rose-50 border-rose-300 shadow-xs'
                          : 'bg-white hover:bg-slate-100/70 border-slate-200/70'
                      }`}
                    >
                      <div className="text-xs font-bold text-slate-900 truncate">
                        {ws.screenTitle}
                      </div>
                      <div className="text-[10px] font-mono text-slate-500 truncate mt-0.5">
                        {ws.packageName}
                      </div>
                      {rec?.tags && rec.tags.length > 0 && (
                        <div className="flex flex-wrap gap-1 mt-1.5">
                          {rec.tags.map((t) => (
                            <span key={t} className="text-[9px] px-1.5 py-0.5 rounded-md font-semibold bg-rose-100/80 text-rose-800">
                              {t}
                            </span>
                          ))}
                        </div>
                      )}
                      {rec?.notes && (
                        <div className="text-[11px] text-slate-500 truncate mt-1 italic font-medium">
                          "{rec.notes}"
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>

              {/* Right panel: Active Wrong Screen Analysis */}
              <div className="flex-1 overflow-y-auto p-6 space-y-4 bg-white">
                {(() => {
                  const activeWrongScreen = wrongScreensList.find(
                    (s) => s.id === (selectedWrongScreenId || wrongScreensList[0]?.id)
                  );
                  if (!activeWrongScreen) {
                    return (
                      <div className="py-20 text-center text-slate-400 text-xs">
                        Select a screen on the left to analyze
                      </div>
                    );
                  }
                  const rec = evaluations[activeWrongScreen.id];

                  const FAILURE_TAGS = [
                    'Noise Elements Captured',
                    'Missing Visible Elements',
                    'Wrong Parent Linking',
                    'Circular Navigation Loop',
                    'Phantom Screen / Keystroke Noise',
                    'Status / Toggle Suffix Not Stripped',
                    'Return / Back Control Misinterpreted',
                    'Incomplete Tree'
                  ];

                  return (
                    <div className="space-y-4">
                      {/* Active Screen Info */}
                      <div className="p-4 rounded-2xl bg-slate-50 border border-slate-200 space-y-1.5">
                        <div className="flex items-center justify-between">
                          <h4 className="text-sm font-bold text-slate-900">
                            {activeWrongScreen.screenTitle}
                          </h4>
                          <span className="text-[10px] font-mono font-bold px-2 py-0.5 rounded-md bg-rose-100 text-rose-800">
                            MARKED WRONG
                          </span>
                        </div>
                        <p className="text-xs font-mono text-slate-500">
                          Package: {activeWrongScreen.packageName}
                        </p>
                        <p className="text-xs text-slate-500 font-medium">
                          Total elements captured: {activeWrongScreen.elements.length}
                        </p>
                      </div>

                      {/* Diagnostic Tagging */}
                      <div className="space-y-1.5">
                        <label className="text-xs font-bold text-slate-700 flex items-center space-x-1.5">
                          <Tag className="w-3.5 h-3.5 text-indigo-600" />
                          <span>Diagnostic Failure Tags:</span>
                        </label>
                        <div className="flex flex-wrap gap-1.5 pt-1">
                          {FAILURE_TAGS.map((t) => {
                            const isTagged = rec?.tags?.includes(t);
                            return (
                              <button
                                key={t}
                                onClick={() => toggleWrongScreenTag(activeWrongScreen.id, t)}
                                className={`text-xs px-2.5 py-1 rounded-xl font-semibold transition-all cursor-pointer border ${
                                  isTagged
                                    ? 'bg-rose-500 text-white border-rose-600 shadow-xs'
                                    : 'bg-slate-50 hover:bg-slate-100 text-slate-700 border-slate-200'
                                }`}
                              >
                                {isTagged ? `✓ ${t}` : `+ ${t}`}
                              </button>
                            );
                          })}
                        </div>
                      </div>

                      {/* Analysis Notes Textarea */}
                      <div className="space-y-1.5">
                        <label className="text-xs font-bold text-slate-700 flex items-center space-x-1.5">
                          <FileText className="w-3.5 h-3.5 text-indigo-600" />
                          <span>Failure Analysis Notes (Auto-saved):</span>
                        </label>
                        <textarea
                          rows={3}
                          value={rec?.notes || ''}
                          onChange={(e) => updateWrongScreenNotes(activeWrongScreen.id, e.target.value)}
                          placeholder="Describe what was wrong: e.g. 'Incomplete tree on scroll', 'Linked to wrong parent screen', etc."
                          className="w-full bg-slate-50 border border-slate-200 rounded-xl p-3 text-xs text-slate-800 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500 font-medium resize-none"
                        />
                      </div>

                      {/* Elements extracted preview */}
                      <div className="space-y-1.5">
                        <label className="text-xs font-bold text-slate-700">
                          Captured Elements ({activeWrongScreen.elements.length}):
                        </label>
                        <div className="max-h-48 overflow-y-auto bg-slate-50 border border-slate-200 rounded-xl divide-y divide-slate-100">
                          {activeWrongScreen.elements.map((el, i) => (
                            <div key={i} className="flex items-center justify-between px-3 py-1.5 text-xs">
                              <span className="text-slate-800 truncate font-semibold">{el.name}</span>
                              <span className="text-[10px] font-mono text-slate-400 font-medium">
                                {el.is_clickable ? 'Clickable' : 'Static'}
                              </span>
                            </div>
                          ))}
                        </div>
                      </div>

                      {/* Change status buttons */}
                      <div className="flex items-center justify-between pt-2 border-t border-slate-100">
                        <div className="flex items-center space-x-2">
                          <button
                            onClick={() => toggleEvaluation(activeWrongScreen, 'correct')}
                            className="flex items-center space-x-1 px-3 py-1.5 rounded-xl bg-emerald-50 hover:bg-emerald-100 text-emerald-700 border border-emerald-200 text-xs font-semibold cursor-pointer"
                          >
                            <Check className="w-3.5 h-3.5" />
                            <span>Mark Correct instead</span>
                          </button>
                          <button
                            onClick={() => toggleEvaluation(activeWrongScreen, 'wrong')}
                            className="px-3 py-1.5 rounded-xl bg-slate-100 hover:bg-slate-200 text-slate-600 text-xs font-semibold cursor-pointer"
                          >
                            Remove Mark (Unreview)
                          </button>
                        </div>
                      </div>
                    </div>
                  );
                })()}
              </div>
            </div>

            {/* Modal Footer */}
            <div className="flex items-center justify-between px-6 py-3.5 border-t border-slate-200/80 bg-slate-50/50 text-xs">
              <span className="text-slate-500 font-mono font-medium">
                Notes & evaluations are auto-saved in your browser.
              </span>
              <div className="flex items-center space-x-2">
                <button
                  onClick={() => exportEvaluationData('wrong')}
                  className="flex items-center space-x-1.5 px-3.5 py-1.5 rounded-xl bg-rose-50 hover:bg-rose-100 text-rose-700 border border-rose-200 font-semibold cursor-pointer"
                >
                  <Download className="w-3.5 h-3.5 text-rose-600" />
                  <span>Download Wrong Analysis JSON</span>
                </button>
                <button
                  onClick={() => setIsStudyModalOpen(false)}
                  className="px-4 py-1.5 rounded-xl bg-slate-200 hover:bg-slate-300 text-slate-700 font-semibold cursor-pointer"
                >
                  Close
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
