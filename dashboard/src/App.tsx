import React, { useState, useEffect, useRef } from 'react';
import { TelemetryEvent, AppState } from './types';
import { Header } from './components/Header';
import { StatusCards } from './components/StatusCards';
import { TimelineTab } from './components/TimelineTab';
import { MediatorTab } from './components/MediatorTab';
import { ScreenSimulatorTab } from './components/ScreenSimulatorTab';
import { TreeExplorerTab } from './components/TreeExplorerTab';
import { AiInspectorTab } from './components/AiInspectorTab';

export const App: React.FC = () => {
  const [events, setEvents] = useState<TelemetryEvent[]>([]);
  const [isPaused, setIsPaused] = useState(false);
  const [activeTab, setActiveTab] = useState('timeline');

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

          // Handle special device connection status broadcast from server
          if (data.type === 'DEVICE_STATUS') {
            setAppState((prev) => ({
              ...prev,
              phoneConnected: Boolean(data.phoneConnected)
            }));
            return;
          }

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
              phoneConnected: true, // Activity received from phone!
              totalEventsReceived: prev.totalEventsReceived + 1
            };
            if (data.packageName) next.activePackage = data.packageName;
            if (data.screenTitle) next.activeScreenTitle = data.screenTitle;
            if (data.currentStep) next.currentStep = data.currentStep;
            if (data.stepIndex !== undefined) next.stepIndex = data.stepIndex;
            if (data.totalSteps !== undefined) next.totalSteps = data.totalSteps;
            if (data.targetBounds) next.lastTargetBounds = data.targetBounds;
            if (data.elements) next.lastElementsCount = data.elements.length;

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

    connect();

    return () => {
      if (ws) ws.close();
      if (reconnectTimeout) clearTimeout(reconnectTimeout);
    };
  }, []);

  return (
    <div className="flex flex-col h-screen bg-dark-900 text-gray-100 overflow-hidden font-['Plus_Jakarta_Sans',sans-serif]">
      {/* Top Header */}
      <Header
        appState={appState}
        isPaused={isPaused}
        onTogglePause={() => setIsPaused((p) => !p)}
        onClear={() => setEvents([])}
        activeTab={activeTab}
        setActiveTab={setActiveTab}
      />

      {/* Overview Stat Cards */}
      <StatusCards appState={appState} />

      {/* Main Tab Panels */}
      <main className="flex-1 flex min-h-0">
        {activeTab === 'timeline' && <TimelineTab events={events} />}
        {activeTab === 'mediator' && <MediatorTab events={events} />}
        {activeTab === 'screen' && <ScreenSimulatorTab appState={appState} events={events} />}
        {activeTab === 'tree' && <TreeExplorerTab events={events} />}
        {activeTab === 'ai' && <AiInspectorTab events={events} />}
      </main>
    </div>
  );
};
export default App;
