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

const getLayerStyle = (layer: number) => {
  switch (layer) {
    case 0:
      return {
        bg: 'bg-[#1e2433]',
        hover: 'hover:bg-[#252c3e]',
        borderLeft: 'border-l-[4px] border-emerald-400',
        badgeBg: 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/30',
        paddingLeft: 'pl-4',
        layerName: 'Screen'
      };
    case 1:
      return {
        bg: 'bg-[#151922]',
        hover: 'hover:bg-[#1c222e]',
        borderLeft: 'border-l-[3px] border-emerald-500/60',
        badgeBg: 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20',
        paddingLeft: 'pl-6',
        layerName: 'L1'
      };
    case 2:
      return {
        bg: 'bg-[#10131b]',
        hover: 'hover:bg-[#161b25]',
        borderLeft: 'border-l-[3px] border-sky-500/50',
        badgeBg: 'bg-sky-500/10 text-sky-400 border border-sky-500/20',
        paddingLeft: 'pl-11',
        layerName: 'L2'
      };
    case 3:
      return {
        bg: 'bg-[#0b0d13]',
        hover: 'hover:bg-[#11141c]',
        borderLeft: 'border-l-[3px] border-indigo-500/40',
        badgeBg: 'bg-indigo-500/10 text-indigo-400 border border-indigo-500/20',
        paddingLeft: 'pl-16',
        layerName: 'L3'
      };
    default:
      return {
        bg: 'bg-[#07080c]',
        hover: 'hover:bg-[#0c0e14]',
        borderLeft: 'border-l-[3px] border-purple-500/40',
        badgeBg: 'bg-purple-500/10 text-purple-400 border border-purple-500/20',
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
    // 1. Screen matching app name (e.g. "Settings" or "Chats")
    const match = activeApp.screens.find(
      (s) =>
        s.title.toLowerCase() === activeApp.display_name.toLowerCase() ||
        s.title.toLowerCase() === 'settings' ||
        s.title.toLowerCase() === 'chats'
    );
    if (match) return match;
    // 2. Highest visit count screen
    const sorted = [...activeApp.screens].sort(
      (a, b) => (b.visit_count || 0) - (a.visit_count || 0)
    );
    return sorted[0];
  }, [activeApp]);

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

    // Filter elements: drop screen title header, search box, and structural containers (bottom bar, etc.), and deduplicate by base label
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

    // Filter elements if search is active
    const q = searchQuery.trim().toLowerCase();
    const visibleElements = q
      ? sanitizedElements.filter((el) => el.toLowerCase().includes(q))
      : sanitizedElements;

    return (
      <div key={screen.id} className="space-y-1">
        {/* Screen Header */}
        <div
          className={`flex items-center justify-between px-4 py-2 rounded-lg ${style.bg} ${style.borderLeft} border-b border-dark-800/40 select-none`}
        >
          <div className="flex items-center space-x-2.5">
            <FolderOpen className="w-4 h-4 text-emerald-400" />
            <span className="text-xs font-semibold text-gray-100">
              {screen.title}
            </span>
            <span
              className={`text-[9px] px-1.5 py-0.2 rounded font-mono font-semibold ${style.badgeBg}`}
            >
              Layer {depth}
            </span>
            <span className="text-[11px] text-gray-400 font-mono">
              ({screen.elements.length} elements, {screen.transitions.length} links)
            </span>
          </div>

          <span className="text-[10px] font-mono text-gray-500">
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
                // Check if this element triggers a forward transition to another screen!
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

                const hasTargetScreen =
                  transition && screensById.has(transition.to_id);
                const targetScreen = hasTargetScreen
                  ? screensById.get(transition.to_id)
                  : undefined;

                const transKey = `${screen.id}_to_${elemName}_${eIdx}`;
                const isExpanded = expandedTransitions[transKey] === true;

                return (
                  <div key={`${screen.id}_elem_${eIdx}`} className="py-0.5">
                    {/* Element Row */}
                    <div
                      onClick={() => {
                        if (hasTargetScreen) {
                          toggleTransition(transKey);
                        }
                      }}
                      className={`flex items-center justify-between pr-4 py-1.5 ${style.paddingLeft} ${
                        hasTargetScreen ? 'cursor-pointer hover:bg-sky-500/10' : 'hover:bg-dark-800/40'
                      } rounded transition-colors select-none`}
                    >
                      {/* Left: Indicator, Name, Link Badge */}
                      <div className="flex items-center space-x-2 min-w-0 mr-3">
                        {hasTargetScreen ? (
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              toggleTransition(transKey);
                            }}
                            className="p-0.5 rounded text-sky-400 hover:text-sky-200 hover:bg-sky-500/10 transition-colors shrink-0"
                            title={`Click to expand child screen: ${targetScreen?.title}`}
                          >
                            {isExpanded ? (
                              <ChevronDown className="w-3.5 h-3.5" />
                            ) : (
                              <ChevronRight className="w-3.5 h-3.5" />
                            )}
                          </button>
                        ) : (
                          <span className="text-gray-600 font-mono text-[11px] select-none shrink-0 w-3.5 text-center">
                            └─
                          </span>
                        )}

                        {/* Name */}
                        <span
                          className={`text-xs font-medium truncate ${
                            hasTargetScreen
                              ? 'text-sky-300 font-semibold'
                              : 'text-gray-200'
                          }`}
                        >
                          {elemName}
                        </span>

                        {/* Transition Link Badge */}
                        {hasTargetScreen && targetScreen && (
                          <span className="inline-flex items-center space-x-1 px-1.5 py-0.2 rounded text-[10px] font-mono bg-sky-500/10 text-sky-300 border border-sky-500/30">
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
                              ? 'text-sky-400 bg-sky-500/10'
                              : 'text-emerald-400 bg-emerald-500/10'
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

                    {/* Child Screen (Expanded recursively under this link!) */}
                    {hasTargetScreen && targetScreen && isExpanded && (
                      <div className="mt-1 mb-2 ml-4 border-l-2 border-sky-500/30 pl-2">
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

      {/* Live Mode Search */}
      {viewMode === 'live' && liveScreens.length > 0 && (
        <div className="my-3 max-w-xs relative">
          <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-gray-500" />
          <input
            type="text"
            placeholder="Filter live elements..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full bg-dark-800 border border-dark-700 rounded-lg pl-9 pr-3 py-1 text-xs text-gray-200 placeholder-gray-500 focus:outline-none focus:border-gray-500"
          />
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
                  <span className="text-[11px] font-mono text-gray-500">
                    Click any <span className="text-sky-300">🔗 opens screen</span> link to expand sub-pages
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
              <div className="flex flex-col items-center justify-center py-20 text-center text-gray-500 text-xs">
                No UI elements captured yet. Open a screen on your phone.
              </div>
            ) : (
              <div className="space-y-3">
                {liveScreens.map((screen, sIdx) => {
                  const isScreenOpen =
                    expandedLiveScreens[screen.id] ?? (sIdx === 0);

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
                      <div
                        onClick={() => toggleLiveScreen(screen.id)}
                        className="flex items-center justify-between px-4 py-2.5 bg-[#1e2433] hover:bg-[#252c3e] cursor-pointer select-none border-b border-dark-800/80 transition-colors"
                      >
                        <div className="flex items-center space-x-2.5">
                          <span className="text-gray-400">
                            {isScreenOpen ? (
                              <ChevronDown className="w-4 h-4 text-gray-200" />
                            ) : (
                              <ChevronRight className="w-4 h-4" />
                            )}
                          </span>
                          <FolderOpen className="w-4 h-4 text-emerald-400" />
                          <span className="text-sm font-semibold text-gray-100">
                            {screen.screenTitle}
                          </span>
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
