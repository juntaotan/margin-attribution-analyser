import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Background, Controls, Position, ReactFlow, MarkerType, type Edge, type Node, type Viewport } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import type { AnalysisOutcome } from '../../analysisRun';
import type { StreamNode } from '../../analysisGraph';
import { INITIAL_QUERY, type DisplayItem, type DisplayQuery, type DisplayResult, type Period } from '../../graphDisplay';

interface DualBomTreeCanvasProps {
  outcome: Pick<AnalysisOutcome, 'analysisId' | 'actual' | 'comparable' | 'branches'>;
  comparisonAvailable: boolean;
  currency: string;
  onSelectNode: (node: StreamNode) => void;
  onCompareProduct: (inventoryId: string) => void;
}
const COLUMN = 290; const ROW = 104; const HEADER = 56;
const NODE = { width: 230, height: 88 }; const FOLD = { width: 170, height: 44 };
const COVERAGE = [20, 40, 50, 60, 80, 100];
const size = (item: DisplayItem) => item.kind === 'node' || item.kind === 'missing' ? NODE : FOLD;

/** Fixed columns by display depth and rows from the worker's ranked tidy-tree order. */
const place = (item: DisplayItem) => ({ x: item.column * COLUMN + (NODE.width - size(item).width) / 2, y: HEADER + item.row * ROW + (NODE.height - size(item).height) / 2 });
const columnLabel = (result: DisplayResult, column: number) => column === 0 ? 'Cost of Goods Sold' : column === 1 ? 'Products'
  : result.terminalColumn && column === result.columns - 1 ? 'Material terminals' : `Level ${column}`;

const GraphPane: React.FC<{
  result: DisplayResult; label: string; period: Period; selectedKey: string | null; viewport: Viewport;
  onViewport: (viewport: Viewport) => void; onActivate: (item: DisplayItem, period: Period) => void;
}> = ({ result, label, period, selectedKey, viewport, onViewport, onActivate }) => {
  const lookup = useMemo(() => new Map(result.items.map(item => [item.key, item])), [result]);
  const nodes: Node[] = useMemo(() => [
    ...Array.from({ length: result.columns }, (_, column) => ({ id: `column:${column}`, className: 'column-header', position: { x: column * COLUMN, y: 0 }, selectable: false, focusable: false, draggable: false,
      data: { label: <div className="text-[11px] font-bold uppercase tracking-wide text-slate-500 text-left">{columnLabel(result, column)}</div> },
      style: { width: NODE.width, height: 32, border: 'none', borderBottom: '2px solid #cbd5e1', borderRadius: 0, background: 'transparent', padding: '6px 4px', pointerEvents: 'none' as const } })),
    ...result.items.map(item => {
      const value = item[period]; const fold = item.kind !== 'node';
      const missing = item.kind === 'node' && !value;
      return { id: item.key, position: place(item), sourcePosition: Position.Right, targetPosition: Position.Left, ariaLabel: fold ? `${item.label}: ${item.note}` : item.label,
        data: { label: fold ? <div className="text-xs font-semibold truncate" title={item.note}><span>{item.label}</span><div className="text-[10px] font-normal text-slate-600 truncate">{item.note}</div></div>
          : <div className="text-left font-sans text-xs leading-tight">
            <div className="flex gap-1 items-center"><span className="font-semibold truncate" title={item.label}>{item.label}</span>
              {item.abnormal && <span className="text-anomaly-boundary bg-anomaly-boundary-subtle border border-anomaly-boundary-border/40 font-bold text-[10px] px-1 rounded">Abnormal</span>}{item.shared && <span className="text-[10px] text-slate-500" title="Recorded receiving items in either period">×{item.shared}</span>}</div>
            {missing ? <div className="text-slate-500 mt-1">Not recorded in this period</div> : <>
              <div className="font-mono mt-1 truncate" title={value?.cost ?? 'Unavailable'}>{value?.cost ?? 'Cost unavailable'}{item.inventoryId !== '__COGS__' && <span className="text-slate-500"> · Qty {value?.quantity}</span>}</div>
              {!value?.linked && <div className="text-[10px] text-amber-700">Relationship to parent not recorded</div>}
            </>}
            <div className="font-mono text-[10px] mt-1 text-slate-600 truncate" title="Actual minus comparable">Δ {item.delta ?? 'unavailable'}{item.parentDelta !== undefined && ` · in parent ${item.parentDelta ?? 'unavailable'}`}</div>
            {item.boundary && <div className="text-[10px] text-anomaly-boundary font-medium truncate">Boundary difference {item.boundary.difference} · {item.boundary.count} paths</div>}
          </div> },
        style: { ...size(item), borderRadius: fold ? 22 : 6, padding: fold ? '4px 10px' : 8, cursor: fold && !item.expandKey ? 'default' : 'pointer',
          border: `1.5px ${fold || missing ? 'dashed' : 'solid'} ${selectedKey === item.key ? (period === 'actual' ? '#1d4ed8' : '#475569') : item.abnormal ? '#c2410c' : '#94a3b8'}`,
          background: missing ? '#f8fafc' : item.abnormal ? '#fff7ed' : fold ? '#f1f5f9' : '#fff', opacity: missing ? 0.7 : 1 } };
    })], [result, period, selectedKey]);
  const edges: Edge[] = useMemo(() => result.edges.map(edge => {
    const recorded = edge[period];
    return { id: edge.id, source: edge.source, target: edge.target, type: 'smoothstep', markerEnd: { type: MarkerType.ArrowClosed, color: '#94a3b8' },
      style: { stroke: recorded ? '#94a3b8' : '#e2e8f0', strokeDasharray: edge.folded || !recorded ? '5 5' : undefined } };
  }), [result, period]);
  const activate = (id: string) => { const item = lookup.get(id); if (item) onActivate(item, period); };
  return <div className="min-w-0 min-h-[360px] flex-1 flex flex-col border-r last:border-r-0">
    <div className={`px-3 py-2 border-b text-xs font-bold flex items-center justify-between ${
      period === 'actual'
        ? 'bg-period-actual-subtle text-period-actual-dark border-period-actual-border/40'
        : 'bg-period-comparable-subtle text-period-comparable-dark border-period-comparable-border/40'
    }`}>
      <div className="flex items-center gap-1.5">
        <span className={`w-2 h-2 rounded-full ${period === 'actual' ? 'bg-period-actual' : 'bg-period-comparable'}`} />
        <span>{label}</span>
      </div>
      <span className="text-[10px] font-mono uppercase tracking-wider opacity-75">
        {period === 'actual' ? 'Actual Execution' : 'Comparable Baseline'}
      </span>
    </div>
    <div className="flex-1 min-h-0" aria-label={`${label} graph`}>
      <ReactFlow nodes={nodes} edges={edges} viewport={viewport} onViewportChange={onViewport} minZoom={0.1} maxZoom={1.5}
        nodesDraggable={false} nodesConnectable={false} onNodeClick={(_, node) => activate(node.id)}
        onKeyDownCapture={event => {
          if (event.key !== 'Enter' && event.key !== ' ') return;
          const id = (event.target as HTMLElement).closest('[data-id]')?.getAttribute('data-id');
          if (id) { event.preventDefault(); event.stopPropagation(); activate(id); }
        }}>
        <Background gap={20} color="#cbd5e1" size={1} /><Controls showInteractive={false} fitViewOptions={{ padding: 0.08, minZoom: 0.1 }} />
      </ReactFlow>
    </div>
  </div>;
};

