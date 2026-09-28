import React from 'react';
import { AlertCircle, GitFork, Sparkles, Crosshair } from 'lucide-react';
import { formatCurrency } from '../../varianceEngine';

interface KpiSummaryBarProps {
  netVariance: number;
  penetratedNodesCount: number;
  riskTier: string;
  topOverrunNodeId?: string;
  onOpenAiDossier: () => void;
  onInspectTopNode: () => void;
}

export const KpiSummaryBar: React.FC<KpiSummaryBarProps> = ({
  netVariance,
  penetratedNodesCount,
  riskTier,
  topOverrunNodeId,
  onOpenAiDossier,
  onInspectTopNode,
}) => {
  const isOverrun = netVariance > 0;
  const isFavorable = netVariance < 0;

  return (
    <div className="w-full px-4 py-1.5 bg-white flex flex-wrap items-center justify-between border-b border-slate-200/80 text-xs shrink-0">
      {/* KPI Metrics */}
      <div className="flex items-center gap-4 flex-wrap">
        {/* Net Variance */}
        <div className="flex items-center gap-1.5">
          <AlertCircle
            className={`w-4 h-4 ${
              isOverrun ? 'text-rose-700' : isFavorable ? 'text-emerald-700' : 'text-slate-500'
            }`}
          />
          <span className="font-semibold text-slate-700">Net Variance:</span>
          <span
            className={`font-mono text-sm font-bold ${
              isOverrun ? 'text-rose-700' : isFavorable ? 'text-emerald-700' : 'text-slate-800'
            }`}
          >
            {formatCurrency(netVariance)}
          </span>
        </div>

        <div className="h-3.5 w-px bg-slate-200/80" />

        {/* Penetrated Nodes */}
        <div className="flex items-center gap-1.5">
          <GitFork className="w-3.5 h-3.5 text-slate-500" />
          <span className="text-slate-500">Penetrated Nodes:</span>
          <span className="font-mono font-semibold text-slate-900">
            {penetratedNodesCount} Penetrated Nodes
          </span>
        </div>

        <div className="h-3.5 w-px bg-slate-200/80" />

        {/* Risk Assessment Tier */}
        <div className="flex items-center gap-1.5">
          <span
            className={`w-2 h-2 rounded-full ${
              penetratedNodesCount > 0 ? 'bg-rose-500 animate-ping' : 'bg-emerald-500'
            }`}
          />
          <span
            className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded border ${
              riskTier.includes('High')
                ? 'text-rose-700 bg-rose-50/80 border-rose-200/80'
                : riskTier.includes('Moderate')
                ? 'text-amber-700 bg-amber-50/80 border-amber-200/80'
                : 'text-emerald-700 bg-emerald-50/80 border-emerald-200/80'
            }`}
          >
            {riskTier}
          </span>
        </div>
      </div>

      {/* Quick Action Buttons */}
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={onOpenAiDossier}
          className="h-6 px-2.5 bg-slate-100 hover:bg-slate-200/80 text-slate-800 border border-slate-300/80 font-medium text-xs rounded flex items-center gap-1.5 transition-colors shadow-2xs"
        >
          <Sparkles className="w-3 h-3 text-sky-600" />
          <span>[Open AI Audit Dossier]</span>
        </button>
        {topOverrunNodeId && (
          <button
            type="button"
            onClick={onInspectTopNode}
            className="h-6 px-2.5 bg-white hover:bg-slate-50 text-slate-700 border border-slate-300/80 font-medium text-xs rounded flex items-center gap-1.5 transition-colors shadow-2xs"
          >
            <Crosshair className="w-3 h-3 text-slate-600" />
            <span>[Inspect Node: {topOverrunNodeId}]</span>
          </button>
        )}
      </div>
    </div>
  );
};

