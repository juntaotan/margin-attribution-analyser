import React, { useCallback, useEffect, useRef, useState } from 'react';
import { FileText } from 'lucide-react';
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
  const [headerCollapsed, setHeaderCollapsed] = useState(false);
  const [auditOpen, setAuditOpen] = useState(false);
  const [auditPinned, setAuditPinned] = useState(false);
  const [diagnosticsOpen, setDiagnosticsOpen] = useState(false);
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
  const chooseNode = (node: StreamNode) => {
    setSelected(node);
    setAuditOpen(true);
  };
  const exportAnalysis = () => {
    if (!outcome) return;
    const url = URL.createObjectURL(new Blob([JSON.stringify(outcome, null, 2)], { type: 'application/json' }));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `analysis-${outcome.analysisId}.json`; anchor.click(); URL.revokeObjectURL(url);
  };
  return <main className="flex-1 flex flex-col h-full min-h-0 bg-surface-canvas text-slate-800 overflow-hidden">
    <AnalysisHeader
      onExport={exportAnalysis}
      status={outcome?.execution.map(report => `${report.stage}: ${report.actual || report.status}`).join(' · ') || 'Awaiting analysis'}
      isCollapsed={headerCollapsed}
      onToggleCollapse={() => setHeaderCollapsed(!headerCollapsed)}
    />
    <ScopeControlsBar
      {...{ periodFrom, setPeriodFrom, periodTo, setPeriodTo, threshold, setThreshold, comparablePeriodFrom, setComparablePeriodFrom, comparablePeriodTo, setComparablePeriodTo, leafThreshold, setLeafThreshold, isAnalyzing }}
      onRunAnalysis={handleRunAnalysis}
    />
    <details className="bg-white border-b px-4 py-2 text-xs shrink-0 group transition-all">
      <summary className="cursor-pointer font-medium text-slate-700 flex items-center justify-between hover:text-slate-900 select-none">
        <span>Data interpretation {(!complete || !repeated) && '· confirmation required for complete comparisons'}</span>
        <span className="text-[10px] text-slate-400 group-open:rotate-180 transition-transform">▼</span>
      </summary>
      <div className="flex flex-wrap gap-4 py-3 pt-2 text-slate-700">
        <label>Currency <input aria-label="Warehouse currency" className="border border-slate-300 rounded px-2 py-1 ml-2 w-20 text-xs font-mono" placeholder="NZD" value={currency} onChange={e => setCurrency(e.target.value)} /></label>
        <label>Cost scope <input aria-label="Warehouse cost scope" className="border border-slate-300 rounded px-2 py-1 ml-2 text-xs" placeholder="Recorded valuation" value={costScope} onChange={e => setCostScope(e.target.value)} /></label>
        <label className="flex items-center gap-2"><input type="checkbox" checked={complete} onChange={e => setComplete(e.target.checked)} className="rounded text-period-actual focus:ring-period-actual" /> Imported records cover both periods and complete selected batches</label>
        <label className="flex items-center gap-2"><input type="checkbox" checked={repeated} onChange={e => setRepeated(e.target.checked)} className="rounded text-period-actual focus:ring-period-actual" /> Output values repeated on issue rows are batch totals</label>
      </div>
    </details>
    <AnalysisProgressBar progress={progress} idle="Select periods and run analysis." summary={<>
      <span>{outcome?.status || (error ? 'Analysis failed' : 'Analysis stopped')}</span>
      {outcome && <span>{outcome.branches.length} paths · {outcome.planned} pairs planned · {outcome.executed} executed · {outcome.rejected} rejected · {outcome.failed} failed</span>}
      {outcome && (scope !== resultScope || options !== submittedOptions) && <span className="text-amber-800 bg-amber-50 px-2 py-0.5 rounded border border-amber-200/60 font-medium">Inputs changed. Displayed results retain the previous run scope; run again to update.</span>}
    </>} />
    {error && <p className="px-4 py-2 text-xs text-variance-unfavourable bg-variance-unfavourable-subtle border-b border-variance-unfavourable-border/50" role="alert">{error}</p>}
    <div className="flex-1 min-h-0 relative overflow-hidden flex flex-col lg:flex-row">
      <div className="flex-1 min-w-0 min-h-[400px] lg:min-h-0 flex flex-col result-enter relative">
        {visibleOutcome ? (
          <DualBomTreeCanvas
            key={visibleOutcome.analysisId}
            outcome={visibleOutcome}
            comparisonAvailable={!!outcome}
            currency={resultCurrency}
            onSelectNode={chooseNode}
            onCompareProduct={setProduct}
          />
        ) : (
          <div className="flex-1 flex flex-col justify-center items-center p-8 text-sm text-slate-600">
            <p>{isAnalyzing && progress ? `${currentStep(progress).step.runningLabel}…` : 'Your analysis starts with the imported warehouse records.'}</p>
            <p className="text-xs mt-2">Select two periods, confirm the data interpretation and run analysis.</p>
          </div>
        )}

        {/* Floating Evidence Inspector Button when drawer is closed */}
        {!auditOpen && outcome && (
          <button
            type="button"
            onClick={() => setAuditOpen(true)}
            className="absolute right-0 top-12 z-20 flex items-center gap-1.5 px-2.5 py-1.5 bg-white hover:bg-slate-50 text-slate-700 text-xs font-medium rounded-l-md border-l border-t border-b border-slate-300 shadow-md transition-all hover:pr-3.5 group cursor-pointer"
            title="Open Evidence Inspector drawer"
            aria-label="Open Evidence Inspector drawer"
          >
            <FileText className="w-3.5 h-3.5 text-period-actual" />
            <span className="hidden sm:inline">Evidence Inspector</span>
            {selected && (
              <span className="text-[10px] text-slate-500 font-mono">
                ({selected.inventoryId})
              </span>
            )}
          </button>
        )}
      </div>

      <AuditSidebar
        outcome={outcome}
        inventoryId={selected?.inventoryId || null}
        active={active}
        isOpen={auditOpen}
        onClose={() => setAuditOpen(false)}
        isPinned={auditPinned}
        onTogglePin={() => setAuditPinned(!auditPinned)}
      />
    </div>

    {/* Bottom Diagnostics Drawer */}
    {outcome && outcome.diagnostics.length > 0 && (
      <details
        open={diagnosticsOpen}
        onToggle={(e) => setDiagnosticsOpen((e.target as HTMLDetailsElement).open)}
        className="px-4 py-1.5 text-xs bg-slate-50 border-t border-slate-200/80 shrink-0 text-slate-700 transition-all"
      >
        <summary className="cursor-pointer font-medium flex items-center justify-between">
          <span className="flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-amber-500" />
            <span>Diagnostics ({outcome.diagnostics.length}) · use graph boxes to browse abnormal paths</span>
          </span>
          <span className="text-[10px] text-slate-400">
            {diagnosticsOpen ? 'collapse' : 'expand'}
          </span>
        </summary>
        <div className="pt-2 pb-1">
          <ul className="max-h-32 overflow-auto space-y-1.5 font-mono text-slate-600 bg-white p-2 rounded border border-slate-200/60 mb-2">
            {outcome.diagnostics.slice(diagnosticPage * 10, (diagnosticPage + 1) * 10).map((diagnostic, i) => (
              <li key={i} className="flex items-start gap-1.5">
                <span className="text-amber-600 shrink-0">⚠</span>
                <span>{diagnostic}</span>
              </li>
            ))}
          </ul>
          <div className="flex items-center gap-2">
            <button
              disabled={diagnosticPage === 0}
              onClick={() => setDiagnosticPage(diagnosticPage - 1)}
              className="px-2 py-0.5 border border-slate-200 rounded text-[11px] disabled:opacity-40 hover:bg-slate-100"
            >
              Previous
            </button>
            <span className="text-[11px] text-slate-600">
              {diagnosticPage + 1} / {Math.max(1, Math.ceil(outcome.diagnostics.length / 10))}
            </span>
            <button
              disabled={(diagnosticPage + 1) * 10 >= outcome.diagnostics.length}
              onClick={() => setDiagnosticPage(diagnosticPage + 1)}
              className="px-2 py-0.5 border border-slate-200 rounded text-[11px] disabled:opacity-40 hover:bg-slate-100"
            >
              Next
            </button>
          </div>
        </div>
      </details>
    )}
    <ProductComparisonModal outcome={outcome} productId={product} alpha={0.8} onClose={closeProduct} active={active} />
  </main>;
};
