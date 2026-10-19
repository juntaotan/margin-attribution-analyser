import React, { useEffect, useRef, useState } from 'react';
import { X } from 'lucide-react';
import type { AnalysisOutcome, PairResult } from '../../analysisRun';
import { CalculationDetailsPanel, CorrespondencePanel, DataEvidencePanel, OverviewPanel } from './ComparisonPanels';

const TABS = ['Overview', 'Node correspondence', 'Data evidence', 'Calculation details'] as const;
type Tab = (typeof TABS)[number];

const pairKey = (pair: PairResult) => `${pair.leftProductionId}|${pair.rightProductionId}`;
const tabId = (tab: Tab) => `comparison-tab-${tab.replace(/ /g, '-')}`;

/**
 * While `open`, focuses the dialog's first button, keeps Tab focus inside the dialog, closes on Escape and
 * stops the page from scrolling. Focus returns to the previously focused element afterwards, if still visible.
 * The dialog is set up again whenever a value in `content` changes.
 */
function useModalFocus(
  dialog: React.RefObject<HTMLDivElement>,
  open: boolean,
  onClose: () => void,
  content: readonly unknown[],
) {
  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement as HTMLElement | null;
    dialog.current?.querySelector<HTMLButtonElement>('button')?.focus();
    const keyboard = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        onClose();
      }
      if (event.key !== 'Tab') return;
      const controls = Array.from(
        dialog.current?.querySelectorAll<HTMLElement>(
          'button:not([disabled]), input, select, summary, a[href], [tabindex="0"]',
        ) || [],
      ).filter((el) => el.getClientRects().length > 0);
      const first = controls[0],
        last = controls[controls.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last?.focus();
      }
      if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first?.focus();
      }
    };
    document.addEventListener('keydown', keyboard);
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', keyboard);
      document.body.style.overflow = previousOverflow;
      if (previous?.getClientRects().length) previous.focus();
    };
  }, [open, onClose, ...content]);
}

/**
 * Modal comparing the production pairs of one product: FGW objectives, node correspondence, the underlying ledger
 * evidence and solver details. The selected pair is remembered per product until a new analysis arrives.
 */
