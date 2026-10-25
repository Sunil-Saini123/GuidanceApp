import React, { useState, useMemo, useEffect, useRef } from 'react';
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
  Filter,
  Maximize,
  Minimize,
  MessageSquare
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

export type ElementEvaluationStatus = 'right' | 'wrong';

export interface ElementEvaluationRecord {
  id: string; // `${screenId}::${elementName}`
  screenId: string;
  elementName: string;
  status?: ElementEvaluationStatus;
  comment?: string;
  updatedAt: number;
}

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

// [PERMANENT FIX] Do not remove this! Code-editor nested tree indentation and vibrant color palette.
// [PERMANENT FIX] Do not remove this! High-contrast vibrant color palette, distinct element role badges, and sticky note styling for maximum readability.
const getLayerStyle = (layer: number) => {
  switch (layer) {
    case 1:
      return {
        bg: 'bg-gradient-to-r from-indigo-50 via-blue-50/60 to-white',
        borderLeft: 'border-l-4 border-indigo-600',
        badgeBg: 'bg-indigo-600 text-white shadow-xs',
        borderColor: 'border-indigo-300',
        titleColor: 'text-indigo-950',
        paddingLeft: 'pl-4'
      };
    case 2:
      return {
        bg: 'bg-gradient-to-r from-sky-50 via-cyan-50/60 to-white',
        borderLeft: 'border-l-4 border-sky-600',
        badgeBg: 'bg-sky-600 text-white shadow-xs',
        borderColor: 'border-sky-300',
        titleColor: 'text-sky-950',
        paddingLeft: 'pl-8'
      };
    case 3:
      return {
        bg: 'bg-gradient-to-r from-emerald-50 via-teal-50/60 to-white',
        borderLeft: 'border-l-4 border-emerald-600',
        badgeBg: 'bg-emerald-600 text-white shadow-xs',
        borderColor: 'border-emerald-300',
        titleColor: 'text-emerald-950',
        paddingLeft: 'pl-12'
      };
    case 4:
      return {
        bg: 'bg-gradient-to-r from-amber-50 via-yellow-50/60 to-white',
        borderLeft: 'border-l-4 border-amber-600',
        badgeBg: 'bg-amber-600 text-white shadow-xs',
        borderColor: 'border-amber-300',
        titleColor: 'text-amber-950',
        paddingLeft: 'pl-16'
      };
    default:
      return {
        bg: 'bg-gradient-to-r from-purple-50 via-fuchsia-50/60 to-white',
        borderLeft: 'border-l-4 border-purple-600',
        badgeBg: 'bg-purple-600 text-white shadow-xs',
        borderColor: 'border-purple-300',
        titleColor: 'text-purple-950',
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
  const searchInputRef = useRef<HTMLInputElement>(null);

  // Fullscreen toggle state
  const [isFullscreen, setIsFullscreen] = useState(false);

  // Text size switcher: 'normal' (14px) or 'large' (16px) - defaults to 'large'
  const [textSize, setTextSize] = useState<'normal' | 'large'>('large');

  // Currently open comment box for an element: `${screenId}::${elemName}`
  const [openCommentElemId, setOpenCommentElemId] = useState<string | null>(null);

  // [PERMANENT FIX] Do not remove this! Per-element Right/Wrong quality evaluation and persistent comment storage in localStorage.
  const [elementEvaluations, setElementEvaluations] = useState<Record<string, ElementEvaluationRecord>>(() => {
    try {
      const saved = localStorage.getItem('guidance_element_evaluations');
      return saved ? JSON.parse(saved) : {};
    } catch (_) {
      return {};
    }
  });

  // Save element evaluations permanently to localStorage on every change (NEVER ERASED!)
  useEffect(() => {
    try {
      localStorage.setItem('guidance_element_evaluations', JSON.stringify(elementEvaluations));
    } catch (_) {}
  }, [elementEvaluations]);

  const toggleElementStatus = (screenId: string, elementName: string, targetStatus: ElementEvaluationStatus) => {
    const key = `${screenId}::${elementName}`;
    setElementEvaluations((prev) => {
      const existing = prev[key];
      if (existing?.status === targetStatus) {
        // Toggle off status, but keep comments intact!
        return {
          ...prev,
          [key]: {
            ...existing,
            status: undefined,
            updatedAt: Date.now()
          }
        };
      }
      return {
        ...prev,
        [key]: {
          id: key,
          screenId,
          elementName,
          status: targetStatus,
          comment: existing?.comment || '',
          updatedAt: Date.now()
        }
      };
    });
  };

  const updateElementComment = (screenId: string, elementName: string, comment: string) => {
    const key = `${screenId}::${elementName}`;
    setElementEvaluations((prev) => {
      const existing = prev[key];
      return {
        ...prev,
        [key]: {
          id: key,
          screenId,
          elementName,
          status: existing?.status,
          comment,
          updatedAt: Date.now()
        }
      };
    });
  };

  // [PERMANENT FIX] Do not remove this! Declare selectedElement state before keyboard event listeners to prevent TDZ ReferenceError.
  const [selectedElement, setSelectedElement] = useState<{
    element: TreeElementItem;
    screenTitle: string;
    packageName: string;
  } | null>(null);

  // Element type filter in Live mode: 'all' | 'clickable' | 'scrollable'
  const [elementFilter, setElementFilter] = useState<'all' | 'clickable' | 'scrollable'>('all');

  // Search text yellow highlight helper
  const highlightMatch = (content: string, query: string) => {
    if (!query.trim()) return content;
    const q = query.trim().toLowerCase();
    const idx = content.toLowerCase().indexOf(q);
    if (idx === -1) return content;
    return (
      <>
        {content.slice(0, idx)}
        <mark className="bg-amber-300 text-amber-950 px-1 py-0.5 rounded-md font-black shadow-xs">
          {content.slice(idx, idx + q.length)}
        </mark>
        {content.slice(idx + q.length)}
      </>
    );
  };

  // ── Screen Evaluation & Quality Labeling State ─────────────────────────────
  const [evaluations, setEvaluations] = useState<Record<string, ScreenEvaluationRecord>>(() => {
    try {
      const saved = localStorage.getItem('guidance_screen_evaluations');
      return saved ? JSON.parse(saved) : {};
    } catch (_) {
      return {};
    }
  });

  // [PERMANENT FIX] Do not remove this! Keyboard shortcuts: Esc closes side drawer, clears search, or exits tree fullscreen.
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        if (selectedElement) {
          setSelectedElement(null);
        } else if (searchQuery) {
          setSearchQuery('');
        } else if (isFullscreen) {
          setIsFullscreen(false);
        }
      } else if (
        (e.key === '/' || (e.ctrlKey && e.key === 'k')) &&
        document.activeElement?.tagName !== 'INPUT' &&
        document.activeElement?.tagName !== 'TEXTAREA'
      ) {
        e.preventDefault();
        searchInputRef.current?.focus();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [selectedElement, searchQuery, isFullscreen]);

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



  // [PERMANENT FIX] Do not remove this! Cumulative Live UI Element Capture & Scroll Merging.
  // Instead of dropping subsequent telemetry events for an existing screen, merge newly scrolled elements
  // into the screen group. Ensures sub-screens like WhatsApp Settings show all elements (Chats, Appearance, etc.)
  const liveScreens: ScreenGroup[] = useMemo(() => {
    const screensMap = new Map<string, ScreenGroup>();

    (events || []).forEach((ev) => {
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
      for (const screen of app.screens || []) {
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
      clusters.set(app.package_name, (app.screens || []).length);
    }

    const primary: NavGraphApp[] = [];
    const satellite: NavGraphApp[] = [];

    for (const app of navGraph.apps) {
      const isSatellite =
        app.package_name.startsWith('com.oplus.') ||
        app.package_name.startsWith('com.coloros.') ||
        app.package_name.startsWith('com.android.settings.') ||
        ((app.screens || []).length <= 2 && app.package_name.includes('settings'));

      if (isSatellite && (app.screens || []).length <= 5) {
        satellite.push(app);
      } else {
        primary.push(app);
      }
    }

    return { primaryApps: primary, satelliteApps: satellite, clusterCountMap: clusters };
  }, [navGraph]);

  // [PERMANENT FIX] Do not remove this! Screen-level transition resolution and null-safe array traversal to prevent undefined forEach crashes.
  const totalTransitionsCount = useMemo(() => {
    if (!activeApp || !activeApp.screens) return 0;
    return (activeApp.screens || []).reduce((acc, s) => acc + (s.transitions?.length || 0), 0);
  }, [activeApp]);

  // [PERMANENT FIX] Do not remove this! App-Aware Root Resolution & Topological In-Degree Root Detection.
  const rootScreen = useMemo(() => {
    if (!activeApp || !activeApp.screens || activeApp.screens.length === 0) return undefined;

    const inDegree = new Map<string, number>();
    (activeApp.screens || []).forEach((s) => inDegree.set(s.id, 0));

    (activeApp.screens || []).forEach((s) => {
      (s.transitions || []).forEach((t) => {
        if (t.type === 'CLICK' && inDegree.has(t.to_id)) {
          inDegree.set(t.to_id, (inDegree.get(t.to_id) || 0) + 1);
        }
      });
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

    (activeApp.screens || []).forEach((parent) => {
      (parent.elements || []).forEach((elem, eIdx) => {
        const transKey = `${parent.id}_to_${elem}_${eIdx}`;
        const hasTransition = (parent.transitions || []).some(
          (t) => t.to_id === targetScreen.id
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

  // Graph health & quality statistics for NavGraph mode
  const navGraphStats = useMemo(() => {
    if (!activeApp) return null;
    const totalScreens = (activeApp.screens || []).length;
    let correct = 0;
    let wrong = 0;
    for (const s of (activeApp.screens || [])) {
      const st = evaluations[s.id]?.status;
      if (st === 'correct') correct++;
      else if (st === 'wrong') wrong++;
    }
    const unreviewed = Math.max(0, totalScreens - correct - wrong);
    return { totalScreens, correct, wrong, unreviewed };
  }, [activeApp, evaluations]);

  // Search match counter for visual feedback
  const searchMatchInfo = useMemo(() => {
    if (!searchQuery.trim()) return null;
    const q = searchQuery.trim().toLowerCase();
    if (viewMode === 'nav_graph' && activeApp) {
      let matchCount = 0;
      let screenCount = 0;
      for (const s of (activeApp.screens || [])) {
        const hits = (s.elements || []).filter((el) => el.toLowerCase().includes(q)).length;
        if (hits > 0) {
          matchCount += hits;
          screenCount++;
        }
      }
      return { matchCount, screenCount };
    }
    if (viewMode === 'live') {
      let matchCount = 0;
      let screenCount = 0;
      for (const s of liveScreens) {
        const hits = s.elements.filter((el) => el.name.toLowerCase().includes(q)).length;
        if (hits > 0) {
          matchCount += hits;
          screenCount++;
        }
      }
      return { matchCount, screenCount };
    }
    return null;
  }, [searchQuery, viewMode, activeApp, liveScreens]);

  // Recursive NavGraph Screen Renderer (True Multi-Screen Tree!)
  const renderNavGraphScreen = (
    screen: NavGraphScreen,
    depth: number = 1,
    visitedScreens: Set<string> = new Set(),
    trail: string[] = []
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
    const combinedElements = [...(screen.elements || [])];
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
      <div key={screen.id} className="space-y-2">
        {/* Interactive Breadcrumb Trail on Sub-Screens */}
        {trail.length > 0 && (
          <div className={`flex items-center space-x-1.5 text-[11px] font-mono text-slate-500 ${style.paddingLeft} pt-1 select-none flex-wrap`}>
            {trail.map((ancestor, aIdx) => (
              <React.Fragment key={`trail_${ancestor}_${aIdx}`}>
                <span className="text-slate-500 font-medium">{ancestor}</span>
                <span className="text-indigo-400 font-bold">›</span>
              </React.Fragment>
            ))}
            <span className="font-bold text-slate-800">{screen.title}</span>
          </div>
        )}

        {/* Screen Header - [PERMANENT FIX] High-contrast glowing live location marker box & vibrant color themes */}
        <div
          className={`flex items-center justify-between px-4 py-3 rounded-2xl select-none transition-all duration-200 border-2 ${
            isCurrentLiveScreen
              ? 'bg-gradient-to-r from-emerald-100 via-teal-50 to-emerald-50 border-emerald-500 shadow-md ring-2 ring-emerald-400/30'
              : `${style.bg} ${style.borderLeft} ${style.borderColor} shadow-xs`
          } ${
            evalRecord?.status === 'correct'
              ? 'ring-2 ring-emerald-400/50 border-emerald-400'
              : evalRecord?.status === 'wrong'
              ? 'ring-2 ring-rose-400/50 border-rose-400'
              : ''
          }`}
        >
          <div className="flex items-center space-x-2.5">
            <div className={`p-1.5 rounded-xl ${isCurrentLiveScreen ? 'bg-emerald-600 text-white animate-pulse' : 'bg-indigo-600 text-white'} shadow-xs`}>
              <FolderOpen className="w-4 h-4" />
            </div>
            <span className={`${textSize === 'large' ? 'text-base font-black' : 'text-sm font-extrabold'} ${isCurrentLiveScreen ? 'text-emerald-950 font-black' : style.titleColor}`}>
              {screen.title}
            </span>
            {isCurrentLiveScreen && (
              <span className="text-[10px] px-2.5 py-0.5 rounded-md font-mono font-bold bg-emerald-600 text-white animate-pulse shadow-sm flex items-center space-x-1">
                <span>📍 ACTIVE PHONE SCREEN</span>
              </span>
            )}
            <span
              className={`text-[10px] px-2.5 py-0.5 rounded-md font-mono font-bold ${style.badgeBg}`}
            >
              Layer {depth}
            </span>
            {evalRecord?.status === 'correct' && (
              <span className="text-[10px] px-2.5 py-0.5 rounded-md font-mono font-bold bg-emerald-100 text-emerald-800 border border-emerald-300 flex items-center space-x-1 shadow-2xs">
                <Check className="w-3 h-3 text-emerald-700 stroke-[3]" />
                <span>Correct</span>
              </span>
            )}
            {evalRecord?.status === 'wrong' && (
              <span className="text-[10px] px-2.5 py-0.5 rounded-md font-mono font-bold bg-rose-100 text-rose-800 border border-rose-300 flex items-center space-x-1 shadow-2xs">
                <X className="w-3 h-3 text-rose-700 stroke-[3]" />
                <span>Wrong</span>
              </span>
            )}
            <span className="text-[11px] font-mono font-bold text-slate-700 bg-white/90 px-2 py-0.5 rounded-md border border-slate-200 shadow-2xs">
              {(screen.elements || []).length} elements · {(screen.transitions || []).length} links
            </span>
          </div>

          <div className="flex items-center space-x-2.5" onClick={(e) => e.stopPropagation()}>
            {/* Correct & Wrong buttons for Nav Graph screens */}
            <div className="flex items-center space-x-1">
              <button
                onClick={() => toggleNavGraphEvaluation(screen, 'correct')}
                className={`flex items-center space-x-1.5 px-3 py-1 rounded-xl text-xs font-bold transition-all active:scale-[0.98] ${
                  evalRecord?.status === 'correct'
                    ? 'bg-emerald-600 text-white border border-emerald-600 shadow-sm'
                    : 'bg-white hover:bg-emerald-50 text-emerald-700 border border-emerald-200 shadow-2xs'
                }`}
                title="Mark this screen as Correct"
              >
                <Check className="w-3.5 h-3.5 stroke-[2.5]" />
                <span>Correct</span>
              </button>

              <button
                onClick={() => toggleNavGraphEvaluation(screen, 'wrong')}
                className={`flex items-center space-x-1.5 px-3 py-1 rounded-xl text-xs font-bold transition-all active:scale-[0.98] ${
                  evalRecord?.status === 'wrong'
                    ? 'bg-rose-600 text-white border border-rose-600 shadow-sm'
                    : 'bg-white hover:bg-rose-50 text-rose-700 border border-rose-200 shadow-2xs'
                }`}
                title="Mark this screen as Wrong for study"
              >
                <X className="w-3.5 h-3.5 stroke-[2.5]" />
                <span>Wrong</span>
              </button>
            </div>

            <span className="text-[10px] font-mono font-bold text-slate-700 bg-white/90 px-2 py-0.5 rounded-md border border-slate-200">
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
                const transition = (screen.transitions || []).find((t) => {
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

                const elemEvalKey = `${screen.id}::${elemName}`;
                const elemEval = elementEvaluations[elemEvalKey];
                const isRight = elemEval?.status === 'right';
                const isWrong = elemEval?.status === 'wrong';

                return (
                  <div key={`${screen.id}_elem_${eIdx}`} className="py-0.5 first:pt-1">
                    {/* Element Row - [PERMANENT FIX] High-contrast colorful target step marker box & status tinting */}
                    <div
                      onClick={() => {
                        if (hasTargetScreen) {
                          toggleTransition(transKey);
                        }
                      }}
                      className={`flex items-center justify-between pr-4 py-2.5 ${style.paddingLeft} rounded-xl select-none transition-all ${
                        isTargetStep
                          ? 'bg-gradient-to-r from-amber-50 via-amber-100/60 to-orange-50 border-2 border-amber-500 shadow-md ring-2 ring-amber-400/20'
                          : isRight
                          ? 'bg-emerald-50/70 hover:bg-emerald-100/60 border border-emerald-300 text-emerald-950 shadow-2xs'
                          : isWrong
                          ? 'bg-rose-50/70 hover:bg-rose-100/60 border border-rose-300 text-rose-950 shadow-2xs'
                          : hasTargetScreen
                          ? 'bg-indigo-50/40 hover:bg-indigo-100/50 border border-indigo-200/70 cursor-pointer shadow-2xs'
                          : 'bg-white hover:bg-slate-50/90 border border-slate-200 shadow-2xs'
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
                            className="p-1 rounded-md hover:bg-indigo-100/80 text-indigo-600 transition-colors"
                          >
                            {isExpanded ? (
                              <ChevronDown className="w-3.5 h-3.5 text-indigo-700 stroke-[2.5]" />
                            ) : (
                              <ChevronRight className="w-3.5 h-3.5 text-indigo-500 stroke-[2.5]" />
                            )}
                          </button>
                        ) : (
                          <span className="text-indigo-400 font-mono font-bold text-[13px] select-none shrink-0 w-5 text-center">
                            {isLastElem ? '└' : '├'}
                          </span>
                        )}

                        <span
                          className={`${textSize === 'large' ? 'text-[15px] font-bold' : 'text-sm font-semibold'} truncate ${
                            isTargetStep
                              ? 'text-amber-950 font-black'
                              : isRight
                              ? 'text-emerald-950 font-bold'
                              : isWrong
                              ? 'text-rose-950 font-bold line-through decoration-rose-400'
                              : hasTargetScreen
                              ? 'text-indigo-900 font-bold hover:underline'
                              : 'text-slate-900'
                          }`}
                        >
                          {highlightMatch(elemName, searchQuery)}
                        </span>

                        {isTargetStep && (
                          <span className="text-[10px] px-2 py-0.5 rounded-md font-mono font-bold bg-amber-500 text-white animate-pulse shadow-sm flex items-center space-x-1 shrink-0">
                            <span>🎯 NEXT TARGET STEP</span>
                          </span>
                        )}

                        {hasTargetScreen && (
                          <span className="text-[11px] px-2.5 py-0.5 rounded-lg bg-sky-100 text-sky-800 border border-sky-300 font-mono font-bold shrink-0 flex items-center space-x-1 shadow-2xs">
                            <span>→ {targetScreen?.title}</span>
                          </span>
                        )}
                      </div>

                      {/* Right: Per-Element Evaluation (Right, Wrong, Comment) & Type */}
                      <div className="flex items-center space-x-2 shrink-0" onClick={(e) => e.stopPropagation()}>
                        {/* Per-Element Right / Wrong Evaluation Buttons */}
                        <div className="flex items-center space-x-1">
                          <button
                            onClick={() => toggleElementStatus(screen.id, elemName, 'right')}
                            className={`flex items-center space-x-1 px-2.5 py-1 rounded-lg text-xs font-bold transition-all active:scale-[0.98] ${
                              isRight
                                ? 'bg-emerald-600 text-white border border-emerald-600 shadow-sm'
                                : 'bg-white hover:bg-emerald-50 text-emerald-700 border border-emerald-200 hover:border-emerald-300 shadow-2xs'
                            }`}
                            title="Mark element as Right"
                          >
                            <Check className="w-3.5 h-3.5 stroke-[2.5]" />
                            <span>Right</span>
                          </button>

                          <button
                            onClick={() => toggleElementStatus(screen.id, elemName, 'wrong')}
                            className={`flex items-center space-x-1 px-2.5 py-1 rounded-lg text-xs font-bold transition-all active:scale-[0.98] ${
                              isWrong
                                ? 'bg-rose-600 text-white border border-rose-600 shadow-sm'
                                : 'bg-white hover:bg-rose-50 text-rose-700 border border-rose-200 hover:border-rose-300 shadow-2xs'
                            }`}
                            title="Mark element as Wrong"
                          >
                            <X className="w-3.5 h-3.5 stroke-[2.5]" />
                            <span>Wrong</span>
                          </button>

                          <button
                            onClick={() => setOpenCommentElemId(openCommentElemId === elemEvalKey ? null : elemEvalKey)}
                            className={`flex items-center space-x-1 px-2.5 py-1 rounded-lg text-xs font-bold transition-all active:scale-[0.98] ${
                              elemEval?.comment
                                ? 'bg-amber-400 text-amber-950 border border-amber-500 shadow-xs'
                                : openCommentElemId === elemEvalKey
                                ? 'bg-indigo-100 text-indigo-800 border border-indigo-300'
                                : 'bg-white hover:bg-amber-50 text-slate-700 border border-slate-300 shadow-2xs'
                            }`}
                            title="Add or edit persistent comment (never erased)"
                          >
                            <MessageSquare className="w-3.5 h-3.5" />
                            <span>{elemEval?.comment ? '📝 Note' : 'Comment'}</span>
                          </button>
                        </div>

                        {hasTargetScreen ? (
                          <span className="text-[11px] font-mono font-bold text-indigo-700 bg-indigo-100/90 px-2 py-0.5 rounded-lg border border-indigo-300 flex items-center space-x-1 shrink-0 shadow-2xs">
                            <span>↗ SCREEN LINK</span>
                          </span>
                        ) : (
                          <span className="text-[10px] font-mono font-bold text-teal-700 bg-teal-50 px-2 py-0.5 rounded-lg border border-teal-200 flex items-center space-x-1 shrink-0">
                            <span>👆 ACTION</span>
                          </span>
                        )}
                      </div>
                    </div>

                    {/* Per-Element Persistent Comment Box (Never Erased!) */}
                    {(() => {
                      const isOpen = openCommentElemId === elemEvalKey;
                      if (!isOpen && !elemEval?.comment) return null;

                      return (
                        <div
                          className="ml-8 mr-4 my-2 p-2.5 rounded-xl bg-amber-50/90 border-2 border-amber-300/80 text-xs flex items-center space-x-2 shadow-sm"
                          onClick={(e) => e.stopPropagation()}
                        >
                          <div className="p-1 rounded-lg bg-amber-200 text-amber-900 shrink-0">
                            <MessageSquare className="w-3.5 h-3.5" />
                          </div>
                          <input
                            type="text"
                            value={elemEval?.comment || ''}
                            onChange={(e) => updateElementComment(screen.id, elemName, e.target.value)}
                            placeholder="Type a permanent note for this element (auto-saved)..."
                            className="flex-1 bg-white border border-amber-300 rounded-lg px-3 py-1.5 text-xs text-slate-900 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-amber-500 font-medium"
                          />
                          {elemEval?.comment && (
                            <span className="text-[10px] font-mono bg-emerald-100 text-emerald-800 border border-emerald-300 px-2 py-0.5 rounded-md font-bold shrink-0">
                              ✓ Saved
                            </span>
                          )}
                        </div>
                      );
                    })()}

                    {/* Child Screen (Expanded recursively under this link!) - [PERMANENT FIX] Code-editor nested block indentation */}
                    {hasTargetScreen && isExpanded && targetScreen && (
                      <div className="ml-6 pl-4 border-l-2 border-indigo-200/80 my-1.5 transition-all">
                        {renderNavGraphScreen(targetScreen, depth + 1, nextVisited, [...trail, screen.title])}
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
              const directTransitions = (screen.transitions || []).filter((t) => {
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
                            {renderNavGraphScreen(directTarget, depth + 1, nextVisited, [...trail, screen.title])}
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
    <div className={`flex-1 flex flex-col min-h-0 bg-[#f8fafc] px-5 py-3 transition-all ${
      isFullscreen ? 'fixed inset-0 z-[100] p-4 sm:p-5 overflow-hidden bg-[#f8fafc]' : ''
    }`}>
      {/* Top Header & View Mode Switcher - Hidden in Fullscreen */}
      {!isFullscreen && (
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

          {/* Top Action Controls: Fullscreen, Text Size, View Modes */}
          <div className="flex items-center space-x-2 flex-wrap gap-y-1">
            {/* Text Size Switcher */}
            <div className="flex items-center bg-slate-100 p-1 rounded-xl border border-slate-200/80 text-xs">
              <button
                onClick={() => setTextSize('normal')}
                className={`px-2.5 py-1 rounded-lg font-bold transition-all ${
                  textSize === 'normal'
                    ? 'bg-white text-indigo-600 shadow-xs border border-slate-200/60'
                    : 'text-slate-500 hover:text-slate-800'
                }`}
                title="Standard Text Size"
              >
                A Normal
              </button>
              <button
                onClick={() => setTextSize('large')}
                className={`px-2.5 py-1 rounded-lg font-bold transition-all ${
                  textSize === 'large'
                    ? 'bg-white text-indigo-600 shadow-xs border border-slate-200/60'
                    : 'text-slate-500 hover:text-slate-800'
                }`}
                title="Large Text Size (Easy reading on big monitors)"
              >
                A+ Large
              </button>
            </div>

            {/* Fullscreen Button */}
            <button
              onClick={() => setIsFullscreen(!isFullscreen)}
              className="flex items-center space-x-1.5 px-3 py-1.5 rounded-xl bg-white hover:bg-slate-50 text-slate-700 border border-slate-200/80 text-xs font-semibold shadow-xs transition-all active:scale-[0.98]"
              title="Enter Fullscreen (Focus on Tree)"
            >
              <Maximize className="w-3.5 h-3.5 text-indigo-600" />
              <span>Fullscreen</span>
            </button>

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
        </div>
      )}

      {/* Graph Health & Quality Stats Bar - Hidden in Fullscreen */}
      {!isFullscreen && viewMode === 'nav_graph' && navGraphStats && (
        <div className="my-2.5 px-4 py-2 bg-white rounded-2xl border border-slate-200/80 text-xs shadow-xs flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center space-x-2.5 flex-wrap gap-y-1">
            <span className="font-bold text-slate-800 flex items-center space-x-1.5 mr-1">
              <Layers className="w-3.5 h-3.5 text-indigo-600" />
              <span>Graph Health:</span>
            </span>
            <span className="px-2 py-0.5 rounded-lg font-mono font-bold bg-slate-100 text-slate-700">
              {navGraphStats.totalScreens} Screens
            </span>
            <span className="px-2 py-0.5 rounded-lg font-mono font-bold bg-indigo-50 text-indigo-700 border border-indigo-200/60">
              {totalTransitionsCount} Links
            </span>
            <span className="px-2 py-0.5 rounded-lg font-mono font-bold bg-emerald-50 text-emerald-700 border border-emerald-200">
              ✓ {navGraphStats.correct} Correct
            </span>
            {navGraphStats.wrong > 0 && (
              <span className="px-2 py-0.5 rounded-lg font-mono font-bold bg-rose-50 text-rose-700 border border-rose-200">
                ✗ {navGraphStats.wrong} Wrong
              </span>
            )}
            <span className="px-2 py-0.5 rounded-lg font-mono font-medium text-slate-500">
              {navGraphStats.unreviewed} Unreviewed
            </span>
          </div>
          <div className="text-[11px] font-mono text-slate-400 font-medium">
            {Math.round(((navGraphStats.correct + navGraphStats.wrong) / Math.max(1, navGraphStats.totalScreens)) * 100)}% Evaluated
          </div>
        </div>
      )}

      {/* App Selector & Search Controls (Nav Graph Mode) */}
      {viewMode === 'nav_graph' && navGraph && navGraph.apps && navGraph.apps.length > 0 && (
        <div className={`flex flex-wrap items-center justify-between gap-3 p-3.5 bg-white rounded-2xl border border-slate-200/80 shadow-xs ${
          isFullscreen ? 'mb-3.5 ring-1 ring-slate-200/60 shadow-sm' : 'my-3.5'
        }`}>
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
              <span className="text-[11px] font-mono text-slate-400 font-medium hidden sm:inline">
                {(activeApp.screens || []).length} screens, {totalTransitionsCount} transitions
              </span>
            )}

            {/* Expand / Collapse All Toggle Button */}
            <button
              onClick={() => setIsAllExpanded(!isAllExpanded)}
              className="px-3 py-1.5 rounded-xl text-xs font-semibold bg-slate-50 hover:bg-slate-100 text-slate-700 border border-slate-200 transition-all flex items-center space-x-1"
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

            {/* Text Size Switcher in Fullscreen */}
            {isFullscreen && (
              <div className="flex items-center bg-slate-100 p-0.5 rounded-xl border border-slate-200/80 text-xs">
                <button
                  onClick={() => setTextSize('normal')}
                  className={`px-2 py-0.5 rounded-lg font-bold transition-all ${
                    textSize === 'normal'
                      ? 'bg-white text-indigo-600 shadow-xs border border-slate-200/60'
                      : 'text-slate-500 hover:text-slate-800'
                  }`}
                  title="Standard Text Size"
                >
                  A
                </button>
                <button
                  onClick={() => setTextSize('large')}
                  className={`px-2 py-0.5 rounded-lg font-bold transition-all ${
                    textSize === 'large'
                      ? 'bg-white text-indigo-600 shadow-xs border border-slate-200/60'
                      : 'text-slate-500 hover:text-slate-800'
                  }`}
                  title="Large Text Size"
                >
                  A+
                </button>
              </div>
            )}

            {/* Fullscreen Toggle Button */}
            <button
              onClick={() => setIsFullscreen(!isFullscreen)}
              className={`px-3 py-1.5 rounded-xl text-xs font-semibold transition-all flex items-center space-x-1.5 shadow-xs ${
                isFullscreen
                  ? 'bg-indigo-600 hover:bg-indigo-700 text-white shadow-sm'
                  : 'bg-indigo-50 hover:bg-indigo-100 text-indigo-700 border border-indigo-200/80 active:scale-[0.98]'
              }`}
              title={isFullscreen ? 'Exit Fullscreen (Esc)' : 'Enter Fullscreen (Focus on Tree)'}
            >
              {isFullscreen ? (
                <>
                  <Minimize className="w-3.5 h-3.5" />
                  <span>Exit Fullscreen</span>
                  <span className="text-[10px] opacity-75 font-mono ml-0.5">(Esc)</span>
                </>
              ) : (
                <>
                  <Maximize className="w-3.5 h-3.5" />
                  <span>Fullscreen</span>
                </>
              )}
            </button>
          </div>

          {/* Search Filter */}
          <div className="relative min-w-[220px] max-w-xs">
            <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              ref={searchInputRef}
              type="text"
              placeholder="Search elements in graph... (Press '/' to focus)"
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

              {/* Fullscreen Toggle Button in Live Mode */}
              <button
                onClick={() => setIsFullscreen(!isFullscreen)}
                className={`px-3 py-1.5 rounded-xl text-xs font-semibold transition-all flex items-center space-x-1.5 shadow-xs ${
                  isFullscreen
                    ? 'bg-indigo-600 hover:bg-indigo-700 text-white shadow-sm'
                    : 'bg-indigo-50 hover:bg-indigo-100 text-indigo-700 border border-indigo-200/80'
                }`}
                title={isFullscreen ? 'Exit Fullscreen (Esc)' : 'Focus Live Screens in Fullscreen'}
              >
                {isFullscreen ? (
                  <>
                    <Minimize className="w-3.5 h-3.5" />
                    <span>Exit Fullscreen</span>
                    <span className="text-[10px] opacity-75 font-mono ml-0.5">(Esc)</span>
                  </>
                ) : (
                  <>
                    <Maximize className="w-3.5 h-3.5" />
                    <span>Fullscreen</span>
                  </>
                )}
              </button>
            </div>
          </div>

          {/* Element Type Filter Pills */}
          <div className="flex items-center space-x-1.5 pt-1 border-t border-slate-100 flex-wrap gap-y-1">
            <span className="text-[11px] text-slate-500 font-semibold mr-1">Show Elements:</span>
            <button
              onClick={() => setElementFilter('all')}
              className={`px-2.5 py-0.5 rounded-lg text-xs font-semibold transition-all ${
                elementFilter === 'all'
                  ? 'bg-slate-200 text-slate-800 font-bold'
                  : 'text-slate-500 hover:text-slate-800 hover:bg-slate-100'
              }`}
            >
              All Elements
            </button>
            <button
              onClick={() => setElementFilter('clickable')}
              className={`px-2.5 py-0.5 rounded-lg text-xs font-semibold transition-all ${
                elementFilter === 'clickable'
                  ? 'bg-indigo-100 text-indigo-700 border border-indigo-200 font-bold'
                  : 'text-slate-500 hover:text-indigo-700 hover:bg-indigo-50'
              }`}
            >
              Clickable Only
            </button>
            <button
              onClick={() => setElementFilter('scrollable')}
              className={`px-2.5 py-0.5 rounded-lg text-xs font-semibold transition-all ${
                elementFilter === 'scrollable'
                  ? 'bg-sky-100 text-sky-700 border border-sky-200 font-bold'
                  : 'text-slate-500 hover:text-sky-700 hover:bg-sky-50'
              }`}
            >
              Scrollable Only
            </button>
          </div>

          {/* Search bar inside Live mode */}
          <div className="relative">
            <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              ref={searchInputRef}
              type="text"
              placeholder="Filter live screens by title, package, elements, or notes... (Press '/' to focus)"
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

      {/* Sticky Live Phone Location Banner - Hidden in Fullscreen */}
      {!isFullscreen && latestLiveLocation.screenTitle && viewMode === 'nav_graph' && (
        <div className="sticky top-0 z-10 my-2 px-3.5 py-2 bg-emerald-50/90 backdrop-blur-sm border border-emerald-200 rounded-xl flex items-center justify-between shadow-xs">
          <div className="flex items-center space-x-2">
            <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse shrink-0" />
            <span className="text-xs font-semibold text-emerald-800">
              Phone is on: <span className="font-bold text-emerald-950">{latestLiveLocation.screenTitle}</span>
            </span>
            {latestLiveLocation.packageName && (
              <span className="text-[10px] font-mono text-emerald-600/70">{latestLiveLocation.packageName}</span>
            )}
          </div>
          {latestLiveLocation.currentStep && (
            <span className="text-[10px] font-mono font-bold text-amber-700 bg-amber-100 px-2 py-0.5 rounded-md border border-amber-200">
              🎯 Next: {latestLiveLocation.currentStep}
            </span>
          )}
        </div>
      )}

      {/* Search Match Counter */}
      {searchMatchInfo && (
        <div className="mb-2 px-3 py-1.5 bg-indigo-50/80 border border-indigo-200/60 rounded-lg flex items-center space-x-2">
          <Search className="w-3 h-3 text-indigo-500" />
          <span className="text-xs font-semibold text-indigo-700">
            Found <span className="font-bold">{searchMatchInfo.matchCount}</span> match{searchMatchInfo.matchCount !== 1 ? 'es' : ''} in <span className="font-bold">{searchMatchInfo.screenCount}</span> screen{searchMatchInfo.screenCount !== 1 ? 's' : ''}
          </span>
        </div>
      )}

      {/* Main Content Area */}
      <div className="flex-1 min-h-0 overflow-y-auto">
        {/* NAV GRAPH TREE MODE */}
        {viewMode === 'nav_graph' && (
          <div className="space-y-4">
            {!activeApp ? (
              <div className="flex flex-col items-center justify-center py-16 text-center text-slate-400 p-8 bg-white rounded-3xl border border-slate-200/80 shadow-xs">
                <div className="w-14 h-14 rounded-2xl bg-indigo-50 border border-indigo-100 flex items-center justify-center mb-3 text-indigo-600 shadow-xs">
                  <GitFork className="w-7 h-7 stroke-[1.75]" />
                </div>
                <h3 className="text-base font-bold text-slate-800">No App Selected</h3>
                <p className="text-xs text-slate-500 max-w-sm mt-1.5 leading-relaxed">
                  Choose an application from the "Select App" dropdown above to explore its navigation graph and screen structure.
                </p>
              </div>
            ) : !rootScreen ? (
              <div className="flex flex-col items-center justify-center py-16 text-center text-slate-400 p-8 bg-white rounded-3xl border border-slate-200/80 shadow-xs">
                <div className="w-14 h-14 rounded-2xl bg-slate-50 border border-slate-200 flex items-center justify-center mb-3 text-slate-400">
                  <FolderOpen className="w-7 h-7 stroke-[1.75]" />
                </div>
                <h3 className="text-base font-bold text-slate-800">No Screens Recorded</h3>
                <p className="text-xs text-slate-500 max-w-sm mt-1.5 leading-relaxed font-mono">
                  {activeApp.package_name} has 0 screens in the graph. Open the app on your phone and navigate to start recording screens.
                </p>
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
                {renderNavGraphScreen(rootScreen, 1, new Set(), [])}

                {/* [PERMANENT FIX] Do not remove this! Render Root Screen + Additional Unconnected Screens Guard.
                    In case some screens in this app are not reachable directly via forward transitions from root,
                    surface them below in an Unconnected / Island Screens section so the user can still see them! */}
                {(() => {
                  const renderedScreens = new Set<string>();
                  const collectRendered = (scr: NavGraphScreen, visited: Set<string>) => {
                    if (visited.has(scr.id)) return;
                    visited.add(scr.id);
                    renderedScreens.add(scr.id);
                    (scr.transitions || []).forEach((t) => {
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
                        {unconnected.map((islandScreen) => renderNavGraphScreen(islandScreen, 1, new Set(), ['Island Screens']))}
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
              <div className="flex flex-col items-center justify-center py-16 text-center text-slate-400 p-8 bg-white rounded-3xl border border-slate-200/80 shadow-xs">
                <div className="w-14 h-14 rounded-2xl bg-indigo-50 border border-indigo-100 flex items-center justify-center mb-3 text-indigo-600 shadow-xs">
                  <Smartphone className="w-7 h-7 stroke-[1.75]" />
                </div>
                <h3 className="text-base font-bold text-slate-800">No Live Screen Captures Yet</h3>
                <p className="text-xs text-slate-500 max-w-sm mt-1.5 leading-relaxed">
                  As you navigate your phone, live accessibility tree dumps with clickable buttons will stream here automatically in real time.
                </p>
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
                            {screen.elements
                              .filter((el) => {
                                if (elementFilter === 'clickable') return el.is_clickable;
                                if (elementFilter === 'scrollable') return el.is_scrollable;
                                return true;
                              })
                              .map((el, elIdx) => (
                              <div
                                key={el.id || `live_el_${elIdx}`}
                                onClick={() =>
                                  setSelectedElement({
                                    element: el,
                                    screenTitle: screen.screenTitle,
                                    packageName: screen.packageName
                                  })
                                }
                                className={`flex items-center justify-between p-2.5 rounded-xl border cursor-pointer select-none transition-all shadow-2xs ${
                                  el.is_clickable
                                    ? 'bg-emerald-50/50 hover:bg-emerald-100/60 border-emerald-200/80'
                                    : el.is_scrollable
                                    ? 'bg-sky-50/50 hover:bg-sky-100/60 border-sky-200/80'
                                    : 'bg-white hover:bg-slate-50 border-slate-200'
                                }`}
                              >
                                <div className="flex items-center space-x-2 truncate mr-2">
                                  <FileCode className={`w-3.5 h-3.5 shrink-0 ${el.is_clickable ? 'text-emerald-600' : el.is_scrollable ? 'text-sky-600' : 'text-slate-400'}`} />
                                  <span className="text-xs font-bold text-slate-900 truncate">
                                    {highlightMatch(el.name, searchQuery)}
                                  </span>
                                </div>
                                <div className="flex items-center space-x-1.5 shrink-0">
                                  {el.is_clickable && (
                                    <span className="text-[10px] px-2 py-0.5 rounded-md font-mono font-bold bg-emerald-600 text-white shadow-2xs">
                                      tap
                                    </span>
                                  )}
                                  {el.is_scrollable && (
                                    <span className="text-[10px] px-2 py-0.5 rounded-md font-mono font-bold bg-sky-600 text-white shadow-2xs">
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

      {/* Side Inspector Drawer for Single Element (Non-blocking, Tree stays visible!) */}
      {selectedElement && (
        <aside className="fixed top-0 right-0 bottom-0 w-84 md:w-96 bg-white border-l border-slate-200 shadow-2xl z-[110] flex flex-col transition-transform duration-200 ease-out">
          {/* Drawer Header */}
          <div className="flex items-center justify-between px-5 py-4 border-b border-slate-200/80 bg-slate-50/60">
            <div className="flex items-center space-x-2.5 truncate mr-2">
              <div className="p-1.5 rounded-xl bg-indigo-50 text-indigo-600 border border-indigo-100 shadow-xs shrink-0">
                <FileCode className="w-4 h-4" />
              </div>
              <div className="truncate">
                <h3 className="text-xs font-bold text-slate-900 truncate">
                  {selectedElement.element.name}
                </h3>
                <span className="text-[10px] font-mono text-slate-400">Element Inspector</span>
              </div>
            </div>
            <button
              onClick={() => setSelectedElement(null)}
              className="text-slate-400 hover:text-slate-700 p-1.5 rounded-xl hover:bg-slate-200/60 transition-colors"
              title="Close inspector (Esc)"
            >
              <X className="w-4 h-4" />
            </button>
          </div>

          {/* Drawer Body: Properties */}
          <div className="flex-1 overflow-y-auto p-5 space-y-4">
            <div className="space-y-2 text-xs">
              <div className="p-3 rounded-xl bg-slate-50 border border-slate-200/70 space-y-1">
                <span className="text-[10px] font-mono font-bold text-slate-400 uppercase">Screen Context</span>
                <div className="font-bold text-slate-900 text-xs">{selectedElement.screenTitle}</div>
                <div className="text-[10px] font-mono text-slate-500 truncate">{selectedElement.packageName}</div>
              </div>

              <div className="space-y-2 pt-2">
                <div className="flex flex-col py-1.5 border-b border-slate-100">
                  <span className="text-[11px] text-slate-500 font-semibold">Resource ID:</span>
                  <span className="text-slate-800 font-mono text-xs break-all mt-0.5">
                    {selectedElement.element.resource_id || '(none)'}
                  </span>
                </div>

                <div className="flex flex-col py-1.5 border-b border-slate-100">
                  <span className="text-[11px] text-slate-500 font-semibold">Class Name:</span>
                  <span className="text-slate-800 font-mono text-xs break-all mt-0.5">
                    {selectedElement.element.class_name || '(none)'}
                  </span>
                </div>

                <div className="flex justify-between py-2 border-b border-slate-100 items-center">
                  <span className="text-[11px] text-slate-500 font-semibold">Bounds:</span>
                  <span className="text-slate-800 font-mono text-xs bg-slate-100 px-2 py-0.5 rounded-md">
                    {selectedElement.element.bounds || '(none)'}
                  </span>
                </div>

                <div className="flex justify-between py-2 border-b border-slate-100 items-center">
                  <span className="text-[11px] text-slate-500 font-semibold">Clickable:</span>
                  <span
                    className={`text-[10px] px-2 py-0.5 rounded-md font-bold font-mono ${
                      selectedElement.element.is_clickable
                        ? 'bg-emerald-50 text-emerald-700 border border-emerald-200'
                        : 'bg-slate-100 text-slate-500'
                    }`}
                  >
                    {selectedElement.element.is_clickable ? 'YES (clickable)' : 'NO (static)'}
                  </span>
                </div>

                <div className="flex justify-between py-2 items-center">
                  <span className="text-[11px] text-slate-500 font-semibold">Scrollable:</span>
                  <span
                    className={`text-[10px] px-2 py-0.5 rounded-md font-bold font-mono ${
                      selectedElement.element.is_scrollable
                        ? 'bg-sky-50 text-sky-700 border border-sky-200'
                        : 'bg-slate-100 text-slate-500'
                    }`}
                  >
                    {selectedElement.element.is_scrollable ? 'YES (scrollable)' : 'NO'}
                  </span>
                </div>
              </div>
            </div>
          </div>

          {/* Drawer Footer */}
          <div className="p-4 border-t border-slate-200/80 bg-slate-50/50 flex items-center justify-between">
            <button
              onClick={() => handleCopy(selectedElement.element, 'element_modal')}
              className="flex items-center space-x-1.5 px-3 py-1.5 rounded-xl bg-white hover:bg-slate-100 text-xs font-semibold text-slate-700 border border-slate-200 shadow-xs transition-colors"
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
              className="px-3.5 py-1.5 rounded-xl bg-slate-200 hover:bg-slate-300 text-xs font-semibold text-slate-700 transition-colors"
            >
              Close
            </button>
          </div>
        </aside>
      )}

      {/* Study & Analysis Modal for Wrong Screens (Nexus Light Theme) */}
      {isStudyModalOpen && (
        <div className="fixed inset-0 z-[120] flex items-center justify-center bg-slate-900/40 backdrop-blur-xs p-4">
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
