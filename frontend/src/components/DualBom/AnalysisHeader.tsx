import React from 'react';
import { Printer, Share2, FileSpreadsheet } from 'lucide-react';

interface AnalysisHeaderProps {
  onExport?: () => void;
  onPrint?: () => void;
  onShare?: () => void;
}

export const AnalysisHeader: React.FC<AnalysisHeaderProps> = ({
  onExport,
  onPrint,
  onShare,
}) => {
  return (
    <div className="w-full bg-white border-b border-slate-200 px-4 py-2 flex flex-wrap items-center justify-between gap-y-2 shrink-0">
      <div className="flex flex-col gap-0.5">
        <div className="flex items-center gap-2.5 flex-wrap">
          <div className="flex items-center gap-1.5">
            <span className="w-5 h-5 rounded bg-blue-50 text-blue-600 flex items-center justify-center font-bold text-xs">
              Δ
            </span>
            <h1 className="text-sm font-bold tracking-tight text-slate-900">
              Dual-BOM MAS Variance Analysis System
            </h1>
          </div>
          <span className="text-[11px] font-mono bg-slate-100 px-1.5 py-0.5 rounded text-slate-600 border border-slate-200">
            v4.8.2-PROD
          </span>
          <div className="flex items-center gap-1.5 px-2 py-0.5 bg-emerald-50 text-emerald-700 border border-emerald-200 rounded text-[11px] font-medium">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse" />
            <span>Engine: Connected / In-Spec</span>
          </div>
        </div>
        <p className="text-[11px] text-slate-500">
          Multi-echelon discrete manufacturing BOM topology &amp; production variance audit workbench
        </p>
      </div>

      <div className="flex items-center gap-1.5">
        <button
          type="button"
          onClick={onPrint || (() => window.print())}
          className="h-7 px-2 bg-white hover:bg-slate-50 border border-slate-200 rounded text-slate-600 hover:text-slate-900 flex items-center transition-colors shadow-xs"
          title="Print Topology"
        >
          <Printer className="w-3.5 h-3.5" />
        </button>
        <button
          type="button"
          onClick={onShare}
          className="h-7 px-2 bg-white hover:bg-slate-50 border border-slate-200 rounded text-slate-600 hover:text-slate-900 flex items-center transition-colors shadow-xs"
          title="Share Workbench"
        >
          <Share2 className="w-3.5 h-3.5" />
        </button>
        <button
          type="button"
          onClick={onExport}
          className="h-7 px-3 bg-blue-600 hover:bg-blue-700 text-white rounded font-medium text-xs flex items-center gap-1.5 shadow-xs transition-colors"
        >
          <FileSpreadsheet className="w-3.5 h-3.5" />
          <span>Export Analysis</span>
        </button>
      </div>
    </div>
  );
};
