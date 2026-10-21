import React from 'react';
import { Activity, Radio, Trash2, Pause, Play, Smartphone, Wifi, Terminal } from 'lucide-react';
import { AppState } from '../types';

interface HeaderProps {
  appState: AppState;
  isPaused: boolean;
  onTogglePause: () => void;
  onClear: () => void;
  activeTab: string;
  setActiveTab: (tab: string) => void;
}

export const Header: React.FC<HeaderProps> = ({
  appState,
  isPaused,
  onTogglePause,
  onClear,
  activeTab,
  setActiveTab,
}) => {
  const tabs = [
    { id: 'timeline', label: 'Decision Stream', icon: Activity },
    { id: 'mediator', label: 'Scroll Mediator Diff', icon: Radio },
    { id: 'screen', label: 'Screen & Bounds Simulator', icon: Smartphone },
    { id: 'tree', label: 'UI Tree Explorer', icon: Terminal },
    { id: 'ai', label: 'AI & Intent Engine', icon: Wifi },
  ];

  return (
    <header className="bg-dark-800 border-b border-dark-700 sticky top-0 z-50 px-6 py-3">
      <div className="flex items-center justify-between">
        {/* Brand */}
        <div className="flex items-center space-x-3">
          <div className="w-8 h-8 rounded-lg bg-emerald-500/10 border border-emerald-500/20 flex items-center justify-center">
            <Activity className="w-5 h-5 text-emerald-400 animate-pulse" />
          </div>
          <div>
            <h1 className="text-sm font-bold text-gray-100 flex items-center gap-2">
              GuidanceApp <span className="text-xs px-2 py-0.5 rounded bg-dark-700 text-emerald-400 font-mono">LIVE BRAIN</span>
            </h1>
            <p className="text-xs text-gray-400">Step-by-Step Decision Telemetry</p>
          </div>
        </div>

        {/* Navigation Tabs */}
        <nav className="flex space-x-1 bg-dark-900/60 p-1 rounded-xl border border-dark-700">
          {tabs.map((tab) => {
            const Icon = tab.icon;
            const active = activeTab === tab.id;
            return (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={`flex items-center space-x-2 px-3 py-1.5 rounded-lg text-xs font-medium transition-all ${
                  active
                    ? 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/30 shadow-sm'
                    : 'text-gray-400 hover:text-gray-200 hover:bg-dark-800'
                }`}
              >
                <Icon className={`w-3.5 h-3.5 ${active ? 'text-emerald-400' : 'text-gray-500'}`} />
                <span>{tab.label}</span>
              </button>
            );
          })}
        </nav>

        {/* Live Status & Controls */}
        <div className="flex items-center space-x-3">
          <div className={`flex items-center space-x-2 px-3 py-1.5 rounded-full border text-xs font-mono font-bold transition-all ${
            appState.phoneConnected
              ? 'bg-emerald-500/10 text-emerald-300 border-emerald-500/40 shadow-[0_0_12px_rgba(16,185,129,0.2)]'
              : 'bg-rose-500/10 text-rose-400 border-rose-500/30'
          }`}>
            <span
              className={`w-2 h-2 rounded-full ${
                appState.phoneConnected ? 'bg-emerald-400 animate-ping' : 'bg-rose-500'
              }`}
            />
            <span>
              {appState.phoneConnected ? 'PHONE CONNECTED' : 'NO PHONE OR APP CONNECTED'}
            </span>
          </div>

          <button
            onClick={onTogglePause}
            className={`p-1.5 rounded-lg border text-xs flex items-center gap-1.5 px-3 transition-colors ${
              isPaused
                ? 'bg-amber-500/20 text-amber-300 border-amber-500/40'
                : 'bg-dark-700 text-gray-300 border-dark-600 hover:bg-dark-600'
            }`}
            title={isPaused ? 'Resume stream' : 'Pause stream'}
          >
            {isPaused ? <Play className="w-3.5 h-3.5" /> : <Pause className="w-3.5 h-3.5" />}
            <span>{isPaused ? 'RESUME' : 'PAUSE'}</span>
          </button>

          <button
            onClick={onClear}
            className="p-1.5 rounded-lg bg-dark-700 text-gray-400 hover:text-rose-400 hover:bg-dark-600 border border-dark-600 transition-colors"
            title="Clear all logs"
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>
    </header>
  );
};
