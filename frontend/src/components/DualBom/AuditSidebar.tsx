import React, { useEffect, useState } from 'react';
import { LoaderCircle, FileText, RefreshCw } from 'lucide-react';
import { recorded, type AnalysisOutcome } from '../../analysisRun';
import { ManagementCommentaryModal } from './ManagementCommentaryModal';

interface RootCauseReport { inventoryId: string; category: string; categoryLabel: string; certainty: string; evidence: string[]; summary: string | null; aiGenerated: boolean; aiMessage: string | null }
/** Shows exact loaded evidence independently of the backend's optional AI explanation. */
export const AuditSidebar: React.FC<{ outcome: AnalysisOutcome | null; inventoryId: string | null; active?: boolean }> = ({ outcome, inventoryId, active = true }) => {
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
  return <aside className="w-full lg:w-80 shrink-0 bg-white border-t lg:border-l overflow-auto p-4 space-y-4 custom-scrollbar">
    <h2 className="text-sm font-semibold">{inventoryId || 'Selected-node details'}</h2>
    {!inventoryId && <p className="text-xs text-slate-600">Select a material to inspect its evidence, or a product to open production comparisons.</p>}
    {inventoryId && outcome && <section className="text-xs space-y-3"><h3 className="font-medium">Recorded period evidence</h3>{[['Actual', outcome.actual], ['Comparable', outcome.comparable]].map(([label, value]) => {
      const snapshot = value as AnalysisOutcome['actual']; const node = snapshot.graph.nodes.find(item => item.inventoryId === inventoryId);
      return <div key={String(label)} className="border rounded p-3"><p className="font-medium">{String(label)} · {snapshot.start}–{snapshot.end}</p><p className="mt-1">Quantity: {recorded(node?.quantity)}</p><p>Recorded cost: {recorded(node?.cost)}</p></div>;
    })}</section>}
    <section className="text-xs space-y-2 border-t pt-3"><div className="flex justify-between"><h3 className="font-medium">Backend explanation</h3>{inventoryId && <button aria-label="Retry explanation" onClick={() => setRetry(retry + 1)}><RefreshCw size={14} /></button>}</div>
      {loading && <p className="flex gap-2 items-center"><LoaderCircle size={14} className="animate-spin" /> Preparing explanation…</p>}
      {error && <p role="alert" className="text-[#A64242]">{error}</p>}
      {report && <><p>{report.categoryLabel} · {report.certainty}</p><ul className="list-disc pl-4 space-y-1">{report.evidence.map((evidence, i) => <li key={i}>{evidence}</li>)}</ul><div className="border-t pt-2"><p className="font-medium">{report.aiGenerated ? 'AI-assisted commentary' : 'Recorded-evidence explanation'}</p><p>{report.summary || report.aiMessage || 'Commentary unavailable.'}</p></div></>}
    </section>
    <div className="flex flex-col gap-2 text-xs"><button disabled={!outcome || opening} className="border rounded p-2 flex items-center justify-center gap-2 disabled:opacity-50" onClick={openStudio}>{opening ? <LoaderCircle size={14} className="animate-spin" /> : <FileText size={14} />} Open Report Studio</button><button disabled={!outcome} className="border rounded p-2 disabled:opacity-50" onClick={() => setCommentary(true)}>Management commentary</button></div>
    {periods && <ManagementCommentaryModal isOpen={active && commentary} onClose={() => setCommentary(false)} {...periods} selectedNode={null} report={report} />}
  </aside>;
};
