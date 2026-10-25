import React, { useState, useEffect, useRef } from 'react';
import { TelemetryEvent, AppState, NavGraphData } from './types';
import { Header } from './components/Header';
import { StatusCards } from './components/StatusCards';
import { TimelineTab } from './components/TimelineTab';
import { MediatorTab } from './components/MediatorTab';
import { ScreenSimulatorTab } from './components/ScreenSimulatorTab';
import { TreeExplorerTab } from './components/TreeExplorerTab';
import { AiInspectorTab } from './components/AiInspectorTab';

class ErrorBoundary extends React.Component<
  { children: React.ReactNode },
  { hasError: boolean; error: any }
> {
  constructor(props: any) {
    super(props);
    this.state = { hasError: false, error: null };
  }
  static getDerivedStateFromError(error: any) {
    return { hasError: true, error };
  }
  componentDidCatch(error: any, info: any) {
    console.error('[Dashboard ErrorBoundary caught error]', error, info);
  }
  render() {
    if (this.state.hasError) {
      return (
        <div className="flex-1 flex flex-col items-center justify-center p-8 text-center bg-dark-900 text-gray-300">
          <div className="p-3 rounded-full bg-rose-500/10 text-rose-400 mb-3 text-lg font-bold">⚠️</div>
          <h3 className="text-sm font-bold text-rose-400">Panel Render Error</h3>
          <p className="text-xs text-gray-400 mt-1 max-w-md font-mono">{String(this.state.error?.message || this.state.error)}</p>
          <button
            onClick={() => this.setState({ hasError: false, error: null })}
            className="mt-4 px-3 py-1.5 rounded-lg bg-dark-800 text-xs font-medium text-emerald-400 border border-dark-700 hover:bg-dark-700 transition-colors"
          >
            Retry Panel
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

export const App: React.FC = () => {
  const [events, setEvents] = useState<TelemetryEvent[]>([]);
  const [navGraph, setNavGraph] = useState<NavGraphData | null>(null);
  const [isPaused, setIsPaused] = useState(false);

  // Persist active tab across browser refreshes
  const [activeTab, setActiveTab] = useState(() => {
    try {
      return localStorage.getItem('guidance_active_tab') || 'tree';
    } catch (_) {
      return 'tree';
    }
  });

  const handleTabChange = (tab: string) => {
    setActiveTab(tab);
    try {
      localStorage.setItem('guidance_active_tab', tab);
    } catch (_) {}
  };

  const [appState, setAppState] = useState<AppState>({
    browserConnected: false,
    phoneConnected: false,
    activePackage: '',
    activeScreenTitle: '',
    currentTask: '',
    currentStep: '',
    totalSteps: 0,
    stepIndex: 0,
    hudState: 'IDLE',
    lastElementsCount: 0,
    totalEventsReceived: 0,
  });

  const isPausedRef = useRef(isPaused);
  isPausedRef.current = isPaused;

  useEffect(() => {
    let ws: WebSocket | null = null;
    let reconnectTimeout: any = null;

    function connect() {
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const host = window.location.host || 'localhost:3000';
      const wsUrl = `${protocol}//${host}/ws`;

      ws = new WebSocket(wsUrl);

      ws.onopen = () => {
        setAppState((prev) => ({ ...prev, browserConnected: true }));
      };

      ws.onmessage = (msgEvent) => {
        try {
          const data: any = JSON.parse(msgEvent.data);

          // 1. Handle device status heartbeat
          if (data.type === 'DEVICE_STATUS') {
            setAppState((prev) => ({
              ...prev,
              phoneConnected: Boolean(data.phoneConnected)
            }));
            return;
          }

          // 2. Handle initial history batch sent by server upon connect/refresh
          if (data.type === 'HISTORY_BATCH' && Array.isArray(data.events)) {
            const historyList: TelemetryEvent[] = data.events;
            // Set newest events first
            setEvents(historyList.slice().reverse());

            // Rehydrate aggregated state from history
            setAppState((prev) => {
              const next = {
                ...prev,
                totalEventsReceived: historyList.length
              };
              for (const ev of historyList) {
                if (ev.packageName) next.activePackage = ev.packageName;
                if (ev.screenTitle) next.activeScreenTitle = ev.screenTitle;
                if (ev.currentStep) next.currentStep = ev.currentStep;
                if (ev.stepIndex !== undefined) next.stepIndex = ev.stepIndex;
                if (ev.totalSteps !== undefined) next.totalSteps = ev.totalSteps;
                if (ev.targetBounds) next.lastTargetBounds = ev.targetBounds;
                if (ev.elements && ev.elements.length > 0) next.lastElementsCount = ev.elements.length;

                if (ev.type === 'WRONG_PATH') next.hudState = 'OFF_TRACK';
                else if (ev.type === 'ELEMENT_FOUND') next.hudState = 'FOUND';
                else if (ev.type === 'DESTINATION_REACHED') next.hudState = 'SUCCESS';
                else if (ev.type === 'STEP_ADVANCED') next.hudState = 'ON_TRACK';
              }
              return next;
            });
            return;
          }

          // 3. Handle history clear
          if (data.type === 'CLEAR_HISTORY') {
            setEvents([]);
            setNavGraph({ apps: [], total_apps: 0, total_screens: 0, total_transitions: 0 });
            return;
          }

          // 3b. Handle nav_graph data update
          if (data.type === 'NAV_GRAPH_DATA' && (data.navGraph || data.data)) {
            setNavGraph(data.navGraph || data.data);
            return;
          }

          // 4. Live single event from phone
          if (!data.id) {
            data.id = `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
          }

          if (!isPausedRef.current) {
            setEvents((prev) => [data, ...prev].slice(0, 500)); // Keep last 500
          }

          // Update aggregated AppState
          setAppState((prev) => {
            const next = {
              ...prev,
              phoneConnected: true,
              totalEventsReceived: prev.totalEventsReceived + 1
            };
            if (data.packageName) next.activePackage = data.packageName;
            if (data.screenTitle) next.activeScreenTitle = data.screenTitle;
            if (data.currentStep) next.currentStep = data.currentStep;
            if (data.stepIndex !== undefined) next.stepIndex = data.stepIndex;
            if (data.totalSteps !== undefined) next.totalSteps = data.totalSteps;
            if (data.targetBounds) next.lastTargetBounds = data.targetBounds;
            if (data.elements && data.elements.length > 0) next.lastElementsCount = data.elements.length;

            if (data.type === 'WRONG_PATH') next.hudState = 'OFF_TRACK';
            else if (data.type === 'ELEMENT_FOUND') next.hudState = 'FOUND';
            else if (data.type === 'DESTINATION_REACHED') next.hudState = 'SUCCESS';
            else if (data.type === 'STEP_ADVANCED') next.hudState = 'ON_TRACK';

            return next;
          });
        } catch (e) {
          console.error('[Dashboard WS] Failed to parse message:', e);
        }
      };

      ws.onclose = () => {
        setAppState((prev) => ({ ...prev, browserConnected: false, phoneConnected: false }));
        reconnectTimeout = setTimeout(connect, 2000);
      };

      ws.onerror = () => {
        ws?.close();
      };
    }

    // Fetch initial nav_graph via REST
    fetch('/api/nav_graph')
      .then((r) => (r.ok ? r.json() : null))
      .then((g) => {
        if (g) setNavGraph(g);
      })
      .catch(() => {});

    connect();

    return () => {
      if (ws) ws.close();
      if (reconnectTimeout) clearTimeout(reconnectTimeout);
    };
  }, []);

  const handleClear = async () => {
    setEvents([]);
    try {
      await fetch('/api/clear', { method: 'POST' });
    } catch (_) {}
  };

  return (
    <div className="flex flex-col h-screen bg-[#f8fafc] text-slate-900 overflow-hidden font-['Plus_Jakarta_Sans',sans-serif]">
      {/* Top Header */}
      <Header
        appState={appState}
        isPaused={isPaused}
        onTogglePause={() => setIsPaused((p) => !p)}
        onClear={handleClear}
        activeTab={activeTab}
        setActiveTab={handleTabChange}
      />

      {/* Overview Stat Cards (Hidden on UI Tree Explorer for clean, spacious view) */}
      {activeTab !== 'tree' && <StatusCards appState={appState} />}

      {/* Main Tab Panels */}
      <main className="flex-1 flex min-h-0">
        {activeTab === 'timeline' && <TimelineTab events={events} />}
        {activeTab === 'mediator' && <MediatorTab events={events} />}
        {activeTab === 'screen' && <ScreenSimulatorTab appState={appState} events={events} />}
        {activeTab === 'tree' && (
          <ErrorBoundary>
            <TreeExplorerTab events={events} navGraph={navGraph} />
          </ErrorBoundary>
        )}
        {activeTab === 'ai' && <AiInspectorTab events={events} />}
      </main>
    </div>
  );
};
export default App;
