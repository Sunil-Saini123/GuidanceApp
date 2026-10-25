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
  X,
  FolderOpen,
  GitFork,
  Smartphone,
  ExternalLink,
  Download,
  AlertTriangle,
  Tag,
  FileText,
  Trash2,
  Filter
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

// ── Nexus Layer Styling Map ───────────────────────────────────────────────────

const getLayerStyle = (layer: number) => {
  switch (layer) {
    case 0:
      return {
        bg: 'bg-white',
        hover: 'hover:bg-slate-50/80',
        borderLeft: 'border-l-[4px] border-indigo-600',
        badgeBg: 'bg-indigo-50 text-indigo-700 border border-indigo-200',
        paddingLeft: 'pl-4',
        layerName: 'Screen'
      };
    case 1:
      return {
        bg: 'bg-slate-50/70',
        hover: 'hover:bg-indigo-50/40',
        borderLeft: 'border-l-[3px] border-violet-500',
        badgeBg: 'bg-violet-50 text-violet-700 border border-violet-200',
        paddingLeft: 'pl-6',
        layerName: 'L1'
      };
    case 2:
      return {
        bg: 'bg-slate-50/40',
        hover: 'hover:bg-sky-50/40',
        borderLeft: 'border-l-[3px] border-sky-500',
        badgeBg: 'bg-sky-50 text-sky-700 border border-sky-200',
        paddingLeft: 'pl-11',
        layerName: 'L2'
      };
    case 3:
      return {
        bg: 'bg-slate-50/20',
        hover: 'hover:bg-purple-50/40',
        borderLeft: 'border-l-[3px] border-purple-500',
        badgeBg: 'bg-purple-50 text-purple-700 border border-purple-200',
        paddingLeft: 'pl-16',
        layerName: 'L3'
      };
    default:
      return {
        bg: 'bg-white',
        hover: 'hover:bg-slate-50',
        borderLeft: 'border-l-[3px] border-slate-400',
        badgeBg: 'bg-slate-100 text-slate-700 border border-slate-200',
        paddingLeft: 'pl-20',
        layerName: `L${layer}`
      };
  }
};

// ── Main Component ────────────────────────────────────────────────────────────