export const ProductComparisonModal: React.FC<{
  outcome: AnalysisOutcome | null;
  productId: string | null;
  alpha: number;
  onClose: () => void;
  active?: boolean;
}> = ({ outcome, productId, alpha, onClose, active = true }) => {
  const dialog = useRef<HTMLDivElement>(null);
  const [tab, setTab] = useState<Tab>('Overview');
  const [selection, setSelection] = useState<Record<string, string>>({});
  useEffect(() => {
    setSelection({});
    setTab('Overview');
  }, [outcome?.analysisId]);
  useModalFocus(dialog, Boolean(active && productId && outcome), onClose, [productId, outcome]);
  if (!active || !outcome || !productId) return null;

  const pairs = outcome.pairs.filter((pair) => pair.productId === productId);
  const pair = pairs.find((item) => pairKey(item) === selection[productId]) || pairs[0];
  const findTree = (productionId?: string) =>
    outcome.selection.trees.find((tree) => tree.productionId === productionId && tree.namespace === pair.namespace);
  const left = findTree(pair?.leftProductionId);
  const right = findTree(pair?.rightProductionId);
  const result = pair?.comparison?.result;
  const best = result?.starts[result.bestStart];

  return (
    <div className="fixed inset-0 z-50 bg-slate-900/30 flex items-center justify-center p-3 modal-backdrop">
      <div
        ref={dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby="comparison-title"
        className="comparison-modal bg-white rounded-xl border border-slate-300 shadow-xl w-full max-w-[1440px] max-h-[90vh] flex flex-col"
      >
        <header className="p-4 border-b flex justify-between gap-3">
          <div>
            <h2 id="comparison-title" className="text-base font-semibold">
              Product comparison · {productId}
            </h2>
            <p className="text-xs text-slate-600 mt-1">
              {pair?.namespace || 'No selected instances'} · {left?.scope || 'Unavailable'} · {outcome.comparable.start}
              –{outcome.comparable.end} / {outcome.actual.start}–{outcome.actual.end}
            </p>
          </div>
          <button aria-label="Close product comparison" className="self-start p-1 border rounded" onClick={onClose}>
            <X size={18} />
          </button>
        </header>
        <div className="p-3 border-b space-y-2">
          {pairs.length > 0 && (
            <PairSelector
              pairs={pairs}
              value={pair ? pairKey(pair) : ''}
              onChange={(value) => setSelection((previous) => ({ ...previous, [productId]: value }))}
            />
          )}
          <p className="text-xs">
            {pair?.disposition || 'No comparison pairs'} {pair?.diagnostic} {pair?.comparison?.statuses.join(' · ')}
          </p>
          {left && right && (!left.costComplete || !right.costComplete) && (
            <p className="text-xs text-amber-800 bg-amber-50 border border-amber-200/80 px-2.5 py-1 rounded">
              Total-cost evidence is incomplete. The topology comparison does not establish complete cost restoration.
            </p>
          )}
        </div>
        <ComparisonTabs tab={tab} onChange={setTab} />
        <div
          id="comparison-content"
          role="tabpanel"
          aria-labelledby={tabId(tab)}
          className="p-4 overflow-auto space-y-4 result-enter"
        >
          {!pairs.length && (
            <p className="text-sm">
              {outcome.status === 'NO_ABNORMAL_BRANCHES'
                ? 'No abnormal branches were found; production comparison was not required.'
                : 'No production pair is available for this product.'}
            </p>
          )}
          {tab === 'Overview' && best && <OverviewPanel best={best} alpha={alpha} />}
          {(tab === 'Overview' || tab === 'Node correspondence') && (
            <CorrespondencePanel
              outcome={outcome}
              pair={pair}
              left={left}
              right={right}
              heatmapKey={`${outcome.analysisId}:${pair && pairKey(pair)}:${tab}`}
            />
          )}
          {tab === 'Data evidence' && (
            <DataEvidencePanel
              issues={outcome.actual.records.issues.filter((issue) => issue.productId === productId)}
              trees={[left, right]}
            />
          )}
          {tab === 'Calculation details' && (
            <CalculationDetailsPanel outcome={outcome} pair={pair} result={result} best={best} alpha={alpha} />
          )}
        </div>
      </div>
    </div>
  );
};

const PairSelector: React.FC<{ pairs: PairResult[]; value: string; onChange: (value: string) => void }> = ({
  pairs,
  value,
  onChange,
}) => (
  <label className="text-xs font-medium">
    Production pair{' '}
    <select
      aria-label="Production pair"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className="ml-2 border rounded p-1 max-w-full"
    >
      {pairs.map((item) => (
        <option key={pairKey(item)} value={pairKey(item)}>
          {item.leftProductionId} ↔ {item.rightProductionId} · {item.disposition} · FGW{' '}
          {item.comparison?.result?.starts[item.comparison.result.bestStart]?.costs.fgw.toPrecision(6) ?? 'Unavailable'}
        </option>
      ))}
    </select>
  </label>
);

const ComparisonTabs: React.FC<{ tab: Tab; onChange: (tab: Tab) => void }> = ({ tab, onChange }) => (
  <div role="tablist" aria-label="Product comparison tabs" className="flex flex-wrap border-b">
    {TABS.map((item) => (
      <button
        role="tab"
        aria-selected={tab === item}
        id={tabId(item)}
        aria-controls="comparison-content"
        key={item}
        className={`px-4 py-2 text-xs border-b-2 transition-colors ${tab === item ? 'border-period-actual bg-period-actual-subtle text-period-actual-dark font-semibold' : 'border-transparent text-slate-600 hover:text-slate-900'}`}
        onClick={() => onChange(item)}
      >
        {item}
      </button>
    ))}
  </div>
);
