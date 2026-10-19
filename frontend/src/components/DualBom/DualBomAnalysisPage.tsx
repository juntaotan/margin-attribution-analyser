import React, { useCallback, useEffect, useState } from 'react';
import { FileText } from 'lucide-react';
import { AnalysisHeader } from './AnalysisHeader';
import { ScopeControlsBar } from './ScopeControlsBar';
import { DualBomTreeCanvas } from './DualBomTreeCanvas';
import { AuditSidebar } from './AuditSidebar';
import { ProductComparisonModal } from './ProductComparisonModal';
import { AnalysisProgressBar } from './AnalysisProgressBar';
import { DataInterpretationControls, type DataInterpretation } from './DataInterpretationControls';
import { DiagnosticsDrawer } from './DiagnosticsDrawer';
import { useAnalysisRun, type AnalysisDisplay } from './useAnalysisRun';
import { accelerationPreference, embeddingPreference, type AnalysisOutcome } from '../../analysisRun';
import type { StreamNode } from '../../analysisGraph';
import { currentStep, type AnalysisProgress } from '../../analysisProgress';

interface AnalysisScope {
  periodFrom: string;
  periodTo: string;
  comparablePeriodFrom: string;
  comparablePeriodTo: string;
  leafThreshold: number;
  threshold: number;
}

/** Returns why the inputs cannot be analysed, or null when they are valid. */
function validateInputs(scope: AnalysisScope, interpretation: DataInterpretation): string | null {
  const { periodFrom, periodTo, comparablePeriodFrom, comparablePeriodTo, leafThreshold, threshold } = scope;
  if (
    !periodFrom ||
    !periodTo ||
    periodFrom > periodTo ||
    !comparablePeriodFrom ||
    !comparablePeriodTo ||
    comparablePeriodFrom > comparablePeriodTo ||
    !Number.isFinite(leafThreshold) ||
    leafThreshold < 0 ||
    !Number.isFinite(threshold) ||
    threshold < 0
  ) {
    return 'Select ordered periods and non-negative thresholds.';
  }
  if (!interpretation.currency.trim() || !interpretation.costScope.trim()) {
    return 'Declare the currency and cost scope for the warehouse values.';
  }
  return null;
}

/** Builds the analysis request; undeclared interpretations are sent as unverified. */
function analysisRequest(scope: AnalysisScope, interpretation: DataInterpretation) {
  const interpretationMode = interpretation.repeated ? 'REPEATED_INSTANCE_TOTAL' : 'UNVERIFIED';
  return {
    actualStartDate: scope.periodFrom,
    actualEndDate: scope.periodTo,
    comparableStartDate: scope.comparablePeriodFrom,
    comparableEndDate: scope.comparablePeriodTo,
    leafThreshold: String(scope.leafThreshold),
    stopThreshold: String(scope.threshold),
    execution: accelerationPreference(),
    embedding: embeddingPreference(),
    comparison: {
      completeGraphCoverage: interpretation.complete,
      warehouse: {
        namespace: 'warehouse',
        currency: interpretation.currency.trim(),
        costScope: interpretation.costScope.trim(),
        quantityInterpretation: interpretationMode,
        costInterpretation: interpretationMode,
      },
      config: { alpha: 0.8 },
    },
  };
}

/** Short run status reported to the application navigation. */
function runStatus(isAnalyzing: boolean, progress: AnalysisProgress | null, error: string | null, done: boolean) {
  const step = isAnalyzing && progress ? currentStep(progress) : null;
  if (step) return `Running · ${step.position}/${step.count}`;
  if (isAnalyzing) return 'Running';
  if (error) return 'Failed';
  return done ? 'Completed' : '';
}

