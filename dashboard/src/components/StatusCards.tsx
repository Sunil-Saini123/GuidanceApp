import React from 'react';
import { AppState } from '../types';
import { Navigation, Compass, Layers, CheckCircle2, AlertTriangle, Eye } from 'lucide-react';

interface StatusCardsProps {
  appState: AppState;
}

export const StatusCards: React.FC<StatusCardsProps> = ({ appState }) => {
  const getHudBadge = (state: string) => {
    switch (state) {
      case 'FOUND':
        return { bg: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30', label: 'TARGET FOUND' };
      case 'ON_TRACK':
        return { bg: 'bg-blue-500/10 text-blue-400 border-blue-500/30', label: 'ON TRACK' };
      case 'OFF_TRACK':
        return { bg: 'bg-rose-500/10 text-rose-400 border-rose-500/30', label: 'OFF TRACK' };
      case 'SEARCHING':
        return { bg: 'bg-amber-500/10 text-amber-400 border-amber-500/30', label: 'SEARCHING' };
      case 'SUCCESS':
        return { bg: 'bg-emerald-500/20 text-emerald-300 border-emerald-500/50', label: 'DESTINATION REACHED' };
      default:
        return { bg: 'bg-gray-800 text-gray-400 border-gray-700', label: 'IDLE / READY' };
    }
  };

  const hudInfo = getHudBadge(appState.hudState);

  return (
    <div className="grid grid-cols-1 md:grid-cols-4 gap-4 px-6 py-4">
      {/* Active Package / Screen */}
      <div className="bg-dark-800 border border-dark-700 rounded-xl p-4 flex items-start space-x-3">
        <div className="p-2 rounded-lg bg-blue-500/10 border border-blue-500/20 text-blue-400">
          <Compass className="w-5 h-5" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-xs font-mono text-gray-400 uppercase tracking-wider">Active Screen</p>
          <p className="text-sm font-semibold text-gray-100 truncate mt-0.5">
            {appState.activeScreenTitle || 'None detected yet'}
          </p>
          <p className="text-xs font-mono text-gray-500 truncate mt-0.5">
            {appState.activePackage || 'com.example.floatingassistant'}
          </p>
        </div>
      </div>

      {/* Navigation Goal & Step */}
      <div className="bg-dark-800 border border-dark-700 rounded-xl p-4 flex items-start space-x-3">
        <div className="p-2 rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-emerald-400">
          <Navigation className="w-5 h-5" />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-center justify-between">
            <p className="text-xs font-mono text-gray-400 uppercase tracking-wider">Current Target</p>
            {appState.totalSteps > 0 && (
              <span className="text-xs font-mono px-2 py-0.5 rounded bg-dark-700 text-gray-300">
                Step {appState.stepIndex + 1}/{appState.totalSteps}
              </span>
            )}
          </div>
          <p className="text-sm font-bold text-emerald-300 truncate mt-0.5">
            {appState.currentStep ? `"${appState.currentStep}"` : 'No active query'}
          </p>
          <p className="text-xs text-gray-500 truncate mt-0.5">
            Task: {appState.currentTask || 'Waiting for user input'}
          </p>
        </div>
      </div>

      {/* HUD State Machine Status */}
      <div className="bg-dark-800 border border-dark-700 rounded-xl p-4 flex items-start space-x-3">
        <div className="p-2 rounded-lg bg-purple-500/10 border border-purple-500/20 text-purple-400">
          {appState.hudState === 'OFF_TRACK' ? (
            <AlertTriangle className="w-5 h-5 text-rose-400" />
          ) : (
            <CheckCircle2 className="w-5 h-5" />
          )}
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-xs font-mono text-gray-400 uppercase tracking-wider">Engine State</p>
          <div className="mt-1">
            <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-mono border ${hudInfo.bg}`}>
              {hudInfo.label}
            </span>
          </div>
          <p className="text-xs font-mono text-gray-500 mt-1">
            Events: {appState.totalEventsReceived} recorded
          </p>
        </div>
      </div>

      {/* Screen Elements Count */}
      <div className="bg-dark-800 border border-dark-700 rounded-xl p-4 flex items-start space-x-3">
        <div className="p-2 rounded-lg bg-amber-500/10 border border-amber-500/20 text-amber-400">
          <Layers className="w-5 h-5" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-xs font-mono text-gray-400 uppercase tracking-wider">UI Tree Visible</p>
          <p className="text-sm font-semibold text-gray-100 mt-0.5">
            {appState.lastElementsCount} elements extracted
          </p>
          <div className="flex items-center space-x-1 mt-0.5 text-xs text-gray-400">
            <Eye className="w-3.5 h-3.5 text-emerald-400" />
            <span className="font-mono">Real-time memory sync</span>
          </div>
        </div>
      </div>
    </div>
  );
};