export const TreeExplorerTab: React.FC<TreeExplorerTabProps> = ({ events, navGraph }) => {
  // View Modes: 'nav_graph' (Real Navigation Tree from .db), 'live' (Current screen capture), 'json'
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
  const [expandedTransitions, setExpandedTransitions] = useState<Record<string, boolean>>({});

  // Active info modal for element details
  const [selectedElement, setSelectedElement] = useState<{
    element: any;
    screenTitle: string;
  } | null>(null);

  // Extract distinct screens from live telemetry events
  const liveScreens: ScreenGroup[] = useMemo(() => {
    const list: ScreenGroup[] = [];
    const seenIds = new Set<string>();

    for (const event of events) {
      if (event.elements && event.elements.length > 0) {
        const title = event.screenTitle || 'Unknown Screen';
        const pkg = event.packageName || 'unknown.app';
        const dedupeKey = `${pkg}::${title}`;

        if (!seenIds.has(dedupeKey)) {
          seenIds.add(dedupeKey);
          list.push({
            id: dedupeKey,
            screenTitle: title,
            packageName: pkg,
            timestamp: event.timestamp,
            elements: event.elements
          });
        }
      }
    }
    return list;
  }, [events]);

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

  const toggleTransition = (key: string) => {
    setExpandedTransitions((prev) => ({
      ...prev,
      [key]: !prev[key]
    }));
  };

  const handleCopy = (data: any, key: string) => {
    navigator.clipboard.writeText(JSON.stringify(data, null, 2));
    setCopiedItemIdx(key);
    setTimeout(() => setCopiedItemIdx(null), 1500);
  };

  // ── Evaluation Helpers ────────────────────────────────────────────────────

  const toggleEvaluation = (
    screen: { id: string; screenTitle: string; packageName: string; elements: TreeElementItem[]; timestamp?: number },
    targetStatus: ScreenEvaluationStatus
  ) => {
    setEvaluations((prev) => {
      const existing = prev[screen.id];
      if (existing && existing.status === targetStatus) {
        // Toggle off if already in this state
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

  // Nav Graph calculations
  const activeApp: NavGraphApp | undefined = useMemo(() => {
    if (!navGraph || !navGraph.apps) return undefined;
    if (selectedAppPkg) {
      const found = navGraph.apps.find((a) => a.package_name === selectedAppPkg);
      if (found) return found;
    }
    return navGraph.apps[0];
  }, [navGraph, selectedAppPkg]);

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

  // Typographic & suffix normalizer for element-to-transition matching
  const normalizeLabel = (text: string): string => {
    if (!text) return '';
    return text
      .replace(/[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]/g, '-')
      .replace(/[\u00A0\u202F\u2007\u200B]/g, ' ')
      .split(/\s*[\u00B7\u2022\u22C5\u2014]\s*/)[0]
      .replace(/\s+(on|off|high|low|medium|connected|disconnected|disabled|enabled)\s*$/i, '')
      .replace(/\s+\d+$/, '')
      .trim()
      .toLowerCase();
  };

  // Find Root / Entry screen for active app
  const rootScreen = useMemo(() => {
    if (!activeApp || activeApp.screens.length === 0) return undefined;
    const match = activeApp.screens.find(
      (s) =>
        s.title.toLowerCase() === activeApp.display_name.toLowerCase() ||
        s.title.toLowerCase() === 'settings' ||
        s.title.toLowerCase() === 'chats'
    );
    if (match) return match;
    const sorted = [...activeApp.screens].sort(
      (a, b) => (b.visit_count || 0) - (a.visit_count || 0)
    );
    return sorted[0];
  }, [activeApp]);

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

    const seenElementBases = new Set<string>();
    const sanitizedElements = screen.elements.filter((el) => {
      const trimmed = el.trim();
      if (!trimmed) return false;
      if (trimmed.toLowerCase() === screen.title.toLowerCase()) return false;
      if (trimmed.toLowerCase() === 'search box') return false;
      const normNoise = trimmed.toLowerCase().replace(/_/g, ' ');
      if (normNoise === 'bottom bar' || normNoise === 'action bar' || normNoise === 'scrollable list') return false;
      const base = normalizeLabel(trimmed);
      if (!base) return false;
      if (seenElementBases.has(base)) return false;
      seenElementBases.add(base);
      return true;
    });

    const q = searchQuery.trim().toLowerCase();
    const visibleElements = q
      ? sanitizedElements.filter((el) => el.toLowerCase().includes(q))
      : sanitizedElements;

    return (
      <div key={screen.id} className="space-y-1.5">
        {/* Screen Header */}
        <div
          className={`flex items-center justify-between px-4 py-2.5 rounded-2xl ${style.bg} ${style.borderLeft} border border-slate-200/80 shadow-xs select-none ${
            evalRecord?.status === 'correct'
              ? 'ring-2 ring-emerald-300/40 border-emerald-300'
              : evalRecord?.status === 'wrong'
              ? 'ring-2 ring-rose-300/40 border-rose-300'
              : ''
          }`}
        >
          <div className="flex items-center space-x-2.5">
            <FolderOpen className="w-4 h-4 text-indigo-600" />
            <span className="text-xs font-bold text-slate-900">
              {screen.title}
            </span>
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
            <span className="text-[11px] text-slate-400 font-mono font-medium">
              ({screen.elements.length} elements, {screen.transitions.length} links)
            </span>
          </div>

          <div className="flex items-center space-x-2.5" onClick={(e) => e.stopPropagation()}>
            {/* Correct & Wrong buttons for Nav Graph screens */}
            <div className="flex items-center space-x-1">
              <button
                onClick={() => toggleNavGraphEvaluation(screen, 'correct')}
                className={`flex items-center space-x-1 px-2.5 py-1 rounded-xl text-xs font-semibold transition-all active:scale-[0.98] shadow-xs ${
                  evalRecord?.status === 'correct'
                    ? 'bg-emerald-600 text-white border border-emerald-600 shadow-sm ring-2 ring-emerald-200'
                    : 'bg-white hover:bg-emerald-50 text-slate-600 hover:text-emerald-700 border border-slate-200/90'
                }`}
                title="Mark this screen as Correct"
              >
                <Check className="w-3 h-3" />
                <span>Correct</span>
              </button>

              <button
                onClick={() => toggleNavGraphEvaluation(screen, 'wrong')}
                className={`flex items-center space-x-1 px-2.5 py-1 rounded-xl text-xs font-semibold transition-all active:scale-[0.98] shadow-xs ${
                  evalRecord?.status === 'wrong'
                    ? 'bg-rose-600 text-white border border-rose-600 shadow-sm ring-2 ring-rose-200'
                    : 'bg-white hover:bg-rose-50 text-slate-600 hover:text-rose-700 border border-slate-200/90'
                }`}
                title="Mark this screen as Wrong for study"
              >
                <X className="w-3 h-3" />
                <span>Wrong</span>
              </button>
            </div>

            <span className="text-[11px] font-mono text-slate-400 font-medium">
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
                const normElem = normalizeLabel(elemName);
                const transition = screen.transitions.find((t) => {
                  if (t.type !== 'CLICK') return false;
                  const normAction = normalizeLabel(t.action);
                  const normToTitle = normalizeLabel(t.to_title);
                  return (
                    normAction === normElem ||
                    normToTitle === normElem ||
                    (normElem.length >= 4 && (normAction.includes(normElem) || normToTitle.includes(normElem))) ||
                    (normAction.length >= 4 && normElem.includes(normAction))
                  );
                });

                const targetScreen = transition ? screensById.get(transition.to_id) : undefined;
                const transitionKey = `${screen.id}->${transition?.to_id || elemName}`;
                const isTransitionOpen = expandedTransitions[transitionKey] ?? false;

                return (
                  <div key={`${screen.id}_${eIdx}`} className="py-0.5">
                    {/* Element Row */}
                    <div
                      className={`flex items-center justify-between px-3.5 py-2 rounded-xl hover:bg-slate-50 transition-colors ${style.paddingLeft}`}
                    >
                      <div className="flex items-center space-x-2">
                        <span className="text-slate-300 font-mono text-[11px]">├─</span>
                        <span className="text-xs text-slate-800 font-semibold">
                          {elemName}
                        </span>
                      </div>

                      {transition && targetScreen ? (
                        <div className="flex items-center space-x-2">
                          <button
                            onClick={() => toggleTransition(transitionKey)}
                            className="flex items-center space-x-1 px-2.5 py-1 rounded-lg text-[11px] font-semibold bg-indigo-50 text-indigo-700 hover:bg-indigo-100/80 border border-indigo-200 transition-all active:scale-[0.98]"
                          >
                            <ExternalLink className="w-3 h-3 text-indigo-600" />
                            <span>opens {targetScreen.title}</span>
                            {isTransitionOpen ? (
                              <ChevronDown className="w-3 h-3 ml-1" />
                            ) : (
                              <ChevronRight className="w-3 h-3 ml-1" />
                            )}
                          </button>
                        </div>
                      ) : (
                        <span className="text-[10px] font-mono text-slate-400 font-medium">
                          leaf action
                        </span>
                      )}
                    </div>

                    {/* Sub-tree recursion */}
                    {transition && targetScreen && isTransitionOpen && (
                      <div className="mt-1 mb-2 ml-4 border-l border-slate-200">
                        {renderNavGraphScreen(targetScreen, depth + 1, nextVisited)}
                      </div>
                    )}
                  </div>
                );
              })
            )}
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="flex-1 flex flex-col min-h-0 bg-[#f8fafc] px-6 py-4">
      {/* Top Header Bar */}
      <div className="flex flex-wrap items-center justify-between pb-3.5 border-b border-slate-200/80 gap-3">
        <div className="flex items-center space-x-3">
          <div className="p-2 rounded-xl bg-indigo-50 border border-indigo-100 text-indigo-600 shadow-2xs">
            <GitFork className="w-4 h-4" />
          </div>
          <div>
            <h2 className="text-base font-bold text-slate-900 flex items-center gap-2">
              Navigation Graph & UI Tree Explorer
            </h2>
            <p className="text-xs text-slate-500 font-medium">
              Verified parent-child transitions directly from{' '}
              <code className="text-indigo-600 font-mono font-bold">nav_graph.db</code>
            </p>
          </div>
        </div>

        {/* View Mode Toggle: Nav Graph vs Live Capture vs Raw JSON */}
        <div className="flex items-center space-x-2">
          <div className="flex items-center bg-slate-100/90 rounded-xl p-1 border border-slate-200/80">
            <button
              onClick={() => setViewMode('nav_graph')}
              className={`flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${
                viewMode === 'nav_graph'
                  ? 'bg-white text-indigo-600 shadow-xs border border-slate-200/60'
                  : 'text-slate-600 hover:text-slate-900 hover:bg-white/60'
              }`}
            >
              <GitFork className="w-3.5 h-3.5" />
              <span>Nav Graph (from .db)</span>
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
              <span>Live Phone Screen</span>
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

          <button
            onClick={() => handleCopy(navGraph || events, 'full_data')}
            className="flex items-center space-x-1.5 px-3 py-1.5 rounded-xl bg-white hover:bg-slate-50 text-xs font-semibold text-slate-700 border border-slate-200/80 shadow-xs transition-all active:scale-[0.98]"
          >
            {copiedItemIdx === 'full_data' ? (
              <Check className="w-3.5 h-3.5 text-emerald-600" />
            ) : (
              <Copy className="w-3.5 h-3.5 text-slate-500" />
            )}
            <span>{copiedItemIdx === 'full_data' ? 'Copied' : 'Copy JSON'}</span>
          </button>
        </div>
      </div>

      {/* App Selector & Search Controls (Nav Graph Mode) */}
      {viewMode === 'nav_graph' && navGraph && navGraph.apps && (
        <div className="flex flex-wrap items-center justify-between gap-3 my-3">
          {/* App Selector Dropdown */}
          <div className="flex items-center space-x-2">
            <span className="text-xs text-slate-500 font-semibold">Select App:</span>
            <select
              value={selectedAppPkg}
              onChange={(e) => setSelectedAppPkg(e.target.value)}
              className="bg-white border border-slate-200/80 text-xs text-slate-800 rounded-xl px-3 py-1.5 focus:outline-none focus:border-indigo-500 font-semibold shadow-xs"
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
              <span className="text-[11px] font-mono text-slate-400 font-medium">
                {activeApp.package_name}
              </span>
            )}
          </div>

          {/* Search Filter */}
          <div className="relative min-w-[220px] max-w-xs">
            <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              type="text"
              placeholder="Search elements in graph..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full bg-white border border-slate-200/80 rounded-xl pl-9 pr-3 py-1.5 text-xs text-slate-800 placeholder-slate-400 focus:outline-none focus:border-indigo-500 shadow-xs font-medium"
            />
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
            <div className="flex items-center space-x-2 flex-wrap gap-y-1">
              <button
                onClick={() => exportEvaluationData('correct')}
                disabled={correctCount === 0}
                className={`flex items-center space-x-1.5 px-3 py-1.5 rounded-xl text-xs font-semibold border transition-all active:scale-[0.98] shadow-xs ${
                  correctCount > 0
                    ? 'bg-emerald-50 hover:bg-emerald-100 text-emerald-700 border-emerald-200'
                    : 'bg-slate-50 text-slate-400 border-slate-200 cursor-not-allowed'
                }`}
                title="Download JSON of all verified correct screens"
              >
                <Download className="w-3.5 h-3.5 text-emerald-600" />
                <span>Save Correct ({correctCount})</span>
              </button>

              <button
                onClick={() => exportEvaluationData('wrong')}
                disabled={wrongCount === 0}
                className={`flex items-center space-x-1.5 px-3 py-1.5 rounded-xl text-xs font-semibold border transition-all active:scale-[0.98] shadow-xs ${
                  wrongCount > 0
                    ? 'bg-rose-50 hover:bg-rose-100 text-rose-700 border-rose-200'
                    : 'bg-slate-50 text-slate-400 border-slate-200 cursor-not-allowed'
                }`}
                title="Download structured JSON of all wrong screens for study and analysis"
              >
                <Download className="w-3.5 h-3.5 text-rose-600" />
                <span>Export Wrong ({wrongCount})</span>
              </button>

              <button
                onClick={() => {
                  if (wrongScreensList.length > 0) {
                    setSelectedWrongScreenId(wrongScreensList[0].id);
                  }
                  setIsStudyModalOpen(true);
                }}
                disabled={wrongCount === 0}
                className={`flex items-center space-x-1.5 px-3.5 py-1.5 rounded-xl text-xs font-bold border transition-all active:scale-[0.98] shadow-sm ${
                  wrongCount > 0
                    ? 'bg-indigo-600 hover:bg-indigo-700 text-white border-indigo-600'
                    : 'bg-slate-100 text-slate-400 border-slate-200 cursor-not-allowed'
                }`}
                title="Open analysis workspace to study wrong screens and diagnosis reasons"
              >
                <AlertTriangle className="w-3.5 h-3.5" />
                <span>Study & Analyze ({wrongCount})</span>
              </button>

              {(correctCount > 0 || wrongCount > 0) && (
                <button
                  onClick={clearAllEvaluations}
                  className="p-1.5 rounded-xl hover:bg-rose-50 text-slate-400 hover:text-rose-600 border border-transparent hover:border-rose-200 transition-colors shadow-2xs"
                  title="Clear all marked evaluations"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
          </div>

          {/* Search Box */}
          <div className="relative max-w-sm">
            <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              type="text"
              placeholder="Filter live elements or screen titles..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-9 pr-3 py-1.5 text-xs text-slate-800 placeholder-slate-400 focus:outline-none focus:bg-white focus:border-indigo-500 font-semibold shadow-2xs"
            />
          </div>
        </div>
      )}

      {/* Main Content Area */}
      <div className="flex-1 overflow-y-auto rounded-2xl border border-slate-200/80 bg-white p-3.5 shadow-xs">
        {/* VIEW 1: NAV GRAPH (from nav_graph.db) */}
        {viewMode === 'nav_graph' && (
          <div>
            {!navGraph || !activeApp ? (
              <div className="flex flex-col items-center justify-center py-20 text-center text-slate-400 text-xs font-medium">
                No nav_graph loaded yet. Open apps on your phone to build the graph.
              </div>
            ) : !rootScreen ? (
              <div className="py-10 text-center text-slate-400 text-xs font-medium">
                No screens found for this app.
              </div>
            ) : (
              <div className="space-y-4">
                {/* Information banner */}
                <div className="px-4 py-2.5 rounded-xl bg-slate-50 border border-slate-200/70 flex items-center justify-between text-xs text-slate-600">
                  <div className="flex items-center space-x-2">
                    <span className="w-2 h-2 rounded-full bg-emerald-500"></span>
                    <span>
                      Navigation Tree rooted at{' '}
                      <strong className="text-slate-900 font-bold">
                        {rootScreen.title}
                      </strong>
                    </span>
                  </div>
                  <span className="text-[11px] font-mono text-slate-400">
                    Click any <span className="text-indigo-600 font-semibold">🔗 opens screen</span> link to expand sub-pages
                  </span>
                </div>

                {/* Render Root Screen with recursive transitions tree */}
                {renderNavGraphScreen(rootScreen, 1)}
              </div>
            )}
          </div>
        )}

        {/* VIEW 2: LIVE PHONE SCREEN (Current Screen Capture) */}
        {viewMode === 'live' && (
          <div>
            {liveScreens.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-20 text-center text-slate-400 text-xs font-medium">
                No UI elements captured yet. Open a screen on your phone.
              </div>
            ) : displayedLiveScreens.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-16 text-center text-slate-400 text-xs space-y-2">
                <Filter className="w-6 h-6 text-slate-300 mb-1" />
                <span>No screens match the current filter "{evalFilter}".</span>
                <button
                  onClick={() => setEvalFilter('all')}
                  className="text-indigo-600 font-semibold hover:underline text-xs"
                >
                  Reset filter to All
                </button>
              </div>
            ) : (
              <div className="space-y-3">
                {displayedLiveScreens.map((screen, sIdx) => {
                  const isScreenOpen =
                    expandedLiveScreens[screen.id] ?? (sIdx === 0);

                  const filtered = searchQuery.trim()
                    ? screen.elements.filter((e) =>
                        e.name.toLowerCase().includes(searchQuery.toLowerCase())
                      )
                    : screen.elements;

                  const evalRecord = evaluations[screen.id];

                  return (
                    <div
                      key={screen.id}
                      className={`rounded-2xl border bg-white overflow-hidden transition-all duration-200 shadow-xs hover:shadow-md ${
                        evalRecord?.status === 'correct'
                          ? 'border-emerald-300 ring-2 ring-emerald-100'
                          : evalRecord?.status === 'wrong'
                          ? 'border-rose-300 ring-2 ring-rose-100'
                          : 'border-slate-200/80'
                      }`}
                    >
                      {/* Live Screen Header (Layer 0) with Evaluation Buttons */}
                      <div
                        onClick={() => toggleLiveScreen(screen.id)}
                        className={`flex items-center justify-between px-4 py-3 bg-slate-50/70 hover:bg-slate-100/70 cursor-pointer select-none border-b border-slate-100 transition-colors ${
                          evalRecord?.status === 'correct'
                            ? 'border-l-[4px] border-l-emerald-500 bg-emerald-50/20'
                            : evalRecord?.status === 'wrong'
                            ? 'border-l-[4px] border-l-rose-500 bg-rose-50/20'
                            : ''
                        }`}
                      >
                        <div className="flex items-center space-x-2.5 flex-1 min-w-0 mr-3">
                          <span className="text-slate-400 flex-shrink-0">
                            {isScreenOpen ? (
                              <ChevronDown className="w-4 h-4 text-slate-600" />
                            ) : (
                              <ChevronRight className="w-4 h-4" />
                            )}
                          </span>
                          <FolderOpen className="w-4 h-4 text-indigo-600 flex-shrink-0" />
                          <span className="text-sm font-bold text-slate-900 truncate">
                            {screen.screenTitle}
                          </span>
                          <span className="text-[10px] px-2 py-0.5 rounded-md font-mono font-bold bg-slate-100 text-slate-600 border border-slate-200/60 flex-shrink-0">
                            Live Screen
                          </span>
                          {evalRecord?.status === 'correct' && (
                            <span className="text-[10px] px-2 py-0.5 rounded-md font-mono font-bold bg-emerald-50 text-emerald-700 border border-emerald-200 flex items-center space-x-1 flex-shrink-0">
                              <Check className="w-3 h-3 text-emerald-600" />
                              <span>Correct</span>
                            </span>
                          )}
                          {evalRecord?.status === 'wrong' && (
                            <span className="text-[10px] px-2 py-0.5 rounded-md font-mono font-bold bg-rose-50 text-rose-700 border border-rose-200 flex items-center space-x-1 flex-shrink-0">
                              <X className="w-3 h-3 text-rose-600" />
                              <span>Wrong</span>
                            </span>
                          )}
                          <span className="text-xs text-slate-400 font-mono font-medium flex-shrink-0">
                            ({screen.elements.length} elements)
                          </span>
                        </div>

                        {/* Right side: Evaluation Buttons + Package Name */}
                        <div className="flex items-center space-x-3 flex-shrink-0" onClick={(e) => e.stopPropagation()}>
                          <div className="flex items-center space-x-1.5">
                            {/* Correct Button */}
                            <button
                              onClick={() => toggleEvaluation(screen, 'correct')}
                              className={`flex items-center space-x-1 px-3 py-1 rounded-xl text-xs font-semibold transition-all active:scale-[0.98] shadow-xs ${
                                evalRecord?.status === 'correct'
                                  ? 'bg-emerald-600 text-white border border-emerald-600 shadow-sm ring-2 ring-emerald-200'
                                  : 'bg-white hover:bg-emerald-50 text-slate-600 hover:text-emerald-700 border border-slate-200/90'
                              }`}
                              title={evalRecord?.status === 'correct' ? 'Marked Correct (click to clear)' : 'Mark this screen as Correct'}
                            >
                              <Check className="w-3.5 h-3.5" />
                              <span>Correct</span>
                            </button>

                            {/* Wrong Button */}
                            <button
                              onClick={() => toggleEvaluation(screen, 'wrong')}
                              className={`flex items-center space-x-1 px-3 py-1 rounded-xl text-xs font-semibold transition-all active:scale-[0.98] shadow-xs ${
                                evalRecord?.status === 'wrong'
                                  ? 'bg-rose-600 text-white border border-rose-600 shadow-sm ring-2 ring-rose-200'
                                  : 'bg-white hover:bg-rose-50 text-slate-600 hover:text-rose-700 border border-slate-200/90'
                              }`}
                              title={evalRecord?.status === 'wrong' ? 'Marked Wrong (click to clear)' : 'Mark this screen as Wrong for study'}
                            >
                              <X className="w-3.5 h-3.5" />
                              <span>Wrong</span>
                            </button>

                            {/* Analyze button when marked Wrong */}
                            {evalRecord?.status === 'wrong' && (
                              <button
                                onClick={() => {
                                  setSelectedWrongScreenId(screen.id);
                                  setIsStudyModalOpen(true);
                                }}
                                className="flex items-center space-x-1 px-2.5 py-1 rounded-xl text-xs font-semibold bg-amber-50 text-amber-700 border border-amber-200 hover:bg-amber-100/80 transition-all active:scale-[0.98] shadow-xs"
                                title="Study & Add Analysis Notes"
                              >
                                <AlertTriangle className="w-3 h-3 text-amber-600" />
                                <span>{evalRecord.notes ? 'Notes ✓' : 'Analyze'}</span>
                              </button>
                            )}
                          </div>

                          <span className="text-[11px] font-mono text-slate-400 font-medium pl-1">
                            {screen.packageName}
                          </span>
                        </div>
                      </div>

                      {/* Live Elements List */}
                      {isScreenOpen && (
                        <div className="divide-y divide-slate-100">
                          {filtered.map((el, eIdx) => {
                            const isClickable = el.is_clickable === true;
                            return (
                              <div
                                key={`${screen.id}_${eIdx}`}
                                className="flex items-center justify-between px-6 py-2.5 bg-white hover:bg-indigo-50/30 transition-colors border-l-[3px] border-indigo-500/70"
                              >
                                <div className="flex items-center space-x-2.5">
                                  <span className="text-slate-300 font-mono text-[11px]">
                                    └─
                                  </span>
                                  <span className="text-xs font-semibold text-slate-800 truncate">
                                    {el.name}
                                  </span>
                                </div>

                                <div className="flex items-center space-x-2.5">
                                  <span
                                    className={`text-[10px] px-2 py-0.5 rounded-md font-semibold ${
                                      isClickable
                                        ? 'text-emerald-700 bg-emerald-50 border border-emerald-100'
                                        : 'text-slate-500 bg-slate-100 border border-slate-200/60'
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
                                    className="text-slate-400 hover:text-slate-700 p-1 rounded-lg hover:bg-slate-100 transition-colors"
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
          <pre className="text-slate-800 font-mono text-xs leading-relaxed overflow-x-auto p-4 bg-slate-50 rounded-xl border border-slate-200">
            {JSON.stringify(navGraph || events, null, 2)}
          </pre>
        )}
      </div>

      {/* Info Modal for Single Element */}
      {selectedElement && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 backdrop-blur-xs p-4">
          <div className="bg-white border border-slate-200 rounded-2xl max-w-md w-full p-5 shadow-2xl space-y-4">
            <div className="flex items-center justify-between pb-3 border-b border-slate-100">
              <div>
                <h3 className="text-sm font-bold text-slate-900">
                  {selectedElement.element.name}
                </h3>
                <span className="text-xs text-slate-500 font-medium">
                  {selectedElement.screenTitle}
                </span>
              </div>
              <button
                onClick={() => setSelectedElement(null)}
                className="text-slate-400 hover:text-slate-600 p-1 rounded-lg hover:bg-slate-100 transition-colors"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="space-y-3 font-mono text-xs text-slate-700">
              <pre className="p-3 bg-slate-50 border border-slate-200 rounded-xl overflow-x-auto text-[11px] text-slate-800">
                {JSON.stringify(selectedElement.element, null, 2)}
              </pre>
            </div>

            <div className="flex items-center justify-end space-x-2 pt-2">
              <button
                onClick={() =>
                  handleCopy(selectedElement.element, 'single_element')
                }
                className="flex items-center space-x-1.5 px-3 py-1.5 rounded-xl bg-slate-100 hover:bg-slate-200 text-xs font-semibold text-slate-700 transition-colors"
              >
                {copiedItemIdx === 'single_element' ? (
                  <Check className="w-3.5 h-3.5 text-emerald-600" />
                ) : (
                  <Copy className="w-3.5 h-3.5 text-slate-500" />
                )}
                <span>
                  {copiedItemIdx === 'single_element' ? 'Copied' : 'Copy JSON'}
                </span>
              </button>
              <button
                onClick={() => setSelectedElement(null)}
                className="px-3.5 py-1.5 rounded-xl bg-slate-100 hover:bg-slate-200 text-xs font-semibold text-slate-600 transition-colors"
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
            <div className="flex items-center justify-between px-6 py-4 border-b border-slate-200/80 bg-slate-50/70">
              <div className="flex items-center space-x-3">
                <div className="p-2 rounded-xl bg-rose-50 text-rose-600 border border-rose-100 shadow-2xs">
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
                    Analyze screens marked as wrong, diagnose extraction errors, tag failure patterns, and export for debugging.
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
                            <span key={t} className="text-[9px] px-1.5 py-0.5 rounded-md font-semibold bg-amber-50 text-amber-700 border border-amber-200">
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
                      <div className="py-20 text-center text-xs text-slate-400 font-medium">
                        No wrong screen selected.
                      </div>
                    );
                  }
                  const rec = evaluations[activeWrongScreen.id];
                  const DIAGNOSIS_TAGS = [
                    'Wrong Title Extracted',
                    'Dynamic Status Noise',
                    'Sub-Page Confusion',
                    'Backdrop / Scrim Leak',
                    'Activity Name Leak',
                    'Missing Elements',
                    'Duplicate Title'
                  ];

                  return (
                    <div className="space-y-4">
                      {/* Active Screen Info Header */}
                      <div className="p-3.5 rounded-xl bg-slate-50 border border-slate-200 space-y-2">
                        <div className="flex items-center justify-between">
                          <span className="text-sm font-bold text-rose-700">
                            {activeWrongScreen.screenTitle}
                          </span>
                          <span className="text-[11px] font-mono px-2 py-0.5 rounded-md bg-white text-slate-600 border border-slate-200 font-semibold shadow-2xs">
                            {activeWrongScreen.packageName}
                          </span>
                        </div>
                        <div className="text-xs text-slate-500 font-mono">
                          Elements Captured: <span className="text-slate-900 font-bold">{activeWrongScreen.elements.length}</span> | ID: <span className="text-slate-400">{activeWrongScreen.id}</span>
                        </div>
                      </div>

                      {/* Diagnostic Tags */}
                      <div className="space-y-1.5">
                        <label className="text-xs font-bold text-slate-700 flex items-center space-x-1.5">
                          <Tag className="w-3.5 h-3.5 text-amber-600" />
                          <span>Diagnostic Failure Tags (Click to toggle):</span>
                        </label>
                        <div className="flex flex-wrap gap-1.5">
                          {DIAGNOSIS_TAGS.map((t) => {
                            const isTagged = rec?.tags?.includes(t);
                            return (
                              <button
                                key={t}
                                onClick={() => toggleWrongScreenTag(activeWrongScreen.id, t)}
                                className={`text-[11px] px-3 py-1 rounded-full font-semibold transition-all border shadow-2xs ${
                                  isTagged
                                    ? 'bg-amber-100 text-amber-800 border-amber-300 shadow-xs'
                                    : 'bg-slate-50 text-slate-600 border-slate-200 hover:bg-slate-100'
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
                          placeholder="Describe what was wrong: e.g. 'Applying, Style1 is a wallpaper preview screen from com.bbk.theme that should have been titled Wallpapers'..."
                          className="w-full bg-slate-50 border border-slate-200 rounded-xl p-3 text-xs text-slate-800 placeholder-slate-400 focus:outline-none focus:bg-white focus:border-indigo-500 font-medium shadow-2xs"
                        />
                      </div>

                      {/* Elements extracted preview */}
                      <div className="space-y-1.5">
                        <label className="text-xs font-bold text-slate-700">
                          Captured Elements ({activeWrongScreen.elements.length}):
                        </label>
                        <div className="max-h-48 overflow-y-auto bg-slate-50 border border-slate-200 rounded-xl divide-y divide-slate-200/70 p-1">
                          {activeWrongScreen.elements.map((el, i) => (
                            <div key={i} className="flex items-center justify-between px-3 py-1.5 text-xs hover:bg-white rounded-lg transition-colors">
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
                            className="flex items-center space-x-1 px-3 py-1.5 rounded-xl bg-emerald-50 hover:bg-emerald-100 text-emerald-700 border border-emerald-200 text-xs font-semibold transition-all active:scale-[0.98] shadow-xs"
                          >
                            <Check className="w-3.5 h-3.5" />
                            <span>Mark Correct instead</span>
                          </button>
                          <button
                            onClick={() => toggleEvaluation(activeWrongScreen, 'wrong')}
                            className="px-3 py-1.5 rounded-xl bg-slate-100 hover:bg-slate-200 text-slate-600 border border-slate-200 text-xs font-semibold transition-all active:scale-[0.98]"
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
            <div className="flex items-center justify-between px-6 py-3.5 border-t border-slate-200/80 bg-slate-50/70 text-xs">
              <span className="text-slate-500 font-mono font-medium">
                Notes & evaluations are auto-saved in your browser.
              </span>
              <div className="flex items-center space-x-2">
                <button
                  onClick={() => exportEvaluationData('wrong')}
                  className="flex items-center space-x-1.5 px-3.5 py-1.5 rounded-xl bg-rose-50 hover:bg-rose-100 text-rose-700 border border-rose-200 font-semibold transition-all active:scale-[0.98] shadow-xs"
                >
                  <Download className="w-3.5 h-3.5 text-rose-600" />
                  <span>Download Wrong Analysis JSON</span>
                </button>
                <button
                  onClick={() => setIsStudyModalOpen(false)}
                  className="px-4 py-1.5 rounded-xl bg-slate-200 hover:bg-slate-300 text-slate-700 font-semibold transition-colors shadow-xs"
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
