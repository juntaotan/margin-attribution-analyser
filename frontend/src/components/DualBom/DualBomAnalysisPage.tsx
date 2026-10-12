import React, { useCallback, useEffect, useRef, useState } from 'react';
import { AnalysisHeader } from './AnalysisHeader';
import { ScopeControlsBar } from './ScopeControlsBar';
import { DualBomTreeCanvas } from './DualBomTreeCanvas';
import { AuditSidebar } from './AuditSidebar';
import { ProductComparisonModal } from './ProductComparisonModal';
import { AnalysisProgressBar } from './AnalysisProgressBar';
import { accelerationPreference, embeddingPreference, readWorkflowStream, type AnalysisOutcome, type PeriodSnapshot, type WorkflowEvent } from '../../analysisRun';
import type { StreamNode } from '../../analysisGraph';
import { currentStep, failProgress, initialProgress, reduceProgress, type AnalysisProgress } from '../../analysisProgress';

/** Retains the analysis connection and results while application navigation hides this page. */
export const DualBomAnalysisPage: React.FC<{ active?: boolean; onStatusChange?: (status: string) => void }> = ({ active = true, onStatusChange }) => {
  const [periodFrom, setPeriodFrom] = useState('2026-01-01'); const [periodTo, setPeriodTo] = useState('2026-01-31');
  const [comparablePeriodFrom, setComparablePeriodFrom] = useState('2025-01-01'); const [comparablePeriodTo, setComparablePeriodTo] = useState('2025-01-31');
  const [threshold, setThreshold] = useState(250); const [leafThreshold, setLeafThreshold] = useState(100);
  const [currency, setCurrency] = useState(''); const [costScope, setCostScope] = useState('');
  const [complete, setComplete] = useState(false); const [repeated, setRepeated] = useState(false);
  const [isAnalyzing, setIsAnalyzing] = useState(false); const [progress, setProgress] = useState<AnalysisProgress | null>(null);
  const [error, setError] = useState<string | null>(null); const [outcome, setOutcome] = useState<AnalysisOutcome | null>(null);
  const [display, setDisplay] = useState<Pick<AnalysisOutcome, 'analysisId' | 'actual' | 'comparable' | 'branches'> | null>(null);
  const [visibleOutcome, setVisibleOutcome] = useState<typeof display>(null);
  const [resultCurrency, setResultCurrency] = useState('');
  const [diagnosticPage, setDiagnosticPage] = useState(0);
  const [selected, setSelected] = useState<StreamNode | null>(null); const [product, setProduct] = useState<string | null>(null);
  const [resultScope, setResultScope] = useState(''); const [submittedOptions, setSubmittedOptions] = useState('');
  const request = useRef<AbortController | null>(null); const version = useRef(0);
  const scope = `${periodFrom}|${periodTo}|${comparablePeriodFrom}|${comparablePeriodTo}|${leafThreshold}|${threshold}`;
  const options = `${currency}|${costScope}|${complete}|${repeated}`;
  useEffect(() => () => { version.current++; request.current?.abort(); }, []);
  // Defer new canvas creation until visible; existing canvases retain their viewport while hidden.
  useEffect(() => { if (active) setVisibleOutcome(display); }, [active, display]);
  useEffect(() => {
    const step = isAnalyzing && progress ? currentStep(progress) : null;
    onStatusChange?.(step ? `Running · ${step.position}/${step.count}` : isAnalyzing ? 'Running' : error ? 'Failed' : outcome ? 'Completed' : '');
  }, [isAnalyzing, progress, error, outcome, onStatusChange]);
  const closeProduct = useCallback(() => setProduct(null), []);
  const handleRunAnalysis = async () => {
    if (isAnalyzing) return;
    if (!periodFrom || !periodTo || periodFrom > periodTo || !comparablePeriodFrom || !comparablePeriodTo || comparablePeriodFrom > comparablePeriodTo || !Number.isFinite(leafThreshold) || leafThreshold < 0 || !Number.isFinite(threshold) || threshold < 0) { setError('Select ordered periods and non-negative thresholds.'); return; }
    if (!currency.trim() || !costScope.trim()) { setError('Declare the currency and cost scope for the warehouse values.'); return; }
    const controller = new AbortController(); request.current?.abort(); request.current = controller; const current = ++version.current;
    setIsAnalyzing(true); setProgress(initialProgress(performance.now())); setError(null); setOutcome(null); setDisplay(null); setResultCurrency(currency.trim()); setDiagnosticPage(0); setProduct(null); setSelected(null); setResultScope(scope); setSubmittedOptions(options);
    let snapshots: PeriodSnapshot[] | null = null;
    let completed = false; let streamedError: string | null = null;
    try {
      const response = await fetch('/api/v1/analysis/run/stream', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/x-ndjson' }, signal: controller.signal,
        body: JSON.stringify({ actualStartDate: periodFrom, actualEndDate: periodTo, comparableStartDate: comparablePeriodFrom, comparableEndDate: comparablePeriodTo,
          leafThreshold: String(leafThreshold), stopThreshold: String(threshold), execution: accelerationPreference(), embedding: embeddingPreference(),
          comparison: { completeGraphCoverage: complete, warehouse: { namespace: 'warehouse', currency: currency.trim(), costScope: costScope.trim(),
            quantityInterpretation: repeated ? 'REPEATED_INSTANCE_TOTAL' : 'UNVERIFIED', costInterpretation: repeated ? 'REPEATED_INSTANCE_TOTAL' : 'UNVERIFIED' }, config: { alpha: 0.8 } } }) });
      if (!response.ok) { const body = await response.json().catch(() => ({})); throw new Error(body.message || `Analysis failed (${response.status})`); }
      await readWorkflowStream(response, (event: WorkflowEvent) => {
        if (current !== version.current) return;
        setProgress(previous => previous && reduceProgress(previous, event, performance.now()));
        if (event.type === 'graph') snapshots = event.data as PeriodSnapshot[];
        else if (event.type === 'branches' && snapshots) setDisplay({ analysisId: event.analysisId!, actual: snapshots[0], comparable: snapshots[1], branches: event.data as AnalysisOutcome['branches'] });
        else if (event.type === 'completed') { completed = true; const value = event.data as AnalysisOutcome; setOutcome(value); setDisplay(previous => previous ?? value); }
        else if (event.type === 'error') streamedError = event.message || 'Analysis failed.';
      });
      if (streamedError) throw new Error(streamedError);
      if (!completed) throw new Error('Analysis stream ended before a completed result.');
    } catch (err) {
      if (current === version.current && !controller.signal.aborted) {
        const message = err instanceof Error ? err.message : 'Analysis failed.';
        setError(message); setProgress(previous => previous && failProgress(previous, message, performance.now()));
      }
    }
    finally { if (current === version.current) setIsAnalyzing(false); }
  };
  const chooseNode = (node: StreamNode) => setSelected(node);
  const exportAnalysis = () => {
    if (!outcome) return;
    const url = URL.createObjectURL(new Blob([JSON.stringify(outcome, null, 2)], { type: 'application/json' }));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `analysis-${outcome.analysisId}.json`; anchor.click(); URL.revokeObjectURL(url);
  };
  return <main className="flex-1 flex flex-col h-full min-h-0 bg-surface-canvas text-slate-800 overflow-hidden">
    <AnalysisHeader onExport={exportAnalysis} status={outcome?.execution.map(report => `${report.stage}: ${report.actual || report.status}`).join(' · ') || 'Awaiting analysis'} />
    <ScopeControlsBar {...{ periodFrom, setPeriodFrom, periodTo, setPeriodTo, threshold, setThreshold, comparablePeriodFrom, setComparablePeriodFrom, comparablePeriodTo, setComparablePeriodTo, leafThreshold, setLeafThreshold, isAnalyzing }} onRunAnalysis={handleRunAnalysis} />
    <details className="bg-white border-b px-4 py-2 text-xs shrink-0"><summary className="cursor-pointer font-medium">Data interpretation {(!complete || !repeated) && '· confirmation required for complete comparisons'}</summary><div className="flex flex-wrap gap-4 py-3">
      <label>Currency <input aria-label="Warehouse currency" className="border rounded px-2 py-1 ml-2 w-20" placeholder="NZD" value={currency} onChange={e => setCurrency(e.target.value)} /></label>
      <label>Cost scope <input aria-label="Warehouse cost scope" className="border rounded px-2 py-1 ml-2" placeholder="Recorded valuation" value={costScope} onChange={e => setCostScope(e.target.value)} /></label>
      <label className="flex items-center gap-2"><input type="checkbox" checked={complete} onChange={e => setComplete(e.target.checked)} /> Imported records cover both periods and complete selected batches</label>
      <label className="flex items-center gap-2"><input type="checkbox" checked={repeated} onChange={e => setRepeated(e.target.checked)} /> Output values repeated on issue rows are batch totals</label>
    </div></details>
    <AnalysisProgressBar progress={progress} idle="Select periods and run analysis." summary={<>
      <span>{outcome?.status || (error ? 'Analysis failed' : 'Analysis stopped')}</span>
      {outcome && <span>{outcome.branches.length} paths · {outcome.planned} pairs planned · {outcome.executed} executed · {outcome.rejected} rejected · {outcome.failed} failed</span>}
      {outcome && (scope !== resultScope || options !== submittedOptions) && <span className="text-amber-800 bg-amber-50 px-2 py-0.5 rounded border border-amber-200/60 font-medium">Inputs changed. Displayed results retain the previous run scope; run again to update.</span>}
    </>} />
    {error && <p className="px-4 py-2 text-xs text-variance-unfavourable bg-variance-unfavourable-subtle border-b border-variance-unfavourable-border/50" role="alert">{error}</p>}
    {outcome && <details className="px-4 py-2 text-xs bg-white border-b shrink-0"><summary className="cursor-pointer">Diagnostics ({outcome.diagnostics.length}) · use graph boxes to browse abnormal paths</summary>
      <ul className="max-h-32 overflow-auto space-y-2 py-2">{outcome.diagnostics.slice(diagnosticPage * 10, (diagnosticPage + 1) * 10).map((diagnostic,i) => <li key={i}>{diagnostic}</li>)}</ul>
      <button disabled={diagnosticPage === 0} onClick={() => setDiagnosticPage(diagnosticPage - 1)}>Previous</button>
      <span className="mx-3">{diagnosticPage + 1} / {Math.max(1, Math.ceil(outcome.diagnostics.length / 10))}</span>
      <button disabled={(diagnosticPage + 1) * 10 >= outcome.diagnostics.length} onClick={() => setDiagnosticPage(diagnosticPage + 1)}>Next</button>
    </details>}
    <div className="flex-1 min-h-0 overflow-auto lg:overflow-hidden flex flex-col lg:flex-row">
      <div className="flex-1 min-w-0 min-h-[400px] lg:min-h-0 flex flex-col result-enter">{visibleOutcome ? <DualBomTreeCanvas key={visibleOutcome.analysisId} outcome={visibleOutcome} comparisonAvailable={!!outcome} currency={resultCurrency} onSelectNode={chooseNode} onCompareProduct={setProduct} /> : <div className="flex-1 flex flex-col justify-center items-center p-8 text-sm text-slate-600"><p>{isAnalyzing && progress ? `${currentStep(progress).step.runningLabel}…` : 'Your analysis starts with the imported warehouse records.'}</p><p className="text-xs mt-2">Select two periods, confirm the data interpretation and run analysis.</p></div>}</div>
      <AuditSidebar outcome={outcome} inventoryId={selected?.inventoryId || null} active={active} />
    </div>
    <ProductComparisonModal outcome={outcome} productId={product} alpha={0.8} onClose={closeProduct} active={active} />
  </main>;
};
