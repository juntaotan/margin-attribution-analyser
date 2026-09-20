import React from 'react';
import { ReconciliationPath } from '../../analysisGraph';
import { formatCurrency } from '../../varianceEngine';

interface ReconciliationPathsPanelProps {
  paths: ReconciliationPath[] | null;
  error: string | null;
}

/** Displays each complete material-to-boundary path returned by the backend. */
export const ReconciliationPathsPanel: React.FC<ReconciliationPathsPanelProps> = ({ paths, error }) => (
  <section className="px-4 py-2 bg-white border-b border-slate-200 shrink-0" aria-label="Cost difference paths">
    <div className="flex items-center justify-between gap-2 mb-1">
      <h3 className="text-xs font-bold text-slate-800">Cost difference paths</h3>
      {paths && <span className="text-[10px] font-mono text-rose-700">{paths.length} path(s)</span>}
    </div>
    {error ? (
      <p className="text-xs text-rose-700">{error}</p>
    ) : paths === null ? (
      <p className="text-xs text-slate-500">Select a comparable period and run analysis to see complete paths.</p>
    ) : paths.length === 0 ? (
      <p className="text-xs text-slate-500">No path reached a node above the cost stop threshold.</p>
    ) : (
      <ol className="max-h-32 overflow-y-auto space-y-1">
        {paths.map((path, index) => (
          <li key={`${index}-${path.edges.map((edge) => edge.edgeIndex).join('-')}`}
              className="flex flex-wrap items-center gap-2 rounded border border-rose-100 bg-rose-50 px-2 py-1 text-xs">
            <span className="font-semibold text-rose-700">#{index + 1}</span>
            <span className="font-mono text-slate-800 break-all">
              {path.nodes.map((node) => node.inventoryId).join(' → ')}
            </span>
            <span className="ml-auto font-mono font-semibold text-rose-700">
              Δ {formatCurrency(path.endingCostDifference)}
            </span>
          </li>
        ))}
      </ol>
    )}
  </section>
);
