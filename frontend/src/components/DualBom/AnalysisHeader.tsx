import React from 'react';
import { Printer, Share2, FileSpreadsheet } from 'lucide-react';

interface AnalysisHeaderProps {
  onExport?: () => void;
  status?: string;
  onPrint?: () => void;
  onShare?: () => void;
}

export const AnalysisHeader: React.FC<AnalysisHeaderProps> = ({
  onExport,
  status,
  onPrint,
  onShare,
}) => {
  return (
    <div className="w-full bg-white border-b border-slate-200/80 px-4 py-2 flex flex-wrap items-center justify-between gap-y-2 shrink-0">
      <div className="flex flex-col gap-0.5">
        <div className="flex items-center gap-2.5 flex-wrap">
          <div className="flex items-center gap-1.5">
            <span className="w-5 h-5 rounded bg-slate-100 text-slate-800 border border-slate-200/80 flex items-center justify-center font-bold text-xs font-mono">
              Δ
            </span>
            <h1 className="text-sm font-bold tracking-tight text-slate-900">
              MarginTrace Analysis
            </h1>
          </div>
          <span className="text-[11px] font-mono bg-[#f8fafc] px-1.5 py-0.5 rounded text-slate-600 border border-slate-200/80">
            Prototype
          </span>
          <div className={`flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-medium border ${
            status?.includes('Running')
              ? 'bg-period-actual-subtle text-period-actual border-period-actual-border/50'
              : status?.includes('Failed')
              ? 'bg-variance-unfavourable-subtle text-variance-unfavourable border-variance-unfavourable-border/50'
              : status && status !== 'Awaiting analysis'
              ? 'bg-emerald-50/80 text-emerald-800 border-emerald-200/80'
              : 'bg-slate-100 text-slate-600 border-slate-200/80'
          }`}>
            <span className={`w-1.5 h-1.5 rounded-full ${
              status?.includes('Running') ? 'bg-period-actual animate-pulse' : status?.includes('Failed') ? 'bg-variance-unfavourable' : 'bg-slate-400'
            }`} />
            <span>{status || 'Awaiting analysis'}</span>
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
          className="h-7 px-2 bg-white hover:bg-slate-50 border border-slate-200/80 rounded text-slate-600 hover:text-slate-900 flex items-center transition-colors shadow-2xs"
          title="Print Topology"
        >
          <Printer className="w-3.5 h-3.5" />
        </button>
        <button
          type="button"
          onClick={onShare}
          disabled={!onShare}
          className="h-7 px-2 bg-white hover:bg-slate-50 border border-slate-200/80 rounded text-slate-600 hover:text-slate-900 flex items-center transition-colors shadow-2xs"
          title="Share Workbench"
        >
          <Share2 className="w-3.5 h-3.5" />
        </button>
        <button
          type="button"
          onClick={onExport}
          disabled={!onExport}
          className="h-7 px-3 bg-slate-900 hover:bg-slate-800 text-white rounded font-medium text-xs flex items-center gap-1.5 shadow-xs transition-colors border border-slate-800"
        >
          <FileSpreadsheet className="w-3.5 h-3.5" />
          <span>Export Analysis</span>
        </button>
      </div>
    </div>
  );
};
