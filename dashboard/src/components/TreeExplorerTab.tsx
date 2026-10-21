import React, { useState } from 'react';
import { TelemetryEvent } from '../types';
import { Terminal, Folder, FileText, Check, Copy } from 'lucide-react';

interface TreeExplorerTabProps {
  events: TelemetryEvent[];
}

export const TreeExplorerTab: React.FC<TreeExplorerTabProps> = ({ events }) => {
  const [copied, setCopied] = useState(false);

  // Find latest event with elements
  const latestEvent = events
    .slice()
    .reverse()
    .find((e) => e.elements && e.elements.length > 0);

  const elements = latestEvent?.elements || [];

  const handleCopy = () => {
    navigator.clipboard.writeText(JSON.stringify(elements, null, 2));
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="flex-1 flex flex-col min-h-0 bg-dark-900 px-6 pb-6">
      <div className="flex items-center justify-between py-3 border-b border-dark-800">
        <div className="flex items-center space-x-2">
          <Terminal className="w-4 h-4 text-emerald-400" />
          <span className="text-xs font-bold text-gray-200">Accessibility Tree Node Explorer</span>
        </div>
        <button
          onClick={handleCopy}
          className="flex items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-dark-800 hover:bg-dark-700 text-xs font-mono text-gray-300 border border-dark-700 transition-colors"
        >
          {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
          <span>{copied ? 'Copied JSON' : 'Copy Clean JSON'}</span>
        </button>
      </div>

      <div className="flex-1 overflow-y-auto mt-4 bg-dark-950 rounded-xl border border-dark-800 p-4 font-mono text-xs">
        {elements.length === 0 ? (
          <div className="text-center py-20 text-gray-600">
            No UI tree dump available. Trigger navigation on your phone.
          </div>
        ) : (
          <pre className="text-gray-300 leading-relaxed overflow-x-auto">
            {JSON.stringify(elements, null, 2)}
          </pre>
        )}
      </div>
    </div>
  );
};
