import React, { useCallback, useEffect, useRef, useState, useMemo } from 'react';
import { LoaderCircle } from 'lucide-react';
import { AnalysisHeader } from './AnalysisHeader';
import { ScopeControlsBar } from './ScopeControlsBar';
import { DualBomTreeCanvas } from './DualBomTreeCanvas';
import { AuditSidebar } from './AuditSidebar';
import { ProductComparisonModal } from './ProductComparisonModal';
import { accelerationPreference, canvasGraph, readWorkflowStream, recorded, type AnalysisOutcome, type PeriodSnapshot, type WorkflowEvent } from '../../analysisRun';
import type { StreamNode } from '../../analysisGraph';

const STAGES: Record<string, string> = { loading: 'Loading warehouse data', graph: 'Period graphs built', branches: 'Abnormal branches discovered', 'production-trees': 'Production trees restored', embeddings: 'Embeddings prepared', comparison: 'Comparing production instances', completed: 'Analysis complete' };
/** Retains the analysis connection and results while application navigation hides this page. */
export const DualBomAnalysisPage: React.FC<{ active?: boolean; onStatusChange?: (status: string) => void }> = ({ active = true, onStatusChange }) => {
  const [periodFrom, setPeriodFrom] = useState('2026-01-01'); const [periodTo, setPeriodTo] = useState('2026-01-31');
  const [comparablePeriodFrom, setComparablePeriodFrom] = useState('2025-01-01'); const [comparablePeriodTo, setComparablePeriodTo] = useState('2025-01-31');
  const [threshold, setThreshold] = useState(250); const [leafThreshold, setLeafThreshold] = useState(100);
  const [currency, setCurrency] = useState(''); const [costScope, setCostScope] = useState('');
  const [complete, setComplete] = useState(false); const [repeated, setRepeated] = useState(false);
  const [isAnalyzing, setIsAnalyzing] = useState(false); const [stage, setStage] = useState('');
  const [error, setError] = useState<string | null>(null); const [outcome, setOutcome] = useState<AnalysisOutcome | null>(null);
  const [graphs, setGraphs] = useState<{ analysisId: string; actual: PeriodSnapshot; comparable: PeriodSnapshot } | null>(null);
  const [visibleGraphs, setVisibleGraphs] = useState<typeof graphs>(null);
  const [selected, setSelected] = useState<StreamNode | null>(null); const [product, setProduct] = useState<string | null>(null);
  const [resultScope, setResultScope] = useState(''); const [submittedOptions, setSubmittedOptions] = useState('');
  const request = useRef<AbortController | null>(null); const version = useRef(0);
  const scope = `${periodFrom}|${periodTo}|${comparablePeriodFrom}|${comparablePeriodTo}|${leafThreshold}|${threshold}`;
  const options = `${currency}|${costScope}|${complete}|${repeated}`;
  useEffect(() => () => { version.current++; request.current?.abort(); }, []);
  // Defer new canvas creation until visible; existing canvases retain their viewport while hidden.
  useEffect(() => { if (active) setVisibleGraphs(graphs); }, [active, graphs]);
  useEffect(() => {
    onStatusChange?.(isAnalyzing ? 'Running' : error ? 'Failed' : outcome ? 'Completed' : '');
  }, [isAnalyzing, error, outcome, onStatusChange]);
  const closeProduct = useCallback(() => setProduct(null), []);
  const handleRunAnalysis = async () => {
    if (isAnalyzing) return;
    if (!periodFrom || !periodTo || periodFrom > periodTo || !comparablePeriodFrom || !comparablePeriodTo || comparablePeriodFrom > comparablePeriodTo || !Number.isFinite(leafThreshold) || leafThreshold < 0 || !Number.isFinite(threshold) || threshold < 0) { setError('Select ordered periods and non-negative thresholds.'); return; }
    if (!currency.trim() || !costScope.trim()) { setError('Declare the currency and cost scope for the warehouse values.'); return; }
    const controller = new AbortController(); request.current?.abort(); request.current = controller; const current = ++version.current;
    setIsAnalyzing(true); setStage('loading'); setError(null); setOutcome(null); setGraphs(null); setProduct(null); setSelected(null); setResultScope(scope); setSubmittedOptions(options);
    let completed = false; let streamedError: string | null = null;
    try {
      const response = await fetch('/api/v1/analysis/run/stream', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/x-ndjson' }, signal: controller.signal,
        body: JSON.stringify({ actualStartDate: periodFrom, actualEndDate: periodTo, comparableStartDate: comparablePeriodFrom, comparableEndDate: comparablePeriodTo,
          leafThreshold: String(leafThreshold), stopThreshold: String(threshold), execution: accelerationPreference(),
          comparison: { completeGraphCoverage: complete, warehouse: { namespace: 'warehouse', currency: currency.trim(), costScope: costScope.trim(),
            quantityInterpretation: repeated ? 'REPEATED_INSTANCE_TOTAL' : 'UNVERIFIED', costInterpretation: repeated ? 'REPEATED_INSTANCE_TOTAL' : 'UNVERIFIED' }, config: { alpha: 0.8 } } }) });
      if (!response.ok) { const body = await response.json().catch(() => ({})); throw new Error(body.message || `Analysis failed (${response.status})`); }
      await readWorkflowStream(response, (event: WorkflowEvent) => {
        if (current !== version.current) return;
        setStage(event.type);
        if (event.type === 'graph') {
          const [actual, comparable] = event.data as PeriodSnapshot[]; setGraphs({ analysisId: event.analysisId!, actual, comparable });
        } else if (event.type === 'completed') { completed = true; const value = event.data as AnalysisOutcome; setOutcome(value); }
        else if (event.type === 'error') streamedError = event.message || 'Analysis failed.';
      });
      if (streamedError) throw new Error(streamedError);
      if (!completed) throw new Error('Analysis stream ended before a completed result.');
    } catch (err) { if (current === version.current && !controller.signal.aborted) setError(err instanceof Error ? err.message : 'Analysis failed.'); }
    finally { if (current === version.current) setIsAnalyzing(false); }
  };
  const actual = useMemo(() => visibleGraphs ? canvasGraph(visibleGraphs.actual, visibleGraphs.analysisId, 'actual') : null, [visibleGraphs]);
  const comparable = useMemo(() => visibleGraphs ? canvasGraph(visibleGraphs.comparable, visibleGraphs.analysisId, 'comparable') : null, [visibleGraphs]);
  const nodes = new Set(outcome?.branches.flatMap(branch => branch.positions.map(i => `${outcome.analysisId}:actual:node:${i}`)) || []);
  const edges = new Set(outcome?.branches.flatMap(branch => branch.edges.map(edge => `${outcome.analysisId}:actual:edge:${edge.edgeIndex}`)) || []);
  const chooseNode = (node: StreamNode) => {
    setSelected(node);
    if (outcome && node.inventoryId !== '__COGS__' && (outcome.actual.records.issues.some(issue => issue.productId === node.inventoryId) || outcome.comparable.records.issues.some(issue => issue.productId === node.inventoryId))) setProduct(node.inventoryId);
  };
  const exportAnalysis = () => {
    if (!outcome) return;
    const url = URL.createObjectURL(new Blob([JSON.stringify(outcome, null, 2)], { type: 'application/json' }));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `analysis-${outcome.analysisId}.json`; anchor.click(); URL.revokeObjectURL(url);
  };
  return <main className="flex-1 flex flex-col h-full min-h-0 bg-[#F6F8FA] text-slate-800 overflow-hidden">
    <AnalysisHeader onExport={exportAnalysis} status={outcome?.execution.map(report => `${report.stage}: ${report.actual || report.status}`).join(' · ') || 'Awaiting analysis'} />
    <ScopeControlsBar {...{ periodFrom, setPeriodFrom, periodTo, setPeriodTo, threshold, setThreshold, comparablePeriodFrom, setComparablePeriodFrom, comparablePeriodTo, setComparablePeriodTo, leafThreshold, setLeafThreshold, isAnalyzing }} onRunAnalysis={handleRunAnalysis} />
    <details className="bg-white border-b px-4 py-2 text-xs shrink-0"><summary className="cursor-pointer font-medium">Data interpretation {(!complete || !repeated) && '· confirmation required for complete comparisons'}</summary><div className="flex flex-wrap gap-4 py-3">
      <label>Currency <input aria-label="Warehouse currency" className="border rounded px-2 py-1 ml-2 w-20" placeholder="NZD" value={currency} onChange={e => setCurrency(e.target.value)} /></label>
      <label>Cost scope <input aria-label="Warehouse cost scope" className="border rounded px-2 py-1 ml-2" placeholder="Recorded valuation" value={costScope} onChange={e => setCostScope(e.target.value)} /></label>
      <label className="flex items-center gap-2"><input type="checkbox" checked={complete} onChange={e => setComplete(e.target.checked)} /> Imported records cover both periods and complete selected batches</label>
      <label className="flex items-center gap-2"><input type="checkbox" checked={repeated} onChange={e => setRepeated(e.target.checked)} /> Output values repeated on issue rows are batch totals</label>
    </div></details>
    <div className="px-4 py-2 bg-white border-b text-xs flex flex-wrap gap-4 items-center" role="status" aria-live="polite">
      {isAnalyzing && <LoaderCircle size={16} className="animate-spin text-[#466987]" />}<span>{isAnalyzing ? STAGES[stage] || stage : outcome?.status || (error ? 'Analysis failed' : 'Select periods and run analysis.')}</span>
      {outcome && <span>{outcome.branches.length} paths · {outcome.planned} pairs planned · {outcome.executed} executed · {outcome.rejected} rejected · {outcome.failed} failed</span>}
      {outcome && (scope !== resultScope || options !== submittedOptions) && <span className="text-[#866322]">Inputs changed. Displayed results retain the previous run scope; run again to update.</span>}
    </div>
    {error && <p className="px-4 py-2 text-xs text-[#A64242] bg-red-50" role="alert">{error}</p>}
    {outcome && <details className="px-4 py-2 text-xs bg-white border-b shrink-0"><summary className="cursor-pointer">Reconciliation paths and diagnostics ({outcome.branches.length})</summary><ul className="max-h-32 overflow-auto space-y-2 py-2">{outcome.branches.map((branch,i) => <li key={i}>{branch.positions.map(position => outcome.actual.graph.nodes[position]?.inventoryId).join(' → ')} · {branch.endReason} · recorded difference {recorded(branch.endingCostDifference)}</li>)}{outcome.diagnostics.map((diagnostic,i) => <li key={`d${i}`}>{diagnostic}</li>)}</ul></details>}
    <div className="flex-1 min-h-0 overflow-auto lg:overflow-hidden flex flex-col lg:flex-row">
      <div className="flex-1 min-w-0 min-h-[400px] lg:min-h-0 flex flex-col result-enter">{actual && comparable ? <DualBomTreeCanvas key={visibleGraphs!.analysisId} actualGraph={actual} comparableGraph={comparable} actualLabel={`Actual ${visibleGraphs!.actual.start} to ${visibleGraphs!.actual.end}`} comparableLabel={`Comparable ${visibleGraphs!.comparable.start} to ${visibleGraphs!.comparable.end}`} highlightedNodeIds={nodes} highlightedEdgeIds={edges} selectedNodeId={selected?.id || null} onSelectNode={chooseNode} /> : <div className="flex-1 flex flex-col justify-center items-center p-8 text-sm text-slate-600"><p>{isAnalyzing ? 'Preparing recorded period graphs…' : 'Your analysis starts with the imported warehouse records.'}</p><p className="text-xs mt-2">Select two periods, confirm the data interpretation and run analysis.</p></div>}</div>
      <AuditSidebar outcome={outcome} inventoryId={selected?.inventoryId || null} active={active} />
    </div>
    <ProductComparisonModal outcome={outcome} productId={product} alpha={0.8} onClose={closeProduct} active={active} />
  </main>;
};
