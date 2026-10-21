import React from 'react';
import { TelemetryEvent } from '../types';
import { Layers, ShieldCheck, ArrowDownCircle, AlertCircle, RefreshCw, CheckCircle2 } from 'lucide-react';

interface MediatorTabProps {
  events: TelemetryEvent[];
}

export const MediatorTab: React.FC<MediatorTabProps> = ({ events }) => {
  const mediatorEvents = events.filter((e) => e.type === 'MEDIATOR_DIFF');

  // Calculate statistics
  const totalSkipped = mediatorEvents.filter(
    (e) => e.mediatorData?.action === 'SKIPPED_NO_CHANGE'
  ).length;

  const totalDeltas = mediatorEvents.filter(
    (e) => e.mediatorData?.action === 'DELTA_ADDED'
  ).length;

  const totalElementsSaved = mediatorEvents.reduce(
    (sum, e) => sum + (e.mediatorData?.newAdded?.length || 0),
    0
  );

  return (
    <div className="flex-1 flex flex-col min-h-0 bg-dark-900 px-6 pb-6">
      {/* Mediator Performance Metrics */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 py-4 border-b border-dark-800">
        <div className="bg-dark-800 border border-dark-700 rounded-xl p-4 flex items-center space-x-3">
          <div className="p-2 rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-emerald-400">
            <ShieldCheck className="w-5 h-5" />
          </div>
          <div>
            <p className="text-xs font-mono text-gray-400 uppercase">Redundant Dumps Prevented</p>
            <p className="text-xl font-bold text-emerald-400 font-mono mt-0.5">{totalSkipped}</p>
            <p className="text-[11px] text-gray-500">Zero disk writes on repeated scrolls</p>
          </div>
        </div>

        <div className="bg-dark-800 border border-dark-700 rounded-xl p-4 flex items-center space-x-3">
          <div className="p-2 rounded-lg bg-blue-500/10 border border-blue-500/20 text-blue-400">
            <ArrowDownCircle className="w-5 h-5" />
          </div>
          <div>
            <p className="text-xs font-mono text-gray-400 uppercase">Delta Updates Processed</p>
            <p className="text-xl font-bold text-blue-400 font-mono mt-0.5">{totalDeltas}</p>
            <p className="text-[11px] text-gray-500">Only genuine new elements added</p>
          </div>
        </div>

        <div className="bg-dark-800 border border-dark-700 rounded-xl p-4 flex items-center space-x-3">
          <div className="p-2 rounded-lg bg-purple-500/10 border border-purple-500/20 text-purple-400">
            <Layers className="w-5 h-5" />
          </div>
          <div>
            <p className="text-xs font-mono text-gray-400 uppercase">Total New Elements Synced</p>
            <p className="text-xl font-bold text-purple-400 font-mono mt-0.5">{totalElementsSaved}</p>
            <p className="text-[11px] text-gray-500">Pushed to database incrementally</p>
          </div>
        </div>
      </div>

      {/* Stream of Mediator Events */}
      <div className="flex-1 overflow-y-auto divide-y divide-dark-800 mt-4">
        {mediatorEvents.length === 0 ? (
          <div className="text-center py-20">
            <RefreshCw className="w-8 h-8 text-gray-600 mx-auto mb-2" />
            <p className="text-sm text-gray-400 font-medium">Awaiting scroll events from device...</p>
            <p className="text-xs text-gray-600 mt-1">
              Scroll through any screen in Settings to see the live Mediator diff in action.
            </p>
          </div>
        ) : (
          mediatorEvents.map((event) => {
            const data = event.mediatorData;
            const isSkipped = data?.action === 'SKIPPED_NO_CHANGE';

            return (
              <div key={event.id} className="py-4 px-2 hover:bg-dark-800/30 rounded-lg transition-colors">
                <div className="flex items-start justify-between">
                  <div className="flex items-start space-x-3">
                    <div
                      className={`p-1.5 rounded-lg border mt-0.5 ${
                        isSkipped
                          ? 'bg-gray-800 text-gray-400 border-gray-700'
                          : 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30'
                      }`}
                    >
                      {isSkipped ? <ShieldCheck className="w-4 h-4" /> : <CheckCircle2 className="w-4 h-4" />}
                    </div>

                    <div>
                      <div className="flex items-center space-x-2">
                        <span
                          className={`text-xs font-mono px-2 py-0.5 rounded border ${
                            isSkipped
                              ? 'bg-gray-800 text-gray-400 border-gray-700'
                              : 'bg-emerald-500/10 text-emerald-300 border-emerald-500/30 font-semibold'
                          }`}
                        >
                          {data?.action || 'DIFF_CHECK'}
                        </span>
                        <span className="text-xs font-semibold text-gray-200">
                          {event.title}
                        </span>
                      </div>

                      <p className="text-xs text-gray-400 mt-1 font-mono">
                        Visible on Screen: <span className="text-blue-400">{data?.totalVisible || 0}</span> | 
                        Already Known in DB: <span className="text-gray-300">{data?.previouslyKnown || 0}</span>
                      </p>

                      {/* Delta New Items */}
                      {data?.newAdded && data.newAdded.length > 0 && (
                        <div className="mt-2.5">
                          <p className="text-[11px] font-mono text-emerald-400 uppercase tracking-wide">
                            Newly Discovered Elements ({data.newAdded.length}):
                          </p>
                          <div className="flex flex-wrap gap-1.5 mt-1">
                            {data.newAdded.map((name, i) => (
                              <span
                                key={i}
                                className="text-xs font-mono px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-300 border border-emerald-500/30"
                              >
                                + {name}
                              </span>
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                  </div>

                  <span className="text-xs font-mono text-gray-500 shrink-0">
                    {new Date(event.timestamp).toLocaleTimeString([], {
                      hour12: false,
                      hour: '2-digit',
                      minute: '2-digit',
                      second: '2-digit',
                      fractionalSecondDigits: 3,
                    })}
                  </span>
                </div>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
};
