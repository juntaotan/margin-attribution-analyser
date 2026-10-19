import React from 'react';
import {
  recorded,
  type AnalysisOutcome,
  type FgwResult,
  type FgwStart,
  type IssueRecord,
  type PairResult,
  type ProductionTree,
} from '../../analysisRun';
import { CorrespondenceHeatmap } from './CorrespondenceHeatmap';

/** Objective values of the best start and how α weights the attribute and structural terms. */
export const OverviewPanel: React.FC<{ best: FgwStart; alpha: number }> = ({ best, alpha }) => (
  <>
    <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
      {[
        ['FGW objective', best.costs.fgw],
        ['Attribute objective', best.costs.material],
        ['Structural objective', best.costs.structure],
        ['Stopping status', best.status],
      ].map(([label, value]) => (
        <div key={String(label)} className="border rounded p-3">
          <p className="text-xs text-slate-600">{label}</p>
          <p className="mt-1 font-medium tabular-nums">{typeof value === 'number' ? value.toPrecision(8) : value}</p>
        </div>
      ))}
    </div>
    <p className="text-xs text-slate-600">
      α = {alpha}; weighted attribute = {((1 - alpha) * best.costs.material).toPrecision(6)}; weighted structure ={' '}
      {(alpha * best.costs.structure).toPrecision(6)}. Lower FGW means closer correspondence under this configuration.
    </p>
  </>
);

interface CorrespondencePanelProps {
  outcome: AnalysisOutcome;
  pair?: PairResult;
  left?: ProductionTree;
  right?: ProductionTree;
  /** Remounts the heatmap when the analysis, pair or tab changes. */
  heatmapKey: string;
}

/**
 * Shows the node-correspondence heatmap, or explains why it is unavailable: missing vectors or the pair's
 * diagnostic, and the embedding statuses of the pair's occurrences.
 */
export const CorrespondencePanel: React.FC<CorrespondencePanelProps> = ({ outcome, pair, left, right, heatmapKey }) => {
  const result = pair?.comparison?.result;
  if (result && left && right) {
    return <CorrespondenceHeatmap key={heatmapKey} result={result} left={left} right={right} />;
  }
  const inPair = (occurrenceId: string) =>
    left?.nodes.some((node) => node.occurrenceId === occurrenceId) ||
    right?.nodes.some((node) => node.occurrenceId === occurrenceId);
  return (
    <div className="p-3 border rounded text-xs">
      <p>Correspondence matrix unavailable.</p>
      <p className="break-all">
        {pair?.comparison?.missingVectors.length
          ? `Missing vectors: ${pair.comparison.missingVectors.join(', ')}`
          : pair?.diagnostic}
      </p>
      {outcome.embeddings?.manifests
        .filter((manifest) => inPair(manifest.occurrenceId))
        .filter((manifest) => manifest.statuses.length)
        .map((manifest) => (
          <p key={manifest.occurrenceId} className="mt-1 break-all">
            {manifest.occurrenceId}: {manifest.statuses.join(', ')}
          </p>
        ))}
    </div>
  );
};

const ISSUE_COLUMNS = [
  'Production',
  'Issue / document',
  'Date',
  'Material',
  'Quantity',
  'Amount',
  'Output quantity',
  'Output cost',
];

/** The product's original ledger entries, as recorded, and the raw evidence of the compared trees. */
export const DataEvidencePanel: React.FC<{ issues: IssueRecord[]; trees: (ProductionTree | undefined)[] }> = ({
  issues,
  trees,
}) => (
  <>
    <p className="text-xs text-slate-600">
      Original ledger entries for this product, including all available dates. Decimal amounts are shown as recorded.
    </p>
    <div className="overflow-auto">
      <table className="w-full text-xs text-left">
        <thead>
          <tr>
            {ISSUE_COLUMNS.map((label) => (
              <th key={label} className="p-2 border-b">
                {label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {issues.map((issue) => (
            <tr key={issue.lineId}>
              {[
                issue.productionId,
                `${issue.lineId} / ${issue.documentId}`,
                issue.date,
                issue.materialId,
                issue.quantity,
                issue.amount,
                issue.outputQuantity,
                issue.outputCost,
              ].map((value, i) => (
                <td key={i} className="p-2 border-b font-mono">
                  {recorded(value)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
    {trees.map(
      (tree) =>
        tree && (
          <details key={tree.productionId}>
            <summary className="text-xs cursor-pointer">{tree.productionId} tree evidence and allocations</summary>
            <pre className="text-xs whitespace-pre-wrap break-all bg-slate-50 p-3">
              {JSON.stringify({ nodes: tree.nodes, edges: tree.edges, statuses: tree.statuses }, null, 2)}
            </pre>
          </details>
        ),
    )}
  </>
);

interface CalculationDetailsPanelProps {
  outcome: AnalysisOutcome;
  pair?: PairResult;
  result?: FgwResult | null;
  best?: FgwStart;
  alpha: number;
}

/** Convergence status and the solver's raw numerical details for every start. */
export const CalculationDetailsPanel: React.FC<CalculationDetailsPanelProps> = ({
  outcome,
  pair,
  result,
  best,
  alpha,
}) => (
  <div className="space-y-3 text-xs">
    <p>
      {best?.status === 'FW_GAP_REACHED'
        ? 'Converged by Frank–Wolfe gap.'
        : `Convergence not established: ${best?.status || 'No numerical result'}.`}
    </p>
    <pre className="whitespace-pre-wrap break-all bg-slate-50 p-3">
      {JSON.stringify(
        {
          rows: result?.rows,
          columns: result?.columns,
          alpha,
          bestStart: result?.bestStart,
          milliseconds: result?.totalMilliseconds,
          starts: result?.starts.map((start) => ({
            status: start.status,
            appliedUpdates: start.iterations,
            finalGap: start.finalGap,
            diagnostic: start.diagnostic,
            marginals: start.marginals,
            capturedTrace: start.trace,
          })),
          embeddingVersions: pair?.comparison?.embeddingVersions,
          execution: outcome.execution,
          diagnostics: outcome.diagnostics,
          identityStatuses: outcome.selection.identities,
        },
        null,
        2,
      )}
    </pre>
  </div>
);