/**
 * Present aligned comparable/actual trees reading Cost of Goods Sold -> products -> materials.
 * The worker retains both complete graphs and returns one bounded layout; folds expand in place.
 */
export const DualBomTreeCanvas: React.FC<DualBomTreeCanvasProps> = ({ outcome, currency, onSelectNode, onCompareProduct, comparisonAvailable }) => {
  const [query, updateQuery] = useState<DisplayQuery>(INITIAL_QUERY);
  const [history, setHistory] = useState<DisplayQuery[]>([]);
  const [result, setResult] = useState<DisplayResult | null>(null); const [busy, setBusy] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<DisplayItem | null>(null);
  const [viewport, setViewport] = useState<Viewport>({ x: 16, y: 8, zoom: 0.75 });
  const worker = useRef<Worker | null>(null); const sequence = useRef(0); const panes = useRef<HTMLDivElement>(null); const fitted = useRef(false);
  const queryRef = useRef(query); queryRef.current = query;
  const setQuery = (next: DisplayQuery) => {
    // Invalidate queued replies immediately, before the next worker request effect.
    sequence.current++; setBusy(true); updateQuery(next);
  };
  useEffect(() => {
    const displayWorker = new Worker(new URL('../../graphDisplay.worker.ts', import.meta.url), { type: 'module' });
    worker.current = displayWorker;
    displayWorker.onmessage = (event: MessageEvent<{ sequence: number; result?: DisplayResult; error?: string }>) => {
      if (event.data.sequence !== sequence.current) return;
      setBusy(false);
      if (event.data.error) { setError(event.data.error); setResult(null); }
      else { setResult(event.data.result!); setError(null); }
    };
    displayWorker.onerror = () => { setBusy(false); setError('Graph display worker failed.'); setResult(null); };
    displayWorker.postMessage({ sequence: ++sequence.current,
      input: { actual: outcome.actual, comparable: outcome.comparable, branches: outcome.branches }, query: queryRef.current });
    return () => { sequence.current++; displayWorker.terminate(); worker.current = null; };
  }, [outcome]);
  useEffect(() => {
    setBusy(true);
    worker.current?.postMessage({ sequence: ++sequence.current, query });
  }, [query]);
  useEffect(() => {
    // Fit the first projection's columns to one pane's width, keeping COGS anchored top-left.
    if (!result || fitted.current || !panes.current) return;
    fitted.current = true;
    setViewport({ x: 16, y: 8, zoom: Math.min(0.85, Math.max(0.5, (panes.current.clientWidth / 2 - 24) / (result.columns * COLUMN))) });
  }, [result]);
  const activate = (item: DisplayItem, period: Period) => {
    if (busy) return;
    if (item.expandKey) {
      setHistory(previous => [...previous, query]);
      setQuery({ ...query, expanded: { ...query.expanded, [item.expandKey]: (query.expanded[item.expandKey] || 0) + 1 } });
      return;
    }
    if (item.kind !== 'node') return;
    setSelected(item);
    const value = item[period];
    if (value) onSelectNode({ id: `${outcome.analysisId}:${period}:node:${value.position}`, position: value.position, inventoryId: item.inventoryId!,
      quantity: Number(value.quantity), cost: value.cost == null ? null : Number(value.cost) });
  };
  const stats = result?.stats;
  return <section data-analysis-id={outcome.analysisId} className="w-full h-full min-h-[400px] flex flex-col bg-surface-canvas relative">
    <div className="shrink-0 border-b bg-white px-3 py-2 flex flex-wrap gap-3 items-center text-xs">
      <label>Initial coverage of Σ|ΔCOGS| <select aria-label="Initial COGS difference coverage" className="border rounded px-1 py-0.5 ml-1" value={query.coverage} disabled={busy}
        onChange={event => { setHistory([]); setQuery({ coverage: Number(event.target.value), expanded: {} }); }}>
        {COVERAGE.map(value => <option key={value} value={value}>{value}%</option>)}</select></label>
      {stats && <span>{stats.selectedProducts} of {stats.products} products · cover {stats.coveredAbsolute} of {stats.totalAbsolute} {currency}
        {stats.coveredPerMille != null && ` (${(stats.coveredPerMille / 10).toFixed(1)}%)`} · ΔCOGS {stats.cogsDelta ?? 'unavailable'}</span>}
      <button disabled={busy || !history.length} className="text-period-actual hover:underline disabled:opacity-40 font-medium" onClick={() => { setQuery(history[history.length - 1]); setHistory(history.slice(0, -1)); }}>Undo expansion</button>
      <button disabled={busy || !Object.keys(query.expanded).length} className="text-period-actual hover:underline disabled:opacity-40 font-medium" onClick={() => { setHistory([]); setQuery({ ...query, expanded: {} }); }}>Collapse all</button>
      {selected?.product && <button className="px-2 py-0.5 rounded bg-period-actual-subtle text-period-actual border border-period-actual-border/50 hover:bg-period-actual hover:text-white transition-colors disabled:opacity-40 font-medium" disabled={!comparisonAvailable} onClick={() => onCompareProduct(selected.inventoryId!)}>Production evidence: {selected.inventoryId}</button>}
    </div>
    {error && <p className="px-3 py-2 text-xs text-variance-unfavourable bg-variance-unfavourable-subtle border-b border-variance-unfavourable-border/40" role="alert">{error}</p>}
    {busy && <p className="px-3 py-1 text-xs" role="status">Preparing bounded graph…</p>}
    {stats && <div className="px-3 py-1 text-[11px] text-slate-600 flex flex-wrap gap-x-3">
      <span>{stats.totalNodes} recorded nodes · {stats.businessNodes} shown · click … or +n to expand in place; Δ is actual minus comparable</span>
      {stats.unavailableProducts > 0 && <span className="text-amber-700">{stats.unavailableProducts} products have unavailable sales cost and are excluded from coverage</span>}
      {stats.unlinkedBranches > 0 && <span className="text-amber-700">{stats.unlinkedBranches} abnormal paths have no recorded route to COGS</span>}
      {stats.budgetReached && <span className="text-amber-700">Display budget reached; further content stays folded</span>}
      {stats.diagnostics.map(diagnostic => <span key={diagnostic} className="text-amber-700">{diagnostic}</span>)}
    </div>}
    {result && <div ref={panes} className={`flex flex-1 min-h-0 ${busy ? 'pointer-events-none opacity-60' : ''}`} aria-busy={busy}>
      <GraphPane result={result} period="comparable" label={`Comparable ${outcome.comparable.start} to ${outcome.comparable.end}`} selectedKey={selected?.key ?? null}
        viewport={viewport} onViewport={setViewport} onActivate={activate} />
      <GraphPane result={result} period="actual" label={`Actual ${outcome.actual.start} to ${outcome.actual.end}`} selectedKey={selected?.key ?? null}
        viewport={viewport} onViewport={setViewport} onActivate={activate} />
    </div>}
  </section>;
};
