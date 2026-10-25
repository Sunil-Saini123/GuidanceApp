import React from 'react';
import { TelemetryEvent } from '../types';
import { Brain, Cpu } from 'lucide-react';

interface AiInspectorTabProps {
  events: TelemetryEvent[];
}

export const AiInspectorTab: React.FC<AiInspectorTabProps> = ({ events }) => {
  const aiEvents = events.filter(
    (e) =>
      e.type === 'INTENT_PARSED' ||
      e.type === 'AI_PROMPT_SENT' ||
      e.type === 'AI_RESPONSE_RECEIVED' ||
      e.type === 'REROUTE_TRIGGERED'
  );

  return (
    <div className="flex-1 flex flex-col min-h-0 bg-[#f8fafc] px-6 pb-6">
      <div className="py-3.5 border-b border-slate-200/80 flex items-center justify-between">
        <div className="flex items-center space-x-2">
          <Brain className="w-4 h-4 text-purple-600" />
          <span className="text-xs font-bold text-slate-900">AI Intent & Pathfinding Engine</span>
        </div>
        <span className="text-xs font-mono font-medium text-slate-400">
          Tier 1 (Local) → Tier 2 (Firestore) → Tier 3 (Groq)
        </span>
      </div>

      <div className="flex-1 overflow-y-auto divide-y divide-slate-100 mt-3 bg-white rounded-2xl border border-slate-200/80 shadow-xs p-4">
        {aiEvents.length === 0 ? (
          <div className="text-center py-20 text-slate-400 text-xs font-medium">
            No AI or intent events recorded yet. Type or speak a query on your phone.
          </div>
        ) : (
          aiEvents.map((event) => (
            <div key={event.id} className="py-4 hover:bg-slate-50/70 rounded-xl px-2.5 transition-colors">
              <div className="flex items-start justify-between">
                <div className="flex items-start space-x-3.5">
                  <div className="p-2 rounded-xl bg-purple-50 border border-purple-100 text-purple-600 mt-0.5 shadow-2xs">
                    <Cpu className="w-4 h-4" />
                  </div>
                  <div>
                    <div className="flex items-center space-x-2">
                      <span className="text-xs font-mono font-bold text-purple-700">
                        {event.title}
                      </span>
                      <span className="text-[10px] font-mono font-bold px-2 py-0.5 rounded-md bg-slate-100 border border-slate-200 text-slate-600">
                        {event.tag}
                      </span>
                    </div>
                    <p className="text-xs text-slate-600 mt-1 font-medium">{event.description}</p>

                    {event.payload && (
                      <div className="mt-2.5">
                        <pre className="text-[11px] font-mono bg-slate-50 p-3 rounded-xl border border-slate-200 overflow-x-auto text-slate-800 shadow-2xs">
                          {typeof event.payload === 'string'
                            ? event.payload
                            : JSON.stringify(event.payload, null, 2)}
                        </pre>
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
          ))
        )}
      </div>
    </div>
  );
};
