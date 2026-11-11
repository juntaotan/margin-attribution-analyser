import React, { useEffect, useRef, useState } from 'react';
import { X } from 'lucide-react';
import type { Viewport } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import type { AnalysisOutcome } from '../../analysisRun';
import { INITIAL_QUERY, type DisplayItem } from '../../graphDisplay';
import { COLUMN, GraphPane } from './GraphPane';
import { useModalFocus } from './ProductComparisonModal';
import { useGraphProjection, type DisplayClient } from './useGraphProjection';

interface ProductTreeModalProps {
  client: DisplayClient | null;
  outcome: Pick<AnalysisOutcome, 'actual' | 'comparable'>;
  productId: string;
  comparisonAvailable: boolean;
  onClose: () => void;
  onCompareProduct: (inventoryId: string) => void;
}

/**
 * Modal showing the aligned comparable and actual trees of one product, read from the product towards its materials.
 * The projection uses the same compression as the table; bridges and remainders expand in place, and clicking a node
 * highlights it in both panes. *Production evidence* closes this modal and opens the product comparison.
 */
export const ProductTreeModal: React.FC<ProductTreeModalProps> = ({
  client,
  outcome,
  productId,
  comparisonAvailable,
  onClose,
  onCompareProduct,
}) => {
  const dialog = useRef<HTMLDivElement>(null);
  const projection = useGraphProjection(client, { ...INITIAL_QUERY, product: productId });
  const { result, busy } = projection;
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [viewport, setViewport] = useState<Viewport>({ x: 16, y: 8, zoom: 0.75 });
  const panes = useRef<HTMLDivElement>(null);
  const fitted = useRef(false);
  useModalFocus(dialog, true, onClose, [productId]);
  useEffect(() => {
    // Fit the first projection's columns to one pane's width, keeping the product anchored top-left.
    if (!result || fitted.current || !panes.current) return;
    fitted.current = true;
    setViewport({
      x: 16,
      y: 8,
      zoom: Math.min(0.85, Math.max(0.3, (panes.current.clientWidth / 2 - 24) / (result.columns * COLUMN))),
    });
  }, [result]);

  const activate = (item: DisplayItem) => {
    if (busy) return;
    if (item.expandKey) projection.expand(item.expandKey);
    else if (item.kind === 'node') setSelectedKey(item.key);
  };
  const stats = result?.stats;
  return (
    <div className="fixed inset-0 z-50 bg-slate-900/30 flex items-center justify-center p-3 modal-backdrop">
      <div
        ref={dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby="product-tree-title"
        className="bg-white rounded-xl border border-slate-300 shadow-xl w-full max-w-[1600px] h-[88vh] flex flex-col"
      >
        <header className="p-3 border-b flex items-start justify-between gap-3">
          <div>
            <h2 id="product-tree-title" className="text-base font-semibold">
              Product tree · {productId}
            </h2>
            <p className="text-xs text-slate-600 mt-1">
              Comparable {outcome.comparable.start}–{outcome.comparable.end} / Actual {outcome.actual.start}–
              {outcome.actual.end} · click … or +n to expand in place; Δ is actual minus comparable
            </p>
          </div>
          <button aria-label="Close product tree" className="p-1 border rounded" onClick={onClose}>
            <X size={18} />
          </button>
        </header>
        <div className="shrink-0 border-b px-3 py-2 flex flex-wrap gap-3 items-center text-xs">
          <button
            disabled={busy || !projection.canUndo}
            className="text-period-actual hover:underline disabled:opacity-40 font-medium"
            onClick={projection.undo}
          >
            Undo expansion
          </button>
          <button
            disabled={busy || !Object.keys(projection.query.expanded).length}
            className="text-period-actual hover:underline disabled:opacity-40 font-medium"
            onClick={projection.collapseAll}
          >
            Collapse all
          </button>
          <button
            className="px-2 py-0.5 rounded bg-period-actual-subtle text-period-actual border border-period-actual-border/50 hover:bg-period-actual hover:text-white transition-colors disabled:opacity-40 font-medium"
            disabled={!comparisonAvailable}
            onClick={() => {
              onClose();
              onCompareProduct(productId);
            }}
          >
            Production evidence: {productId}
          </button>
          {stats && <span className="text-slate-600">{stats.businessNodes} nodes shown</span>}
          {stats?.budgetReached && (
            <span className="text-amber-700">Display budget reached; further content stays folded</span>
          )}
        </div>
        {projection.error && (
          <p className="px-3 py-2 text-xs text-variance-unfavourable bg-variance-unfavourable-subtle" role="alert">
            {projection.error}
          </p>
        )}
        {busy && (
          <p className="px-3 py-1 text-xs" role="status">
            Preparing product tree…
          </p>
        )}
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
              selectedKey={selectedKey}
              viewport={viewport}
              onViewport={setViewport}
              onActivate={activate}
            />
            <GraphPane
              result={result}
              period="actual"
              label={`Actual ${outcome.actual.start} to ${outcome.actual.end}`}
              selectedKey={selectedKey}
              viewport={viewport}
              onViewport={setViewport}
              onActivate={activate}
            />
          </div>
        )}
      </div>
    </div>
  );
};
