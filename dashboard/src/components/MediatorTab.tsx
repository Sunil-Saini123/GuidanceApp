import React from 'react';
import { TelemetryEvent } from '../types';
import { Layers, ShieldCheck, ArrowDownCircle, RefreshCw, CheckCircle2 } from 'lucide-react';

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
    <div className="flex-1 flex flex-col min-h-0 bg-[#f8fafc] px-6 pb-6">
      {/* Mediator Performance Metrics */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 py-4 border-b border-slate-200/80">
        <div className="bg-white border border-slate-200/80 rounded-2xl p-4 flex items-center space-x-3.5 shadow-xs hover:shadow-md transition-all">
          <div className="p-2.5 rounded-xl bg-emerald-50 border border-emerald-100 text-emerald-600 shadow-2xs">
            <ShieldCheck className="w-5 h-5" />
          </div>
          <div>
            <p className="text-[11px] font-mono text-slate-400 font-bold uppercase tracking-wider">Redundant Dumps Prevented</p>
            <p className="text-xl font-bold text-emerald-600 font-mono mt-0.5">{totalSkipped}</p>
            <p className="text-xs text-slate-500 font-medium">Zero disk writes on repeated scrolls</p>
          </div>
        </div>

        <div className="bg-white border border-slate-200/80 rounded-2xl p-4 flex items-center space-x-3.5 shadow-xs hover:shadow-md transition-all">
          <div className="p-2.5 rounded-xl bg-blue-50 border border-blue-100 text-blue-600 shadow-2xs">
            <ArrowDownCircle className="w-5 h-5" />
          </div>
          <div>
            <p className="text-[11px] font-mono text-slate-400 font-bold uppercase tracking-wider">Delta Updates Processed</p>
            <p className="text-xl font-bold text-blue-600 font-mono mt-0.5">{totalDeltas}</p>
            <p className="text-xs text-slate-500 font-medium">Only genuine new elements added</p>
          </div>
        </div>

        <div className="bg-white border border-slate-200/80 rounded-2xl p-4 flex items-center space-x-3.5 shadow-xs hover:shadow-md transition-all">
          <div className="p-2.5 rounded-xl bg-indigo-50 border border-indigo-100 text-indigo-600 shadow-2xs">
            <Layers className="w-5 h-5" />
          </div>
          <div>
            <p className="text-[11px] font-mono text-slate-400 font-bold uppercase tracking-wider">Total New Elements Synced</p>
            <p className="text-xl font-bold text-indigo-600 font-mono mt-0.5">{totalElementsSaved}</p>
            <p className="text-xs text-slate-500 font-medium">Pushed to database incrementally</p>
          </div>
        </div>
      </div>

      {/* Stream of Mediator Events */}
      <div className="flex-1 overflow-y-auto divide-y divide-slate-100 mt-4 bg-white rounded-2xl border border-slate-200/80 shadow-xs p-4">
        {mediatorEvents.length === 0 ? (
          <div className="text-center py-20">
            <RefreshCw className="w-8 h-8 text-slate-400 mx-auto mb-2 animate-spin" />
            <p className="text-sm text-slate-600 font-semibold">Awaiting scroll events from device...</p>
            <p className="text-xs text-slate-400 mt-1">
              Scroll through any screen in Settings to see the live Mediator diff in action.
            </p>
          </div>
        ) : (
          mediatorEvents.map((event) => {
            const data = event.mediatorData;
            const isSkipped = data?.action === 'SKIPPED_NO_CHANGE';

            return (
              <div key={event.id} className="py-4 px-3 hover:bg-slate-50/80 rounded-xl transition-colors">
                <div className="flex items-start justify-between">
                  <div className="flex items-start space-x-3.5">
                    <div
                      className={`p-2 rounded-xl border mt-0.5 shadow-2xs ${
                        isSkipped
                          ? 'bg-slate-100 text-slate-500 border-slate-200'
                          : 'bg-emerald-50 text-emerald-600 border-emerald-200'
                      }`}
                    >
                      {isSkipped ? <ShieldCheck className="w-4 h-4" /> : <CheckCircle2 className="w-4 h-4" />}
                    </div>

                    <div>
                      <div className="flex items-center space-x-2">
                        <span
                          className={`text-xs font-mono font-bold px-2 py-0.5 rounded-md border ${
                            isSkipped
                              ? 'bg-slate-100 text-slate-600 border-slate-200'
                              : 'bg-emerald-50 text-emerald-700 border-emerald-200'
                          }`}
                        >
                          {data?.action || 'DIFF_CHECK'}
                        </span>
                        <span className="text-xs font-bold text-slate-900">
                          {event.title}
                        </span>
                      </div>

                      <p className="text-xs text-slate-500 mt-1 font-mono font-medium">
                        Visible on Screen: <span className="text-blue-600 font-bold">{data?.totalVisible || 0}</span> | 
                        Already Known in DB: <span className="text-slate-700 font-bold">{data?.previouslyKnown || 0}</span>
                      </p>

                      {/* Delta New Items */}
                      {data?.newAdded && data.newAdded.length > 0 && (
                        <div className="mt-2.5">
                          <p className="text-[11px] font-mono text-emerald-700 font-bold uppercase tracking-wide">
                            Newly Discovered Elements ({data.newAdded.length}):
                          </p>
                          <div className="flex flex-wrap gap-1.5 mt-1">
                            {data.newAdded.map((name, i) => (
                              <span
                                key={i}
                                className="text-xs font-mono font-semibold px-2.5 py-0.5 rounded-lg bg-emerald-50 text-emerald-700 border border-emerald-200 shadow-2xs"
                              >
                                + {name}
                              </span>
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                  </div>

                  <span className="text-xs font-mono text-slate-400 font-medium shrink-0">
                    {new Date(event.timestamp).toLocaleTimeString([], {
                      hour12: false,
                      hour: '2-digit',
                      minute: '2-digit',
                      second: '2-digit',
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
