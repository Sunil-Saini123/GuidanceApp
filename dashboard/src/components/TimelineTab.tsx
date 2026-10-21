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
        return { bg: 'bg-purple-500/10 text-purple-400 border-purple-500/30', icon: Sparkles };
      case 'SCREEN_CHANGED':
        return { bg: 'bg-blue-500/10 text-blue-400 border-blue-500/30', icon: MapPin };
      case 'MEDIATOR_DIFF':
        return { bg: 'bg-amber-500/10 text-amber-400 border-amber-500/30', icon: Layers };
      case 'ELEMENT_FOUND':
        return { bg: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30', icon: CheckCircle2 };
      case 'WRONG_PATH':
        return { bg: 'bg-rose-500/10 text-rose-400 border-rose-500/30', icon: AlertCircle };
      case 'STEP_ADVANCED':
        return { bg: 'bg-emerald-500/10 text-emerald-300 border-emerald-500/30', icon: ArrowRight };
      default:
        return { bg: 'bg-gray-800 text-gray-400 border-gray-700', icon: Clock };
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
    <div className="flex-1 flex flex-col min-h-0 bg-dark-900 px-6 pb-6">
      {/* Filter and Search Bar */}
      <div className="flex items-center justify-between py-3 border-b border-dark-800">
        <div className="flex items-center space-x-2">
          <Filter className="w-4 h-4 text-gray-500" />
          <span className="text-xs font-mono text-gray-400">Filter:</span>
          {['ALL', 'INTENT_PARSED', 'SCREEN_CHANGED', 'MEDIATOR_DIFF', 'ELEMENT_FOUND', 'WRONG_PATH'].map((type) => (
            <button
              key={type}
              onClick={() => setFilterType(type)}
              className={`text-xs px-2.5 py-1 rounded-md transition-colors ${
                filterType === type
                  ? 'bg-dark-700 text-emerald-400 border border-dark-600 font-semibold'
                  : 'text-gray-400 hover:text-gray-200 hover:bg-dark-800'
              }`}
            >
              {type.replace('_', ' ')}
            </button>
          ))}
        </div>

        <div className="relative w-64">
          <Search className="w-3.5 h-3.5 absolute left-3 top-2.5 text-gray-500" />
          <input
            type="text"
            placeholder="Search decisions..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full bg-dark-800 border border-dark-700 text-xs rounded-lg pl-8 pr-3 py-1.5 text-gray-200 placeholder-gray-500 focus:outline-none focus:border-emerald-500/50"
          />
        </div>
      </div>

      {/* Events Stream */}
      <div className="flex-1 overflow-y-auto divide-y divide-dark-800 mt-2">
        {filteredEvents.length === 0 ? (
          <div className="text-center py-16">
            <Clock className="w-8 h-8 text-gray-600 mx-auto mb-2 animate-spin" />
            <p className="text-sm text-gray-400 font-medium">Awaiting decision events from phone...</p>
            <p className="text-xs text-gray-600 mt-1">
              Ensure <code className="bg-dark-800 px-1 py-0.5 rounded text-gray-400 font-mono">adb reverse tcp:3000 tcp:3000</code> is run.
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
                className="py-3 hover:bg-dark-800/40 rounded-lg px-2 transition-colors"
              >
                <div
                  className="flex items-start justify-between cursor-pointer select-none"
                  onClick={() => toggleExpand(event.id)}
                >
                  <div className="flex items-start space-x-3 min-w-0">
                    <button className="mt-0.5 text-gray-500 hover:text-gray-300">
                      {isExpanded ? (
                        <ChevronDown className="w-4 h-4 text-emerald-400" />
                      ) : (
                        <ChevronRight className="w-4 h-4" />
                      )}
                    </button>

                    <span
                      className={`inline-flex items-center space-x-1 px-2 py-0.5 rounded text-[11px] font-mono border ${badge.bg}`}
                    >
                      <Icon className="w-3 h-3" />
                      <span>{event.type}</span>
                    </span>

                    <div className="min-w-0">
                      <p className="text-xs font-semibold text-gray-200">
                        {event.title}
                      </p>
                      <p className="text-xs text-gray-400 mt-0.5 line-clamp-1">
                        {event.description}
                      </p>
                    </div>
                  </div>

                  <div className="flex items-center space-x-2 text-right shrink-0">
                    <span className="text-[11px] font-mono text-gray-500">
                      {new Date(event.timestamp).toLocaleTimeString([], {
                        hour12: false,
                        hour: '2-digit',
                        minute: '2-digit',
                        second: '2-digit',
                        fractionalSecondDigits: 3,
                      })}
                    </span>
                    <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-dark-800 text-gray-400 border border-dark-700">
                      {event.tag}
                    </span>
                  </div>
                </div>

                {/* Expanded Details */}
                {isExpanded && (
                  <div className="mt-3 ml-7 bg-dark-950 border border-dark-800 rounded-lg p-3 space-y-2">
                    {event.packageName && (
                      <div className="text-xs flex items-center space-x-2">
                        <span className="text-gray-500 font-mono">Package:</span>
                        <span className="text-blue-400 font-mono">{event.packageName}</span>
                      </div>
                    )}
                    {event.screenTitle && (
                      <div className="text-xs flex items-center space-x-2">
                        <span className="text-gray-500 font-mono">Screen:</span>
                        <span className="text-emerald-400 font-mono">{event.screenTitle}</span>
                      </div>
                    )}
                    {event.targetBounds && (
                      <div className="text-xs flex items-center space-x-2">
                        <span className="text-gray-500 font-mono">Bounds:</span>
                        <span className="text-amber-400 font-mono">
                          [{event.targetBounds.left}, {event.targetBounds.top}, {event.targetBounds.right}, {event.targetBounds.bottom}]
                        </span>
                      </div>
                    )}

                    {event.payload && (
                      <div className="mt-2">
                        <span className="text-[11px] font-mono text-gray-500 uppercase tracking-wider block mb-1">
                          Raw Payload
                        </span>
                        <pre className="text-[11px] font-mono bg-dark-900 p-2 rounded border border-dark-800 overflow-x-auto text-gray-300">
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
