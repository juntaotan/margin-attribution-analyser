import React, { useState } from 'react';
import { Calendar, Play, SlidersHorizontal, ChevronDown, ChevronUp } from 'lucide-react';

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
  const [thresholdsOpen, setThresholdsOpen] = useState(false);

  return (
    <div className="w-full px-4 py-2 bg-[#f8fafc] flex flex-col gap-2 border-b border-slate-200/80 text-xs shrink-0 transition-all">
      <div className="flex flex-wrap items-center justify-between gap-2.5">
        {/* Permanent Group 1: Analysis periods (Dates) */}
        <div className="flex items-center gap-3 flex-wrap">
          {/* Actual Date Range */}
          <div className="flex items-center gap-1.5 flex-wrap">
            <span className="text-[10px] font-bold text-slate-500 uppercase tracking-wider">
              Start Date:
            </span>
            <div className="flex items-center bg-white border border-slate-300/80 hover:border-slate-400 focus-within:border-slate-600 focus-within:ring-1 focus-within:ring-slate-300 px-2 py-0.5 rounded gap-1 font-mono text-xs text-slate-800 shadow-2xs transition-colors">
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
            <div className="flex items-center bg-white border border-slate-300/80 hover:border-slate-400 focus-within:border-slate-600 focus-within:ring-1 focus-within:ring-slate-300 px-2 py-0.5 rounded gap-1 font-mono text-xs text-slate-800 shadow-2xs transition-colors">
              <Calendar className="w-3 h-3 text-slate-400 pointer-events-none" />
              <input
                type="date"
                value={periodTo}
                onChange={(e) => setPeriodTo(e.target.value)}
                className="border-none p-0 bg-transparent text-xs font-mono focus:ring-0 text-slate-800"
              />
            </div>
          </div>

          {/* Comparable Date Range */}
          <div className="flex items-center gap-2 flex-wrap border-l border-slate-200/80 pl-3">
            <span className="text-[10px] font-bold text-slate-500 uppercase">Compare:</span>
            <input
              type="date"
              aria-label="Comparable period start"
              value={comparablePeriodFrom}
              onChange={(event) => setComparablePeriodFrom(event.target.value)}
              className="bg-white border border-slate-300/80 hover:border-slate-400 focus:border-slate-600 focus:ring-1 focus:ring-slate-300 rounded px-1.5 py-0.5 font-mono text-xs shadow-2xs transition-colors"
            />
            <span className="text-slate-400">to</span>
            <input
              type="date"
              aria-label="Comparable period end"
              value={comparablePeriodTo}
              onChange={(event) => setComparablePeriodTo(event.target.value)}
              className="bg-white border border-slate-300/80 hover:border-slate-400 focus:border-slate-600 focus:ring-1 focus:ring-slate-300 rounded px-1.5 py-0.5 font-mono text-xs shadow-2xs transition-colors"
            />
          </div>
        </div>

        {/* Group 2: Thresholds Drawer Toggle & Run Button */}
        <div className="flex items-center gap-2 flex-wrap">
          <button
            type="button"
            onClick={() => setThresholdsOpen(!thresholdsOpen)}
            className={`px-2 py-0.5 rounded border text-xs flex items-center gap-1.5 transition-colors ${
              thresholdsOpen
                ? 'bg-period-actual-subtle text-period-actual-dark border-period-actual-border/50'
                : 'bg-white hover:bg-slate-50 text-slate-600 border-slate-300/80 shadow-2xs'
            }`}
            title={thresholdsOpen ? "Collapse thresholds drawer" : "Expand thresholds drawer"}
            aria-expanded={thresholdsOpen}
          >
            <SlidersHorizontal className="w-3 h-3 text-slate-500" />
            <span>Thresholds (θ)</span>
            <span className="text-[10px] text-slate-400 font-mono hidden sm:inline">
              Mat: {leafThreshold} · Stop: ${threshold}
            </span>
            {thresholdsOpen ? <ChevronUp className="w-3 h-3 text-slate-400" /> : <ChevronDown className="w-3 h-3 text-slate-400" />}
          </button>

          {/* Execute Button */}
          <button
            type="button"
            onClick={onRunAnalysis}
            className="h-6 px-3 bg-period-actual hover:bg-period-actual-dark active:bg-blue-900 text-white rounded font-medium text-xs flex items-center gap-1 shadow-xs transition-colors border border-period-actual-dark"
          >
            <Play className={`w-3 h-3 fill-current ${isAnalyzing ? 'animate-spin' : ''}`} />
            <span>{isAnalyzing ? 'Run Again' : 'Run Analysis'}</span>
          </button>
        </div>
      </div>

      {/* Collapsible Thresholds Drawer Shelf */}
      {thresholdsOpen && (
        <div className="w-full flex items-center gap-4 pt-2 border-t border-slate-200/60 flex-wrap animate-in slide-in-from-top-1">
          <label className="flex items-center gap-1.5 text-[10px] font-bold text-slate-500 uppercase">
            Material θ:
            <input
              type="number"
              aria-label="Material cost threshold"
              min="0"
              step="any"
              value={leafThreshold}
              onChange={(event) => setLeafThreshold(Number(event.target.value))}
              className="w-20 bg-white border border-slate-300/80 hover:border-slate-400 focus:border-slate-600 focus:ring-1 focus:ring-slate-300 rounded px-1.5 py-0.5 text-right font-mono text-xs text-slate-800 shadow-2xs transition-colors"
            />
          </label>

          <div className="flex items-center gap-1.5">
            <span className="text-[10px] font-bold text-slate-500 uppercase tracking-wider">
              Cost stop θ:
            </span>
            <div className="flex items-center bg-white border border-slate-300/80 hover:border-slate-400 focus-within:border-slate-600 focus-within:ring-1 focus-within:ring-slate-300 rounded px-1.5 py-0.5 shadow-2xs transition-colors">
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
        </div>
      )}
    </div>
  );
};
