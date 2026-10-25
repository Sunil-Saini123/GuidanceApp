import React from 'react';
import { AppState, TelemetryEvent } from '../types';
import { Smartphone, Box } from 'lucide-react';

interface ScreenSimulatorTabProps {
  appState: AppState;
  events: TelemetryEvent[];
}

export const ScreenSimulatorTab: React.FC<ScreenSimulatorTabProps> = ({ appState, events }) => {
  // Find the most recent event with elements
  const latestElementEvent = events
    .slice()
    .reverse()
    .find((e) => e.elements && e.elements.length > 0);

  const elements = latestElementEvent?.elements || [];
  const targetBounds = appState.lastTargetBounds;

  // Assumed standard screen dimension for coordinate scaling
  const screenWidth = 1080;
  const screenHeight = 2400;

  return (
    <div className="flex-1 flex min-h-0 bg-[#f8fafc] px-6 pb-6 gap-6">
      {/* Left Column: Phone Mockup Canvas */}
      <div className="w-[360px] flex flex-col items-center">
        <div className="flex items-center space-x-2 mb-3">
          <Smartphone className="w-4 h-4 text-indigo-600" />
          <span className="text-xs font-mono font-bold text-slate-700">Live Phone Display (1080×2400)</span>
        </div>

        {/* Scaled Device Frame (1080x2400 scaled to 320x711) */}
        <div className="relative w-[320px] h-[711px] bg-slate-950 rounded-[40px] border-[6px] border-slate-800 shadow-2xl overflow-hidden">
          {/* Top Speaker / Camera punch-hole */}
          <div className="absolute top-2 left-1/2 -translate-x-1/2 w-4 h-4 bg-black rounded-full z-30 border border-slate-800" />

          {/* Canvas area representing device screen */}
          <div className="relative w-full h-full bg-slate-950 select-none">
            {/* Render all detected elements as wireframe boxes */}
            {elements.map((el, idx) => {
              if (!el.bounds) return null;
              const b = el.bounds;
              const leftPercent = (b.left / screenWidth) * 100;
              const topPercent = (b.top / screenHeight) * 100;
              const widthPercent = ((b.right - b.left) / screenWidth) * 100;
              const heightPercent = ((b.bottom - b.top) / screenHeight) * 100;

              return (
                <div
                  key={idx}
                  title={`${el.name} (${b.left}, ${b.top})`}
                  style={{
                    left: `${leftPercent}%`,
                    top: `${topPercent}%`,
                    width: `${widthPercent}%`,
                    height: `${heightPercent}%`,
                  }}
                  className="absolute border border-indigo-500/30 bg-indigo-500/10 hover:border-indigo-400 hover:bg-indigo-500/30 transition-colors pointer-events-auto"
                >
                  <span className="text-[7px] text-slate-300 font-mono truncate block px-0.5 pointer-events-none font-semibold">
                    {el.name}
                  </span>
                </div>
              );
            })}

            {/* Glowing Green Marker on active target element */}
            {targetBounds && (
              <div
                style={{
                  left: `${(targetBounds.left / screenWidth) * 100}%`,
                  top: `${(targetBounds.top / screenHeight) * 100}%`,
                  width: `${((targetBounds.right - targetBounds.left) / screenWidth) * 100}%`,
                  height: `${((targetBounds.bottom - targetBounds.top) / screenHeight) * 100}%`,
                }}
                className="absolute border-2 border-emerald-400 bg-emerald-400/25 rounded-md shadow-[0_0_15px_rgba(52,211,153,0.5)] animate-pulse pointer-events-none z-20"
              >
                <div className="absolute -top-4 left-0 bg-emerald-500 text-black text-[8px] font-bold px-1 rounded font-mono">
                  TARGET
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Right Column: Element Inspector List */}
      <div className="flex-1 flex flex-col min-h-0 bg-white border border-slate-200/80 rounded-2xl p-4 shadow-xs">
        <div className="flex items-center justify-between pb-3.5 border-b border-slate-100">
          <div className="flex items-center space-x-2">
            <Box className="w-4 h-4 text-indigo-600" />
            <h3 className="text-xs font-bold text-slate-900">Visible Elements on Current Screen</h3>
          </div>
          <span className="text-xs font-mono font-semibold text-slate-500">
            {elements.length} elements detected
          </span>
        </div>

        <div className="flex-1 overflow-y-auto divide-y divide-slate-100 mt-2">
          {elements.length === 0 ? (
            <div className="text-center py-20 text-slate-400 text-xs font-medium">
              No screen elements received yet. Navigate or open an app on your phone.
            </div>
          ) : (
            elements.map((el, i) => (
              <div key={i} className="py-2.5 px-2 hover:bg-slate-50 rounded-xl transition-colors text-xs flex items-center justify-between">
                <div className="min-w-0 pr-4">
                  <p className="font-semibold text-slate-900 truncate">{el.name || '(unnamed element)'}</p>
                  <p className="text-[11px] font-mono text-slate-400 truncate mt-0.5">
                    {el.resource_id || el.class_name || 'android.view.View'}
                  </p>
                </div>
                {el.bounds && (
                  <div className="shrink-0 text-right">
                    <span className="font-mono text-[10px] px-2 py-0.5 rounded-md bg-amber-50 text-amber-700 border border-amber-200 font-bold">
                      [{el.bounds.left}, {el.bounds.top}, {el.bounds.right}, {el.bounds.bottom}]
                    </span>
                  </div>
                )}
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
};
