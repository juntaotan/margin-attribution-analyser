import React, { useEffect, useRef, useState } from 'react';
import type { Viewport } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import type { AnalysisOutcome } from '../../analysisRun';
import type { StreamNode } from '../../analysisGraph';
import {
  INITIAL_QUERY,
  type DisplayItem,
  type DisplayQuery,
  type DisplayResult,
  type Period,
} from '../../graphDisplay';
import { COLUMN, GraphPane } from './GraphPane';

interface DualBomTreeCanvasProps {
  outcome: Pick<AnalysisOutcome, 'analysisId' | 'actual' | 'comparable' | 'branches'>;
  comparisonAvailable: boolean;
  currency: string;
  onSelectNode: (node: StreamNode) => void;
  onCompareProduct: (inventoryId: string) => void;
}
const COVERAGE = [20, 40, 50, 60, 80, 100];

type WorkerReply = { sequence: number; result?: DisplayResult; error?: string };

/**
 * Keeps both period graphs in a display worker and requests a bounded projection for each query. Replies to
 * superseded requests are ignored, and `busy` is set until the latest request is answered. Expansions are kept in
 * an undo history; changing the coverage or collapsing all clears it.
 */
function useGraphProjection(outcome: DualBomTreeCanvasProps['outcome']) {
  const [query, updateQuery] = useState<DisplayQuery>(INITIAL_QUERY);
  const [history, setHistory] = useState<DisplayQuery[]>([]);
  const [result, setResult] = useState<DisplayResult | null>(null);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const worker = useRef<Worker | null>(null);
  const sequence = useRef(0);
  const queryRef = useRef(query);
  queryRef.current = query;
  const setQuery = (next: DisplayQuery) => {
    // Invalidate queued replies immediately, before the next worker request effect.
    sequence.current++;
    setBusy(true);
    updateQuery(next);
  };

  useEffect(() => {
    const displayWorker = new Worker(new URL('../../graphDisplay.worker.ts', import.meta.url), { type: 'module' });
    worker.current = displayWorker;
    displayWorker.onmessage = (event: MessageEvent<WorkerReply>) => {
      if (event.data.sequence !== sequence.current) return;
      setBusy(false);
      if (event.data.error) {
        setError(event.data.error);
        setResult(null);
      } else {
        setResult(event.data.result!);
        setError(null);
      }
    };
    displayWorker.onerror = () => {
      setBusy(false);
      setError('Graph display worker failed.');
      setResult(null);
    };
    displayWorker.postMessage({
      sequence: ++sequence.current,
      input: { actual: outcome.actual, comparable: outcome.comparable, branches: outcome.branches },
      query: queryRef.current,
    });
    return () => {
      sequence.current++;
      displayWorker.terminate();
      worker.current = null;
    };
  }, [outcome]);
  useEffect(() => {
    setBusy(true);
    worker.current?.postMessage({ sequence: ++sequence.current, query });
  }, [query]);

  return {
    query,
    result,
    busy,
    error,
    canUndo: history.length > 0,
    expand: (expandKey: string) => {
      setHistory((previous) => [...previous, query]);
      setQuery({ ...query, expanded: { ...query.expanded, [expandKey]: (query.expanded[expandKey] || 0) + 1 } });
    },
    undo: () => {
      setQuery(history[history.length - 1]);
      setHistory(history.slice(0, -1));
    },
    collapseAll: () => {
      setHistory([]);
      setQuery({ ...query, expanded: {} });
    },
    setCoverage: (coverage: number) => {
      setHistory([]);
      setQuery({ coverage, expanded: {} });
    },
  };
}

/**
 * Present aligned comparable/actual trees reading Cost of Goods Sold -> products -> materials.
 * The worker retains both complete graphs and returns one bounded layout; folds expand in place.
 */
