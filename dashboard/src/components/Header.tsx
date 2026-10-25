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
    <header className="bg-white/90 backdrop-blur-md border-b border-slate-200/80 sticky top-0 z-50 px-6 py-3 shadow-xs">
      <div className="flex items-center justify-between">
        {/* Brand */}
        <div className="flex items-center space-x-3">
          <div className="w-8 h-8 rounded-xl bg-gradient-to-tr from-indigo-600 to-violet-500 flex items-center justify-center shadow-sm shadow-indigo-500/20 text-white">
            <Activity className="w-4 h-4 animate-pulse" />
          </div>
          <div>
            <h1 className="text-sm font-bold text-slate-900 flex items-center gap-2">
              GuidanceApp{' '}
              <span className="text-[10px] px-2 py-0.5 rounded-full bg-indigo-50 text-indigo-600 border border-indigo-100 font-mono font-bold tracking-wide">
                LIVE BRAIN
              </span>
            </h1>
            <p className="text-xs text-slate-500 font-medium">Step-by-Step Decision Telemetry</p>
          </div>
        </div>

        {/* Navigation Tabs */}
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

        {/* Live Status & Controls */}
        <div className="flex items-center space-x-3">
          <div
            className={`flex items-center space-x-2 px-3 py-1.5 rounded-full border text-xs font-mono font-bold transition-all shadow-xs ${
              appState.phoneConnected
                ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                : 'bg-rose-50 text-rose-600 border-rose-200'
            }`}
          >
            <span
              className={`w-2 h-2 rounded-full ${
                appState.phoneConnected ? 'bg-emerald-500 animate-ping' : 'bg-rose-500'
              }`}
            />
            <span>{appState.phoneConnected ? 'PHONE CONNECTED' : 'NO PHONE CONNECTED'}</span>
          </div>

          <button
            onClick={onTogglePause}
            className={`p-1.5 rounded-xl border text-xs flex items-center gap-1.5 px-3 font-semibold transition-all active:scale-[0.98] shadow-xs ${
              isPaused
                ? 'bg-amber-50 text-amber-700 border-amber-200 hover:bg-amber-100/80'
                : 'bg-white text-slate-700 border-slate-200 hover:bg-slate-50'
            }`}
            title={isPaused ? 'Resume stream' : 'Pause stream'}
          >
            {isPaused ? <Play className="w-3.5 h-3.5 text-amber-600" /> : <Pause className="w-3.5 h-3.5 text-slate-500" />}
            <span>{isPaused ? 'RESUME' : 'PAUSE'}</span>
          </button>

          <button
            onClick={onClear}
            className="p-1.5 rounded-xl bg-white text-slate-400 hover:text-rose-600 hover:bg-rose-50 border border-slate-200 hover:border-rose-200 transition-all active:scale-[0.98] shadow-xs"
            title="Clear all logs"
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>
    </header>
  );
};
