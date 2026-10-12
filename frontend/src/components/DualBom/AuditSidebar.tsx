import React, { useEffect, useState } from 'react';
import { LoaderCircle, FileText, RefreshCw, Pin, PinOff, X } from 'lucide-react';
import { recorded, type AnalysisOutcome } from '../../analysisRun';
import { ManagementCommentaryModal } from './ManagementCommentaryModal';

interface RootCauseReport { inventoryId: string; category: string; categoryLabel: string; certainty: string; evidence: string[]; summary: string | null; aiGenerated: boolean; aiMessage: string | null }

export interface AuditSidebarProps {
  outcome: AnalysisOutcome | null;
  inventoryId: string | null;
  active?: boolean;
  isOpen?: boolean;
  onClose?: () => void;
  isPinned?: boolean;
  onTogglePin?: () => void;
}

/** Shows exact loaded evidence independently of the backend's optional AI explanation in a collapsible drawer. */
export const AuditSidebar: React.FC<AuditSidebarProps> = ({
  outcome,
  inventoryId,
  active = true,
  isOpen = true,
  onClose,
  isPinned = true,
  onTogglePin,
}) => {
  const [report, setReport] = useState<RootCauseReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [retry, setRetry] = useState(0);
  const [commentary, setCommentary] = useState(false);
  const [opening, setOpening] = useState(false);

  useEffect(() => {
    setReport(null); setError(null);
    if (!outcome || !inventoryId) return;
    const controller = new AbortController(); setLoading(true);
    fetch('/api/v1/analysis/root-cause-report', { method: 'POST', headers: { 'Content-Type': 'application/json' }, signal: controller.signal,
      body: JSON.stringify({ actualStartDate: outcome.actual.start, actualEndDate: outcome.actual.end,
        comparableStartDate: outcome.comparable.start, comparableEndDate: outcome.comparable.end, inventoryId }) })
      .then(async response => { if (!response.ok) throw new Error(`Explanation unavailable (${response.status})`); return response.json() as Promise<RootCauseReport>; })
      .then(setReport).catch(err => { if (!controller.signal.aborted) setError(err instanceof Error ? err.message : 'Explanation unavailable'); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [outcome?.analysisId, inventoryId, retry]);
  const periods = outcome ? { actualStartDate: outcome.actual.start, actualEndDate: outcome.actual.end, comparableStartDate: outcome.comparable.start, comparableEndDate: outcome.comparable.end } : null;
  const openStudio = async () => {
    if (!periods) return; setOpening(true); setError(null);
    try {
      const response = await fetch('/api/v1/report/instantiate', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...periods, mode: 'open_in_studio' }) });
      if (!response.ok) throw new Error(`Report preparation failed (${response.status})`);
      window.history.pushState({}, '', `/report-studio?${new URLSearchParams(periods)}`); window.dispatchEvent(new PopStateEvent('popstate'));
    } catch (err) { setError(err instanceof Error ? err.message : 'Report preparation failed'); }
    finally { setOpening(false); }
  };

  if (!isOpen) return null;

  return <aside className={isPinned
    ? "w-full lg:w-84 shrink-0 bg-white border-t lg:border-t-0 lg:border-l overflow-auto p-4 space-y-4 custom-scrollbar flex flex-col z-10 transition-all duration-200"
    : "absolute right-0 top-0 bottom-0 z-30 w-full sm:w-88 bg-white border-l border-slate-200 shadow-2xl overflow-auto p-4 space-y-4 custom-scrollbar flex flex-col transition-all duration-200 animate-in slide-in-from-right"}>
    <div className="flex items-center justify-between pb-2 border-b border-slate-200 shrink-0">
      <div className="flex items-center gap-2 min-w-0">
        <span className="w-2 h-2 rounded-full bg-period-actual shrink-0" />
        <h2 className="text-sm font-bold text-slate-800 truncate" title={inventoryId || 'Selected-node details'}>
          {inventoryId || 'Selected-node details'}
        </h2>
      </div>
      <div className="flex items-center gap-1 shrink-0">
        {onTogglePin && (
          <button
            type="button"
            onClick={onTogglePin}
            className={`p-1.5 rounded text-xs transition-colors ${
              isPinned ? 'bg-period-actual-subtle text-period-actual font-medium' : 'text-slate-400 hover:text-slate-700 hover:bg-slate-100'
            }`}
            title={isPinned ? 'Unpin drawer (switch to overlay)' : 'Pin drawer (dock side-by-side)'}
          >
            {isPinned ? <Pin className="w-3.5 h-3.5 fill-current" /> : <PinOff className="w-3.5 h-3.5" />}
          </button>
        )}
        {onClose && (
          <button
            type="button"
            onClick={onClose}
            className="p-1.5 rounded text-slate-400 hover:text-slate-700 hover:bg-slate-100 transition-colors"
            title="Close drawer"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        )}
      </div>
    </div>
    {!inventoryId && <p className="text-xs text-slate-600">Select a material to inspect its evidence, or a product to open production comparisons.</p>}
    {inventoryId && outcome && <section className="text-xs space-y-3"><h3 className="font-medium">Recorded period evidence</h3>{[['Actual', outcome.actual], ['Comparable', outcome.comparable]].map(([label, value]) => {
      const isActual = label === 'Actual';
      const snapshot = value as AnalysisOutcome['actual']; const node = snapshot.graph.nodes.find(item => item.inventoryId === inventoryId);
      return <div key={String(label)} className={`border rounded p-3 ${isActual ? 'bg-period-actual-subtle/40 border-period-actual-border/50' : 'bg-period-comparable-subtle/40 border-period-comparable-border/50'}`}>
        <div className="flex items-center gap-1.5 font-medium">
          <span className={`w-1.5 h-1.5 rounded-full ${isActual ? 'bg-period-actual' : 'bg-period-comparable'}`} />
          <span className={isActual ? 'text-period-actual-dark' : 'text-period-comparable-dark'}>{String(label)} · {snapshot.start}–{snapshot.end}</span>
        </div>
        <p className="mt-1">Quantity: {recorded(node?.quantity)}</p>
        <p>Recorded cost: {recorded(node?.cost)}</p>
      </div>;
    })}</section>}
    <section className="text-xs space-y-2 border-t pt-3"><div className="flex justify-between"><h3 className="font-medium">Backend explanation</h3>{inventoryId && <button aria-label="Retry explanation" onClick={() => setRetry(retry + 1)}><RefreshCw size={14} /></button>}</div>
      {loading && <p className="flex gap-2 items-center"><LoaderCircle size={14} className="animate-spin text-period-actual" /> Preparing explanation…</p>}
      {error && <p role="alert" className="text-variance-unfavourable bg-variance-unfavourable-subtle border border-variance-unfavourable-border/40 p-2 rounded">{error}</p>}
      {report && <div className={report.aiGenerated ? 'bg-provenance-ai-subtle/30 border border-provenance-ai-border/60 rounded-md p-2.5 space-y-1.5' : 'space-y-1.5'}>
        <p className="font-semibold text-slate-800">{report.categoryLabel} · <span className="font-normal text-slate-500">{report.certainty}</span></p>
        <ul className="list-disc pl-4 space-y-1 text-slate-600">{report.evidence.map((evidence, i) => <li key={i}>{evidence}</li>)}</ul>
        <div className="border-t border-slate-200/60 pt-2">
          <p className={`font-medium ${report.aiGenerated ? 'text-provenance-ai' : 'text-slate-700'}`}>
            {report.aiGenerated ? 'AI-assisted commentary' : 'Recorded-evidence explanation'}
          </p>
          <p className="text-slate-600 mt-0.5">{report.summary || report.aiMessage || 'Commentary unavailable.'}</p>
        </div>
      </div>}
    </section>
    <div className="flex flex-col gap-2 text-xs"><button disabled={!outcome || opening} className="border rounded p-2 flex items-center justify-center gap-2 disabled:opacity-50" onClick={openStudio}>{opening ? <LoaderCircle size={14} className="animate-spin" /> : <FileText size={14} />} Open Report Studio</button><button disabled={!outcome} className="border rounded p-2 disabled:opacity-50" onClick={() => setCommentary(true)}>Management commentary</button></div>
    {periods && <ManagementCommentaryModal isOpen={active && commentary} onClose={() => setCommentary(false)} {...periods} selectedNode={null} report={report} />}
  </aside>;
};
