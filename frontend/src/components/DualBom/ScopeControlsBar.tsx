import React from 'react';
import { Calendar, Play } from 'lucide-react';

interface ScopeControlsBarProps {
  periodFrom: string;
  setPeriodFrom: (val: string) => void;
  periodTo: string;
  setPeriodTo: (val: string) => void;
  threshold: number;
  setThreshold: (val: number) => void;
  comparablePeriodFrom: string;
  setComparablePeriodFrom: (val: string) => void;
  comparablePeriodTo: string;
  setComparablePeriodTo: (val: string) => void;
  leafThreshold: number;
  setLeafThreshold: (val: number) => void;
  isAnalyzing: boolean;
  onRunAnalysis: () => void;
}

export const ScopeControlsBar: React.FC<ScopeControlsBarProps> = ({
  periodFrom,
  setPeriodFrom,
  periodTo,
  setPeriodTo,
  threshold,
  setThreshold,
  comparablePeriodFrom,
  setComparablePeriodFrom,
  comparablePeriodTo,
  setComparablePeriodTo,
  leafThreshold,
  setLeafThreshold,
  isAnalyzing,
  onRunAnalysis,
}) => {
  return (
    <div className="w-full px-4 py-2 bg-slate-50 flex flex-wrap items-center justify-between gap-2.5 border-b border-slate-200 text-xs shrink-0">
      {/* Analysis periods */}
      <div className="flex items-center gap-3 flex-wrap">
        {/* Date Range */}
        <div className="flex items-center gap-1.5 flex-wrap">
          <span className="text-[10px] font-bold text-slate-500 uppercase tracking-wider">
            Start Date:
          </span>
          <div className="flex items-center bg-white border border-slate-200 px-2 py-0.5 rounded gap-1 font-mono text-xs text-slate-800 shadow-xs">
            <Calendar className="w-3 h-3 text-slate-400 pointer-events-none" />
            <input
              type="date"
              value={periodFrom}
              onChange={(e) => setPeriodFrom(e.target.value)}
              className="border-none p-0 bg-transparent text-xs font-mono focus:ring-0 text-slate-800"
            />
          </div>

          <span className="text-slate-400 font-mono">➔</span>

          <span className="text-[10px] font-bold text-slate-500 uppercase tracking-wider">
            End Date:
          </span>
          <div className="flex items-center bg-white border border-slate-200 px-2 py-0.5 rounded gap-1 font-mono text-xs text-slate-800 shadow-xs">
            <Calendar className="w-3 h-3 text-slate-400 pointer-events-none" />
            <input
              type="date"
              value={periodTo}
              onChange={(e) => setPeriodTo(e.target.value)}
              className="border-none p-0 bg-transparent text-xs font-mono focus:ring-0 text-slate-800"
            />
          </div>
        </div>
      </div>
      <div className="flex items-center gap-2 flex-wrap border-l border-slate-200 pl-3">
        <span className="text-[10px] font-bold text-slate-500 uppercase">Compare period:</span>
        <input type="date" aria-label="Comparable period start" value={comparablePeriodFrom}
          onChange={(event) => setComparablePeriodFrom(event.target.value)}
          className="bg-white border border-slate-200 rounded px-1.5 py-0.5 font-mono text-xs" />
        <span className="text-slate-400">to</span>
        <input type="date" aria-label="Comparable period end" value={comparablePeriodTo}
          onChange={(event) => setComparablePeriodTo(event.target.value)}
          className="bg-white border border-slate-200 rounded px-1.5 py-0.5 font-mono text-xs" />
        <label className="flex items-center gap-1 text-[10px] font-bold text-slate-500 uppercase">
          Material θ:
          <input type="number" aria-label="Material cost threshold" min="0" step="any"
            value={leafThreshold} onChange={(event) => setLeafThreshold(Number(event.target.value))}
            className="w-20 bg-white border border-slate-200 rounded px-1.5 py-0.5 text-right font-mono text-xs text-slate-800" />
        </label>
      </div>

      {/* Group 2: Threshold & Trace Method & Run Button */}
      <div className="flex items-center gap-3 flex-wrap">
        {/* Variance Threshold Input */}
        <div className="flex items-center gap-1.5">
          <span className="text-[10px] font-bold text-slate-500 uppercase tracking-wider">
            Cost stop θ:
          </span>
          <div className="flex items-center bg-white border border-slate-200 rounded px-1.5 py-0.5 shadow-xs">
            <span className="text-slate-400 font-mono text-xs pr-1">$</span>
            <input
              type="number"
              value={threshold}
              onChange={(e) => setThreshold(parseFloat(e.target.value) || 0)}
              className="w-16 text-right font-mono text-xs text-slate-800 border-none p-0 focus:ring-0 focus:outline-hidden"
              step="50"
              min="0"
            />
          </div>
        </div>

        {/* Execute Button */}
        <button
          type="button"
          onClick={onRunAnalysis}
          className="h-6 px-3 bg-blue-600 hover:bg-blue-700 active:scale-98 text-white rounded font-medium text-xs flex items-center gap-1 shadow-xs transition-all"
        >
          <Play className={`w-3 h-3 fill-current ${isAnalyzing ? 'animate-spin' : ''}`} />
          <span>{isAnalyzing ? 'Run Again' : 'Run Analysis'}</span>
        </button>
      </div>
    </div>
  );
};