export const DualBomTreeCanvas: React.FC<DualBomTreeCanvasProps> = ({
  outcome,
  currency,
  onSelectNode,
  onCompareProduct,
  comparisonAvailable,
}) => {
  const projection = useGraphProjection(outcome);
  const { query, result, busy } = projection;
  const [selected, setSelected] = useState<DisplayItem | null>(null);
  const [viewport, setViewport] = useState<Viewport>({ x: 16, y: 8, zoom: 0.75 });
  const panes = useRef<HTMLDivElement>(null);
  const fitted = useRef(false);
  useEffect(() => {
    // Fit the first projection's columns to one pane's width, keeping COGS anchored top-left.
    if (!result || fitted.current || !panes.current) return;
    fitted.current = true;
    setViewport({
      x: 16,
      y: 8,
      zoom: Math.min(0.85, Math.max(0.5, (panes.current.clientWidth / 2 - 24) / (result.columns * COLUMN))),
    });
  }, [result]);

  const activate = (item: DisplayItem, period: Period) => {
    if (busy) return;
    if (item.expandKey) {
      projection.expand(item.expandKey);
      return;
    }
    if (item.kind !== 'node') return;
    setSelected(item);
    const value = item[period];
    if (value)
      onSelectNode({
        id: `${outcome.analysisId}:${period}:node:${value.position}`,
        position: value.position,
        inventoryId: item.inventoryId!,
        quantity: Number(value.quantity),
        cost: value.cost == null ? null : Number(value.cost),
      });
  };
  const stats = result?.stats;
  return (
    <section
      data-analysis-id={outcome.analysisId}
      className="w-full h-full min-h-[400px] flex flex-col bg-surface-canvas relative"
    >
      <div className="shrink-0 border-b bg-white px-3 py-2 flex flex-wrap gap-3 items-center text-xs">
        <label>
          Initial coverage of Σ|ΔCOGS|{' '}
          <select
            aria-label="Initial COGS difference coverage"
            className="border rounded px-1 py-0.5 ml-1"
            value={query.coverage}
            disabled={busy}
            onChange={(event) => projection.setCoverage(Number(event.target.value))}
          >
            {COVERAGE.map((value) => (
              <option key={value} value={value}>
                {value}%
              </option>
            ))}
          </select>
        </label>
        {stats && (
          <span>
            {stats.selectedProducts} of {stats.products} products · cover {stats.coveredAbsolute} of{' '}
            {stats.totalAbsolute} {currency}
            {stats.coveredPerMille != null && ` (${(stats.coveredPerMille / 10).toFixed(1)}%)`} · ΔCOGS{' '}
            {stats.cogsDelta ?? 'unavailable'}
          </span>
        )}
        <button
          disabled={busy || !projection.canUndo}
          className="text-period-actual hover:underline disabled:opacity-40 font-medium"
          onClick={projection.undo}
        >
          Undo expansion
        </button>
        <button
          disabled={busy || !Object.keys(query.expanded).length}
          className="text-period-actual hover:underline disabled:opacity-40 font-medium"
          onClick={projection.collapseAll}
        >
          Collapse all
        </button>
        {selected?.product && (
          <button
            className="px-2 py-0.5 rounded bg-period-actual-subtle text-period-actual border border-period-actual-border/50 hover:bg-period-actual hover:text-white transition-colors disabled:opacity-40 font-medium"
            disabled={!comparisonAvailable}
            onClick={() => onCompareProduct(selected.inventoryId!)}
          >
            Production evidence: {selected.inventoryId}
          </button>
        )}
      </div>
      {projection.error && (
        <p
          className="px-3 py-2 text-xs text-variance-unfavourable bg-variance-unfavourable-subtle border-b border-variance-unfavourable-border/40"
          role="alert"
        >
          {projection.error}
        </p>
      )}
      {busy && (
        <p className="px-3 py-1 text-xs" role="status">
          Preparing bounded graph…
        </p>
      )}
      {stats && <ProjectionNotes stats={stats} />}
      {result && (
        <div
          ref={panes}
          className={`flex flex-1 min-h-0 ${busy ? 'pointer-events-none opacity-60' : ''}`}
          aria-busy={busy}
        >
          <GraphPane
            result={result}
            period="comparable"
            label={`Comparable ${outcome.comparable.start} to ${outcome.comparable.end}`}
            selectedKey={selected?.key ?? null}
            viewport={viewport}
            onViewport={setViewport}
            onActivate={activate}
          />
          <GraphPane
            result={result}
            period="actual"
            label={`Actual ${outcome.actual.start} to ${outcome.actual.end}`}
            selectedKey={selected?.key ?? null}
            viewport={viewport}
            onViewport={setViewport}
            onActivate={activate}
          />
        </div>
      )}
    </section>
  );
};

/** Projection size and the warnings that qualify it: excluded products, unlinked paths, budget and diagnostics. */
const ProjectionNotes: React.FC<{ stats: DisplayResult['stats'] }> = ({ stats }) => (
  <div className="px-3 py-1 text-[11px] text-slate-600 flex flex-wrap gap-x-3">
    <span>
      {stats.totalNodes} recorded nodes · {stats.businessNodes} shown · click … or +n to expand in place; Δ is actual
      minus comparable
    </span>
    {stats.unavailableProducts > 0 && (
      <span className="text-amber-700">
        {stats.unavailableProducts} products have unavailable sales cost and are excluded from coverage
      </span>
    )}
    {stats.unlinkedBranches > 0 && (
      <span className="text-amber-700">{stats.unlinkedBranches} abnormal paths have no recorded route to COGS</span>
    )}
    {stats.budgetReached && (
      <span className="text-amber-700">Display budget reached; further content stays folded</span>
    )}
    {stats.diagnostics.map((diagnostic) => (
      <span key={diagnostic} className="text-amber-700">
        {diagnostic}
      </span>
    ))}
  </div>
);
