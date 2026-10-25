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
  // Toggle to true whenever you want to display all 5 tabs in the top header
  const SHOW_ALL_TABS = false;

  const allTabs = [
    { id: 'timeline', label: 'Decision Stream', icon: Activity },
    { id: 'mediator', label: 'Scroll Mediator Diff', icon: Radio },
    { id: 'screen', label: 'Screen & Bounds Simulator', icon: Smartphone },
    { id: 'tree', label: 'UI Tree Explorer', icon: Terminal },
    { id: 'ai', label: 'AI & Intent Engine', icon: Wifi },
  ];

  const tabs = SHOW_ALL_TABS ? allTabs : allTabs.filter((t) => t.id === 'tree');

  return (
    <header className="bg-white/95 backdrop-blur-md border-b border-slate-200/80 sticky top-0 z-50 px-5 py-2 shadow-xs">
      <div className="flex items-center justify-between">
        {/* Brand — Compact */}
        <div className="flex items-center space-x-2.5">
          <div className="w-7 h-7 rounded-lg bg-gradient-to-tr from-indigo-600 to-violet-500 flex items-center justify-center shadow-sm shadow-indigo-500/20 text-white">
            <Activity className="w-3.5 h-3.5" />
          </div>
          <h1 className="text-[13px] font-bold text-slate-900 flex items-center gap-2">
            GuidanceApp
            <span className="text-[9px] px-1.5 py-0.5 rounded-full bg-indigo-50 text-indigo-600 border border-indigo-100 font-mono font-bold tracking-wide">
              LIVE
            </span>
          </h1>
        </div>

        {/* Navigation Tabs (hidden when single tab) */}
        {SHOW_ALL_TABS && (
          <nav className="flex space-x-1 bg-slate-100/80 p-1 rounded-xl border border-slate-200/60">
            {tabs.map((tab) => {
              const Icon = tab.icon;
              const active = activeTab === tab.id;
              return (
                <button
                  key={tab.id}
                  onClick={() => setActiveTab(tab.id)}
                  className={`flex items-center space-x-2 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all duration-150 ${
                    active
                      ? 'bg-white text-indigo-600 shadow-xs border border-slate-200/60 scale-[1.01]'
                      : 'text-slate-600 hover:text-slate-900 hover:bg-white/60'
                  }`}
                >
                  <Icon className={`w-3.5 h-3.5 ${active ? 'text-indigo-600' : 'text-slate-400'}`} />
                  <span>{tab.label}</span>
                </button>
              );
            })}
          </nav>
        )}

        {/* Live Status & Controls */}
        <div className="flex items-center space-x-2.5">
          <div
            className={`flex items-center space-x-1.5 px-2.5 py-1 rounded-full border text-[11px] font-mono font-bold transition-all ${
              appState.phoneConnected
                ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                : 'bg-rose-50 text-rose-600 border-rose-200'
            }`}
          >
            <span
              className={`w-1.5 h-1.5 rounded-full ${
                appState.phoneConnected ? 'bg-emerald-500 animate-pulse' : 'bg-rose-500'
              }`}
            />
            <span>{appState.phoneConnected ? 'CONNECTED' : 'DISCONNECTED'}</span>
          </div>

          <button
            onClick={onTogglePause}
            className={`p-1 rounded-lg border text-[11px] flex items-center gap-1 px-2.5 font-semibold transition-all active:scale-[0.98] ${
              isPaused
                ? 'bg-amber-50 text-amber-700 border-amber-200 hover:bg-amber-100/80'
                : 'bg-white text-slate-600 border-slate-200 hover:bg-slate-50'
            }`}
            title={isPaused ? 'Resume stream' : 'Pause stream'}
          >
            {isPaused ? <Play className="w-3 h-3 text-amber-600" /> : <Pause className="w-3 h-3 text-slate-400" />}
            <span>{isPaused ? 'RESUME' : 'PAUSE'}</span>
          </button>

          <button
            onClick={onClear}
            className="p-1.5 rounded-lg bg-white text-slate-400 hover:text-rose-600 hover:bg-rose-50 border border-slate-200 hover:border-rose-200 transition-all active:scale-[0.98]"
            title="Clear all logs"
          >
            <Trash2 className="w-3 h-3" />
          </button>
        </div>
      </div>
    </header>
  );
};
