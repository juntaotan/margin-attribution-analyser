import React, { useEffect, useRef, useState } from 'react';
import { X } from 'lucide-react';
import { recorded, type AnalysisOutcome, type PairResult } from '../../analysisRun';
import { CorrespondenceHeatmap } from './CorrespondenceHeatmap';

const TABS = ['Overview', 'Node correspondence', 'Data evidence', 'Calculation details'] as const;
export const ProductComparisonModal: React.FC<{ outcome: AnalysisOutcome | null; productId: string | null; alpha: number; onClose: () => void }> = ({ outcome, productId, alpha, onClose }) => {
  const dialog = useRef<HTMLDivElement>(null);
  const [tab, setTab] = useState<typeof TABS[number]>('Overview');
  const [selection, setSelection] = useState<Record<string, string>>({});
  useEffect(() => { setSelection({}); setTab('Overview'); }, [outcome?.analysisId]);
  useEffect(() => {
    if (!productId) return;
    const previous = document.activeElement as HTMLElement | null;
    dialog.current?.querySelector<HTMLButtonElement>('button')?.focus();
    const keyboard = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); onClose(); }
      if (event.key !== 'Tab') return;
      const controls = Array.from(dialog.current?.querySelectorAll<HTMLElement>('button:not([disabled]), input, select, summary, a[href], [tabindex="0"]') || []).filter(el => el.getClientRects().length > 0);
      const first = controls[0], last = controls[controls.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    };
    document.addEventListener('keydown', keyboard);
    const previousOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden';
    return () => { document.removeEventListener('keydown', keyboard); document.body.style.overflow = previousOverflow; previous?.focus(); };
  }, [productId, onClose]);
  if (!outcome || !productId) return null;
  const pairs = outcome.pairs.filter(pair => pair.productId === productId);
  const key = (pair: PairResult) => `${pair.leftProductionId}|${pair.rightProductionId}`;
  const pair = pairs.find(item => key(item) === selection[productId]) || pairs[0];
  const left = outcome.selection.trees.find(tree => tree.productionId === pair?.leftProductionId && tree.namespace === pair.namespace);
  const right = outcome.selection.trees.find(tree => tree.productionId === pair?.rightProductionId && tree.namespace === pair.namespace);
  const result = pair?.comparison?.result;
  const best = result?.starts[result.bestStart];
  const issues = outcome.actual.records.issues.filter(issue => issue.productId === productId);
  return <div className="fixed inset-0 z-50 bg-slate-900/30 flex items-center justify-center p-3 modal-backdrop">
    <div ref={dialog} role="dialog" aria-modal="true" aria-labelledby="comparison-title" className="comparison-modal bg-white rounded-xl border border-slate-300 shadow-xl w-full max-w-[1440px] max-h-[90vh] flex flex-col">
      <header className="p-4 border-b flex justify-between gap-3"><div><h2 id="comparison-title" className="text-base font-semibold">Product comparison · {productId}</h2><p className="text-xs text-slate-600 mt-1">{pair?.namespace || 'No selected instances'} · {left?.scope || 'Unavailable'} · {outcome.comparable.start}–{outcome.comparable.end} / {outcome.actual.start}–{outcome.actual.end}</p></div><button aria-label="Close product comparison" className="self-start p-1 border rounded" onClick={onClose}><X size={18} /></button></header>
      <div className="p-3 border-b space-y-2">
        {pairs.length > 0 && <label className="text-xs font-medium">Production pair <select aria-label="Production pair" value={pair ? key(pair) : ''} onChange={e => setSelection(previous => ({ ...previous, [productId]: e.target.value }))} className="ml-2 border rounded p-1 max-w-full">{pairs.map(item => <option key={key(item)} value={key(item)}>{item.leftProductionId} ↔ {item.rightProductionId} · {item.disposition} · FGW {item.comparison?.result?.starts[item.comparison.result.bestStart]?.costs.fgw.toPrecision(6) ?? 'Unavailable'}</option>)}</select></label>}
        <p className="text-xs">{pair?.disposition || 'No comparison pairs'} {pair?.diagnostic} {pair?.comparison?.statuses.join(' · ')}</p>
        {left && right && (!left.costComplete || !right.costComplete) && <p className="text-xs text-[#866322]">Total-cost evidence is incomplete. The topology comparison does not establish complete cost restoration.</p>}
      </div>
      <div role="tablist" aria-label="Product comparison tabs" className="flex flex-wrap border-b">{TABS.map(item => <button role="tab" aria-selected={tab === item} id={`comparison-tab-${item.replace(/ /g, '-')}`} aria-controls="comparison-content" key={item} className={`px-4 py-2 text-xs border-b-2 transition-colors ${tab === item ? 'border-[#466987] bg-[#E8EFF5] text-slate-900' : 'border-transparent text-slate-600'}`} onClick={() => setTab(item)}>{item}</button>)}</div>
      <div id="comparison-content" role="tabpanel" aria-labelledby={`comparison-tab-${tab.replace(/ /g, '-')}`} className="p-4 overflow-auto space-y-4 result-enter">
        {!pairs.length && <p className="text-sm">{outcome.status === 'NO_ABNORMAL_BRANCHES' ? 'No abnormal branches were found; production comparison was not required.' : 'No production pair is available for this product.'}</p>}
        {tab === 'Overview' && best && <><div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">{[['FGW objective', best.costs.fgw], ['Attribute objective', best.costs.material], ['Structural objective', best.costs.structure], ['Stopping status', best.status]].map(([label, value]) => <div key={String(label)} className="border rounded p-3"><p className="text-xs text-slate-600">{label}</p><p className="mt-1 font-medium tabular-nums">{typeof value === 'number' ? value.toPrecision(8) : value}</p></div>)}</div><p className="text-xs text-slate-600">α = {alpha}; weighted attribute = {((1-alpha)*best.costs.material).toPrecision(6)}; weighted structure = {(alpha*best.costs.structure).toPrecision(6)}. Lower FGW means closer correspondence under this configuration.</p></>}
        {(tab === 'Overview' || tab === 'Node correspondence') && (result && left && right ? <CorrespondenceHeatmap key={`${outcome.analysisId}:${pair && key(pair)}:${tab}`} result={result} left={left} right={right} /> : <div className="p-3 border rounded text-xs"><p>Correspondence matrix unavailable.</p><p className="break-all">{pair?.comparison?.missingVectors.length ? `Missing vectors: ${pair.comparison.missingVectors.join(', ')}` : pair?.diagnostic}</p>{outcome.embeddings?.manifests.filter(manifest => left?.nodes.some(node => node.occurrenceId === manifest.occurrenceId) || right?.nodes.some(node => node.occurrenceId === manifest.occurrenceId)).filter(manifest => manifest.statuses.length).map(manifest => <p key={manifest.occurrenceId} className="mt-1 break-all">{manifest.occurrenceId}: {manifest.statuses.join(', ')}</p>)}</div>)}
        {tab === 'Data evidence' && <><p className="text-xs text-slate-600">Original ledger entries for this product, including all available dates. Decimal amounts are shown as recorded.</p><div className="overflow-auto"><table className="w-full text-xs text-left"><thead><tr>{['Production','Issue / document','Date','Material','Quantity','Amount','Output quantity','Output cost'].map(label => <th key={label} className="p-2 border-b">{label}</th>)}</tr></thead><tbody>{issues.map(issue => <tr key={issue.lineId}>{[issue.productionId, `${issue.lineId} / ${issue.documentId}`, issue.date, issue.materialId, issue.quantity, issue.amount, issue.outputQuantity, issue.outputCost].map((value,i) => <td key={i} className="p-2 border-b font-mono">{recorded(value)}</td>)}</tr>)}</tbody></table></div>{[left,right].filter(Boolean).map(tree => tree && <details key={tree.productionId}><summary className="text-xs cursor-pointer">{tree.productionId} tree evidence and allocations</summary><pre className="text-xs whitespace-pre-wrap break-all bg-slate-50 p-3">{JSON.stringify({ nodes: tree.nodes, edges: tree.edges, statuses: tree.statuses }, null, 2)}</pre></details>)}</>}
        {tab === 'Calculation details' && <div className="space-y-3 text-xs"><p>{best?.status === 'FW_GAP_REACHED' ? 'Converged by Frank–Wolfe gap.' : `Convergence not established: ${best?.status || 'No numerical result'}.`}</p><pre className="whitespace-pre-wrap break-all bg-slate-50 p-3">{JSON.stringify({ rows: result?.rows, columns: result?.columns, alpha, bestStart: result?.bestStart, milliseconds: result?.totalMilliseconds, starts: result?.starts.map(start => ({ status: start.status, appliedUpdates: start.iterations, finalGap: start.finalGap, diagnostic: start.diagnostic, marginals: start.marginals, capturedTrace: start.trace })), embeddingVersions: pair?.comparison?.embeddingVersions, execution: outcome.execution, diagnostics: outcome.diagnostics, identityStatuses: outcome.selection.identities }, null, 2)}</pre></div>}
      </div>
    </div>
  </div>;
};