/** Downloads the full analysis outcome as JSON. */
function exportOutcome(outcome: AnalysisOutcome) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(outcome, null, 2)], { type: 'application/json' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `analysis-${outcome.analysisId}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
}

/** Retains the analysis connection and results while application navigation hides this page. */
export const DualBomAnalysisPage: React.FC<{ active?: boolean; onStatusChange?: (status: string) => void }> = ({
  active = true,
  onStatusChange,
}) => {
  const [periodFrom, setPeriodFrom] = useState('2026-01-01');
  const [periodTo, setPeriodTo] = useState('2026-01-31');
  const [comparablePeriodFrom, setComparablePeriodFrom] = useState('2025-01-01');
  const [comparablePeriodTo, setComparablePeriodTo] = useState('2025-01-31');
  const [threshold, setThreshold] = useState(250);
  const [leafThreshold, setLeafThreshold] = useState(100);
  const [interpretation, setInterpretation] = useState<DataInterpretation>({
    currency: '',
    costScope: '',
    complete: false,
    repeated: false,
  });
  const analysis = useAnalysisRun();
  const { isAnalyzing, progress, error, outcome, display } = analysis;
  const [visibleOutcome, setVisibleOutcome] = useState<AnalysisDisplay | null>(null);
  const [resultCurrency, setResultCurrency] = useState('');
  const [selected, setSelected] = useState<StreamNode | null>(null);
  const [product, setProduct] = useState<string | null>(null);
  const [headerCollapsed, setHeaderCollapsed] = useState(false);
  const [auditOpen, setAuditOpen] = useState(false);
  const [auditPinned, setAuditPinned] = useState(false);
  const [diagnosticsOpen, setDiagnosticsOpen] = useState(false);
  const [resultScope, setResultScope] = useState('');
  const [submittedOptions, setSubmittedOptions] = useState('');
  const scopeValues = { periodFrom, periodTo, comparablePeriodFrom, comparablePeriodTo, leafThreshold, threshold };
  const scope = `${periodFrom}|${periodTo}|${comparablePeriodFrom}|${comparablePeriodTo}|${leafThreshold}|${threshold}`;
  const options = `${interpretation.currency}|${interpretation.costScope}|${interpretation.complete}|${interpretation.repeated}`;

  // Defer new canvas creation until visible; existing canvases retain their viewport while hidden.
  useEffect(() => {
    if (active) setVisibleOutcome(display);
  }, [active, display]);
  useEffect(() => {
    onStatusChange?.(runStatus(isAnalyzing, progress, error, Boolean(outcome)));
  }, [isAnalyzing, progress, error, outcome, onStatusChange]);
  const closeProduct = useCallback(() => setProduct(null), []);

  const handleRunAnalysis = () => {
    if (isAnalyzing) return;
    const invalid = validateInputs(scopeValues, interpretation);
    if (invalid) {
      analysis.setError(invalid);
      return;
    }
    setResultCurrency(interpretation.currency.trim());
    setProduct(null);
    setSelected(null);
    setResultScope(scope);
    setSubmittedOptions(options);
    void analysis.run(analysisRequest(scopeValues, interpretation));
  };
  const chooseNode = (node: StreamNode) => {
    setSelected(node);
    setAuditOpen(true);
  };

  return (
    <main className="flex-1 flex flex-col h-full min-h-0 bg-surface-canvas text-slate-800 overflow-hidden">
      <AnalysisHeader
        onExport={() => outcome && exportOutcome(outcome)}
        status={
          outcome?.execution.map((report) => `${report.stage}: ${report.actual || report.status}`).join(' · ') ||
          'Awaiting analysis'
        }
        isCollapsed={headerCollapsed}
        onToggleCollapse={() => setHeaderCollapsed(!headerCollapsed)}
      />
      <ScopeControlsBar
        {...{
          periodFrom,
          setPeriodFrom,
          periodTo,
          setPeriodTo,
          threshold,
          setThreshold,
          comparablePeriodFrom,
          setComparablePeriodFrom,
          comparablePeriodTo,
          setComparablePeriodTo,
          leafThreshold,
          setLeafThreshold,
          isAnalyzing,
        }}
        onRunAnalysis={handleRunAnalysis}
      />
      <DataInterpretationControls
        value={interpretation}
        onChange={(change) => setInterpretation((current) => ({ ...current, ...change }))}
      />
      <AnalysisProgressBar
        progress={progress}
        idle="Select periods and run analysis."
        summary={
          <RunSummary
            outcome={outcome}
            failed={Boolean(error)}
            inputsChanged={scope !== resultScope || options !== submittedOptions}
          />
        }
      />
      {error && (
        <p
          className="px-4 py-2 text-xs text-variance-unfavourable bg-variance-unfavourable-subtle border-b border-variance-unfavourable-border/50"
          role="alert"
        >
          {error}
        </p>
      )}
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
            <AwaitingResults
              message={
                isAnalyzing && progress
                  ? `${currentStep(progress).step.runningLabel}…`
                  : 'Your analysis starts with the imported warehouse records.'
              }
            />
          )}

          {!auditOpen && outcome && (
            <EvidenceInspectorButton inventoryId={selected?.inventoryId} onOpen={() => setAuditOpen(true)} />
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

      {/* A new run clears the outcome, which remounts the drawer on its first page. */}
      {outcome && outcome.diagnostics.length > 0 && (
        <DiagnosticsDrawer diagnostics={outcome.diagnostics} open={diagnosticsOpen} onOpenChange={setDiagnosticsOpen} />
      )}
      <ProductComparisonModal
        outcome={outcome}
        productId={product}
        alpha={0.8}
        onClose={closeProduct}
        active={active}
      />
    </main>
  );
};

/** Run status and pair counts, warning when the inputs no longer match the displayed run. */
const RunSummary: React.FC<{ outcome: AnalysisOutcome | null; failed: boolean; inputsChanged: boolean }> = ({
  outcome,
  failed,
  inputsChanged,
}) => (
  <>
    <span>{outcome?.status || (failed ? 'Analysis failed' : 'Analysis stopped')}</span>
    {outcome && (
      <span>
        {outcome.branches.length} paths · {outcome.planned} pairs planned · {outcome.executed} executed ·{' '}
        {outcome.rejected} rejected · {outcome.failed} failed
      </span>
    )}
    {outcome && inputsChanged && (
      <span className="text-amber-800 bg-amber-50 px-2 py-0.5 rounded border border-amber-200/60 font-medium">
        Inputs changed. Displayed results retain the previous run scope; run again to update.
      </span>
    )}
  </>
);

const AwaitingResults: React.FC<{ message: string }> = ({ message }) => (
  <div className="flex-1 flex flex-col justify-center items-center p-8 text-sm text-slate-600">
    <p>{message}</p>
    <p className="text-xs mt-2">Select two periods, confirm the data interpretation and run analysis.</p>
  </div>
);

/** Floating button that reopens the closed evidence drawer, naming the selected item. */
const EvidenceInspectorButton: React.FC<{ inventoryId?: string; onOpen: () => void }> = ({ inventoryId, onOpen }) => (
  <button
    type="button"
    onClick={onOpen}
    className="absolute right-0 top-12 z-20 flex items-center gap-1.5 px-2.5 py-1.5 bg-white hover:bg-slate-50 text-slate-700 text-xs font-medium rounded-l-md border-l border-t border-b border-slate-300 shadow-md transition-all hover:pr-3.5 group cursor-pointer"
    title="Open Evidence Inspector drawer"
    aria-label="Open Evidence Inspector drawer"
  >
    <FileText className="w-3.5 h-3.5 text-period-actual" />
    <span className="hidden sm:inline">Evidence Inspector</span>
    {inventoryId && <span className="text-[10px] text-slate-500 font-mono">({inventoryId})</span>}
  </button>
);
