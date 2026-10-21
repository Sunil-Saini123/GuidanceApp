import React from 'react';
import { TelemetryEvent } from '../types';
import { Brain, Cpu, Clock, CheckCircle2, AlertTriangle, ArrowRight } from 'lucide-react';

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
    <div className="flex-1 flex flex-col min-h-0 bg-dark-900 px-6 pb-6">
      <div className="py-3 border-b border-dark-800 flex items-center justify-between">
        <div className="flex items-center space-x-2">
          <Brain className="w-4 h-4 text-purple-400" />
          <span className="text-xs font-bold text-gray-200">AI Intent & Pathfinding Engine</span>
        </div>
        <span className="text-xs font-mono text-gray-500">
          Tier 1 (Local) → Tier 2 (Firestore) → Tier 3 (Groq)
        </span>
      </div>

      <div className="flex-1 overflow-y-auto divide-y divide-dark-800 mt-2">
        {aiEvents.length === 0 ? (
          <div className="text-center py-20 text-gray-600 text-xs">
            No AI or intent events recorded yet. Type or speak a query on your phone.
          </div>
        ) : (
          aiEvents.map((event) => (
            <div key={event.id} className="py-4 hover:bg-dark-800/30 rounded-lg px-2 transition-colors">
              <div className="flex items-start justify-between">
                <div className="flex items-start space-x-3">
                  <div className="p-1.5 rounded-lg bg-purple-500/10 border border-purple-500/20 text-purple-400 mt-0.5">
                    <Cpu className="w-4 h-4" />
                  </div>
                  <div>
                    <div className="flex items-center space-x-2">
                      <span className="text-xs font-mono font-bold text-purple-300">
                        {event.title}
                      </span>
                      <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-dark-800 border border-dark-700 text-gray-400">
                        {event.tag}
                      </span>
                    </div>
                    <p className="text-xs text-gray-300 mt-1">{event.description}</p>

                    {event.payload && (
                      <div className="mt-2.5">
                        <pre className="text-[11px] font-mono bg-dark-950 p-2.5 rounded-lg border border-dark-800 overflow-x-auto text-gray-300">
                          {typeof event.payload === 'string'
                            ? event.payload
                            : JSON.stringify(event.payload, null, 2)}
                        </pre>
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
          ))
        )}
      </div>
    </div>
  );
};
