import React from 'react';
import { AppState } from '../types';
import { Navigation, Compass, Layers, CheckCircle2, AlertTriangle } from 'lucide-react';

interface StatusCardsProps {
  appState: AppState;
}

export const StatusCards: React.FC<StatusCardsProps> = ({ appState }) => {
  const getHudBadge = (state: string) => {
    switch (state) {
      case 'FOUND':
        return { bg: 'bg-emerald-50 text-emerald-700 border-emerald-200', label: 'TARGET FOUND' };
      case 'ON_TRACK':
        return { bg: 'bg-indigo-50 text-indigo-700 border-indigo-200', label: 'ON TRACK' };
      case 'OFF_TRACK':
        return { bg: 'bg-rose-50 text-rose-700 border-rose-200', label: 'OFF TRACK' };
      case 'SEARCHING':
        return { bg: 'bg-amber-50 text-amber-700 border-amber-200', label: 'SEARCHING' };
      case 'SUCCESS':
        return { bg: 'bg-emerald-50 text-emerald-700 border-emerald-200', label: 'DESTINATION REACHED' };
      default:
        return { bg: 'bg-slate-100 text-slate-600 border-slate-200', label: 'IDLE / READY' };
    }
  };

  const hudInfo = getHudBadge(appState.hudState);

  return (
    <div className="grid grid-cols-1 md:grid-cols-4 gap-4 px-6 py-4">
      {/* Active Package / Screen */}
      <div className="bg-white rounded-2xl border border-slate-200/80 shadow-[0_1px_3px_0_rgba(0,0,0,0.02)] hover:shadow-[0_4px_20px_-2px_rgba(15,23,42,0.06)] hover:border-slate-300/80 transition-all duration-200 p-4 flex items-start space-x-3.5">
        <div className="p-2.5 rounded-xl bg-blue-50 border border-blue-100 text-blue-600 shadow-2xs">
          <Compass className="w-5 h-5" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-[11px] font-bold text-slate-400 uppercase tracking-wider font-mono">Active Screen</p>
          <p className="text-sm font-bold text-slate-900 truncate mt-1">
            {appState.activeScreenTitle || 'None detected yet'}
          </p>
          <p className="text-xs font-mono text-slate-500 truncate mt-0.5">
            {appState.activePackage || 'com.example.floatingassistant'}
          </p>
        </div>
      </div>

      {/* Navigation Goal & Step */}
      <div className="bg-white rounded-2xl border border-slate-200/80 shadow-[0_1px_3px_0_rgba(0,0,0,0.02)] hover:shadow-[0_4px_20px_-2px_rgba(15,23,42,0.06)] hover:border-slate-300/80 transition-all duration-200 p-4 flex items-start space-x-3.5">
        <div className="p-2.5 rounded-xl bg-emerald-50 border border-emerald-100 text-emerald-600 shadow-2xs">
          <Navigation className="w-5 h-5" />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-center justify-between">
            <p className="text-[11px] font-bold text-slate-400 uppercase tracking-wider font-mono">Current Target</p>
            {appState.totalSteps > 0 && (
              <span className="text-[10px] font-mono font-bold px-2 py-0.5 rounded-md bg-emerald-50 text-emerald-700 border border-emerald-200">
                Step {appState.stepIndex + 1}/{appState.totalSteps}
              </span>
            )}
          </div>
          <p className="text-sm font-bold text-emerald-600 truncate mt-1">
            {appState.currentStep ? `"${appState.currentStep}"` : 'No active query'}
          </p>
          <p className="text-xs text-slate-500 truncate mt-0.5 font-medium">
            Task: {appState.currentTask || 'Waiting for user input'}
          </p>
        </div>
      </div>

      {/* HUD State Machine Status */}
      <div className="bg-white rounded-2xl border border-slate-200/80 shadow-[0_1px_3px_0_rgba(0,0,0,0.02)] hover:shadow-[0_4px_20px_-2px_rgba(15,23,42,0.06)] hover:border-slate-300/80 transition-all duration-200 p-4 flex items-start space-x-3.5">
        <div className="p-2.5 rounded-xl bg-indigo-50 border border-indigo-100 text-indigo-600 shadow-2xs">
          {appState.hudState === 'OFF_TRACK' ? (
            <AlertTriangle className="w-5 h-5 text-rose-500" />
          ) : (
            <CheckCircle2 className="w-5 h-5" />
          )}
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-[11px] font-bold text-slate-400 uppercase tracking-wider font-mono">Engine State</p>
          <div className="mt-1">
            <span className={`inline-flex items-center px-2.5 py-0.5 rounded-lg text-xs font-bold font-mono border ${hudInfo.bg}`}>
              {hudInfo.label}
            </span>
          </div>
          <p className="text-xs font-mono text-slate-500 mt-1">
            Events: {appState.totalEventsReceived} recorded
          </p>
        </div>
      </div>

      {/* Screen Elements Count */}
      <div className="bg-white rounded-2xl border border-slate-200/80 shadow-[0_1px_3px_0_rgba(0,0,0,0.02)] hover:shadow-[0_4px_20px_-2px_rgba(15,23,42,0.06)] hover:border-slate-300/80 transition-all duration-200 p-4 flex items-start space-x-3.5">
        <div className="p-2.5 rounded-xl bg-amber-50 border border-amber-100 text-amber-600 shadow-2xs">
          <Layers className="w-5 h-5" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-[11px] font-bold text-slate-400 uppercase tracking-wider font-mono">UI Tree Visible</p>
          <p className="text-sm font-bold text-slate-900 mt-1">
            {appState.lastElementsCount} elements extracted
          </p>
          <p className="text-xs text-slate-500 mt-0.5 font-medium">Live Accessibility Nodes</p>
        </div>
      </div>
    </div>
  );
};
