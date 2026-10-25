import React, { useState } from 'react';
import { TelemetryEvent } from '../types';
import { 
  ChevronRight, 
  ChevronDown, 
  Clock, 
  CheckCircle2, 
  AlertCircle, 
  Search, 
  Filter,
  Sparkles,
  ArrowRight,
  Layers,
  MapPin
} from 'lucide-react';

interface TimelineTabProps {
  events: TelemetryEvent[];
}

export const TimelineTab: React.FC<TimelineTabProps> = ({ events }) => {
  const [filterType, setFilterType] = useState<string>('ALL');
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());

  const toggleExpand = (id: string) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const getBadge = (type: string) => {
    switch (type) {
      case 'INTENT_PARSED':
        return { bg: 'bg-purple-50 text-purple-700 border-purple-200', icon: Sparkles };
      case 'SCREEN_CHANGED':
        return { bg: 'bg-blue-50 text-blue-700 border-blue-200', icon: MapPin };
      case 'MEDIATOR_DIFF':
        return { bg: 'bg-amber-50 text-amber-700 border-amber-200', icon: Layers };
      case 'ELEMENT_FOUND':
        return { bg: 'bg-emerald-50 text-emerald-700 border-emerald-200', icon: CheckCircle2 };
      case 'WRONG_PATH':
        return { bg: 'bg-rose-50 text-rose-700 border-rose-200', icon: AlertCircle };
      case 'STEP_ADVANCED':
        return { bg: 'bg-indigo-50 text-indigo-700 border-indigo-200', icon: ArrowRight };
      default:
        return { bg: 'bg-slate-100 text-slate-700 border-slate-200', icon: Clock };
    }
  };

  const filteredEvents = events.filter((e) => {
    if (filterType !== 'ALL' && e.type !== filterType) return false;
    if (searchQuery.trim() !== '') {
      const query = searchQuery.toLowerCase();
      const matchTitle = e.title.toLowerCase().includes(query);
      const matchDesc = e.description.toLowerCase().includes(query);
      const matchTag = e.tag.toLowerCase().includes(query);
      return matchTitle || matchDesc || matchTag;
    }
    return true;
  });

  return (
    <div className="flex-1 flex flex-col min-h-0 bg-[#f8fafc] px-6 pb-6">
      {/* Filter and Search Bar */}
      <div className="flex flex-wrap items-center justify-between py-3.5 border-b border-slate-200/80 gap-3">
        <div className="flex items-center space-x-1.5 flex-wrap gap-y-1">
          <Filter className="w-4 h-4 text-slate-400 mr-1" />
          <span className="text-xs font-semibold text-slate-500 mr-1">Filter:</span>
          {['ALL', 'INTENT_PARSED', 'SCREEN_CHANGED', 'MEDIATOR_DIFF', 'ELEMENT_FOUND', 'WRONG_PATH'].map((type) => (
            <button
              key={type}
              onClick={() => setFilterType(type)}
              className={`text-xs px-3 py-1 rounded-xl font-semibold transition-all shadow-xs ${
                filterType === type
                  ? 'bg-indigo-50 text-indigo-700 border border-indigo-200'
                  : 'bg-white hover:bg-slate-100 text-slate-600 border border-slate-200/70'
              }`}
            >
              {type.replace('_', ' ')}
            </button>
          ))}
        </div>

        <div className="relative w-64">
          <Search className="w-3.5 h-3.5 absolute left-3 top-2.5 text-slate-400" />
          <input
            type="text"
            placeholder="Search decisions..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full bg-white border border-slate-200/80 text-xs rounded-xl pl-8 pr-3 py-1.5 text-slate-800 placeholder-slate-400 focus:outline-none focus:border-indigo-500 shadow-xs font-medium"
          />
        </div>
      </div>

      {/* Events Stream */}
      <div className="flex-1 overflow-y-auto divide-y divide-slate-100 mt-3 bg-white rounded-2xl border border-slate-200/80 shadow-xs p-4">
        {filteredEvents.length === 0 ? (
          <div className="text-center py-16">
            <Clock className="w-8 h-8 text-slate-400 mx-auto mb-2 animate-spin" />
            <p className="text-sm text-slate-600 font-semibold">Awaiting decision events from phone...</p>
            <p className="text-xs text-slate-400 mt-1">
              Ensure <code className="bg-slate-100 px-1.5 py-0.5 rounded text-indigo-600 font-mono font-bold">adb reverse tcp:3000 tcp:3000</code> is run.
            </p>
          </div>
        ) : (
          filteredEvents.map((event) => {
            const badge = getBadge(event.type);
            const Icon = badge.icon;
            const isExpanded = expandedIds.has(event.id);

            return (
              <div
                key={event.id}
                className="py-3 hover:bg-slate-50/70 rounded-xl px-2.5 transition-colors"
              >
                <div
                  className="flex items-start justify-between cursor-pointer select-none"
                  onClick={() => toggleExpand(event.id)}
                >
                  <div className="flex items-start space-x-3 min-w-0">
                    <button className="mt-0.5 text-slate-400 hover:text-slate-600">
                      {isExpanded ? (
                        <ChevronDown className="w-4 h-4 text-indigo-600" />
                      ) : (
                        <ChevronRight className="w-4 h-4" />
                      )}
                    </button>

                    <span
                      className={`inline-flex items-center space-x-1 px-2.5 py-0.5 rounded-lg text-[11px] font-mono font-bold border ${badge.bg}`}
                    >
                      <Icon className="w-3 h-3" />
                      <span>{event.type}</span>
                    </span>

                    <div className="min-w-0">
                      <p className="text-xs font-bold text-slate-900">
                        {event.title}
                      </p>
                      <p className="text-xs text-slate-500 mt-0.5 line-clamp-1 font-medium">
                        {event.description}
                      </p>
                    </div>
                  </div>

                  <div className="flex items-center space-x-2 text-right shrink-0">
                    <span className="text-[11px] font-mono text-slate-400">
                      {new Date(event.timestamp).toLocaleTimeString([], {
                        hour12: false,
                        hour: '2-digit',
                        minute: '2-digit',
                        second: '2-digit',
                      })}
                    </span>
                    <span className="text-[10px] font-mono px-2 py-0.5 rounded-md bg-slate-100 text-slate-600 border border-slate-200 font-bold">
                      {event.tag}
                    </span>
                  </div>
                </div>

                {/* Expanded Details */}
                {isExpanded && (
                  <div className="mt-3 ml-7 bg-slate-50 border border-slate-200 rounded-xl p-3.5 space-y-2">
                    {event.packageName && (
                      <div className="text-xs flex items-center space-x-2">
                        <span className="text-slate-500 font-mono">Package:</span>
                        <span className="text-blue-600 font-mono font-bold">{event.packageName}</span>
                      </div>
                    )}
                    {event.screenTitle && (
                      <div className="text-xs flex items-center space-x-2">
                        <span className="text-slate-500 font-mono">Screen:</span>
                        <span className="text-emerald-700 font-mono font-bold">{event.screenTitle}</span>
                      </div>
                    )}
                    {event.targetBounds && (
                      <div className="text-xs flex items-center space-x-2">
                        <span className="text-slate-500 font-mono">Bounds:</span>
                        <span className="text-amber-700 font-mono font-bold">
                          [{event.targetBounds.left}, {event.targetBounds.top}, {event.targetBounds.right}, {event.targetBounds.bottom}]
                        </span>
                      </div>
                    )}

                    {event.payload && (
                      <div className="mt-2">
                        <span className="text-[11px] font-mono text-slate-400 uppercase tracking-wider block mb-1 font-bold">
                          Raw Payload
                        </span>
                        <pre className="text-[11px] font-mono bg-white p-2.5 rounded-xl border border-slate-200 overflow-x-auto text-slate-800 shadow-2xs">
                          {JSON.stringify(event.payload, null, 2)}
                        </pre>
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })
        )}
      </div>
    </div>
  );
};
