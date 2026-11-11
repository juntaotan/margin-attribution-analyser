import React, { useCallback, useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import type { AnalysisOutcome } from '../../analysisRun';
import type { StreamNode } from '../../analysisGraph';
import { COGS_ID, INITIAL_QUERY, type DisplayItem, type DisplayResult, type Period } from '../../graphDisplay';
import { ProductTreeModal } from './ProductTreeModal';
import { useDisplayClient, useGraphProjection } from './useGraphProjection';

interface DualBomTreeTableProps {
  outcome: Pick<AnalysisOutcome, 'analysisId' | 'actual' | 'comparable' | 'branches'>;
  comparisonAvailable: boolean;
  currency: string;
  onSelectNode: (node: StreamNode) => void;
  onCompareProduct: (inventoryId: string) => void;
}
const COVERAGE = [20, 40, 50, 60, 80, 100];
const PERIODS: Period[] = ['comparable', 'actual'];
const NUMBER = 'px-2 py-1.5 text-right font-mono tabular-nums whitespace-nowrap';

/** Colours a signed difference: increases are unfavourable, decreases favourable, unavailable is muted. */
const DeltaText: React.FC<{ value: string | null | undefined }> = ({ value }) =>
  value == null ? (
    <span className="text-slate-400">—</span>
  ) : (
    <span
      className={
        value.startsWith('-')
          ? 'text-variance-favourable'
          : value === '0'
            ? 'text-variance-neutral'
            : 'text-variance-unfavourable'
      }
    >
      {value}
    </span>
  );

/** One period's quantity, total cost and unit cost; a node absent from the period spans all three cells. */
const PeriodCells: React.FC<{ item: DisplayItem; period: Period }> = ({ item, period }) => {
  const tint = period === 'actual' ? 'bg-period-actual-subtle/40' : 'bg-period-comparable-subtle/40';
  const value = item[period];
  if (item.kind !== 'node') return <td colSpan={3} className={tint} />;
  if (!value)
    return (
      <td colSpan={3} className={`${tint} px-2 py-1.5 text-center text-slate-500 italic`}>
        Not recorded in this period
      </td>
    );
  const cogs = item.inventoryId === COGS_ID;
  return (
    <>
      <td className={`${NUMBER} ${tint}`}>
        {cogs ? '—' : (value.quantity ?? 'Unavailable')}
        {!value.linked && (
          <div className="text-[10px] font-sans text-amber-700" title="Relationship to parent not recorded">
            Not linked
          </div>
        )}
      </td>
      <td className={`${NUMBER} ${tint}`}>{value.cost ?? '—'}</td>
      <td className={`${NUMBER} ${tint}`}>{value.unitCost ?? '—'}</td>
    </>
  );
};

/** The indented item cell: open/close control for products, label, evidence badges and fold notes. */
const ItemCell: React.FC<{ item: DisplayItem; onToggle: () => void }> = ({ item, onToggle }) => (
  <td
    className="sticky left-0 z-[1] bg-inherit px-2 py-1.5 min-w-[260px]"
    style={{ paddingLeft: 8 + item.depth * 18 }}
  >
    <div className="flex items-center gap-1.5">
      {item.open != null ? (
        <button
          type="button"
          tabIndex={-1}
          aria-label={`${item.open ? 'Close' : 'Open'} materials of ${item.label}`}
          className="p-0.5 rounded hover:bg-slate-200"
          onClick={(event) => {
            event.stopPropagation();
            onToggle();
          }}
        >
          {item.open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        </button>
      ) : (
        <span className="inline-block w-[18px]" />
      )}
      <span
        className={`truncate ${item.kind === 'node' ? 'font-medium' : 'font-semibold text-slate-600'}`}
        title={item.label}
      >
        {item.label}
      </span>
      {item.abnormal && (
        <span className="text-anomaly-boundary bg-anomaly-boundary-subtle border border-anomaly-boundary-border/40 font-bold text-[10px] px-1 rounded">
          Abnormal
        </span>
      )}
      {item.shared && (
        <span className="text-[10px] text-slate-500" title="Recorded receiving items in either period">
          ×{item.shared}
        </span>
      )}
    </div>
    {item.note && <div className="text-[10px] text-slate-600 pl-6 truncate">{item.note}</div>}
    {item.boundary && (
      <div className="text-[10px] text-anomaly-boundary font-medium pl-6">
        Boundary difference {item.boundary.difference} · {item.boundary.count} paths
      </div>
    )}
  </td>
);

/**
 * Present the aligned comparable and actual trees as one table reading Cost of Goods Sold -> products -> materials.
 * Each row is one display item; the comparable and actual column groups show that item's recorded values, and the
 * difference group shows total and unit differences. Products start closed; clicking a product opens its tree graph,
 * and its chevron (or the arrow keys) opens its materials in place.
 */
export const DualBomTreeTable: React.FC<DualBomTreeTableProps> = ({
  outcome,
  currency,
  onSelectNode,
  onCompareProduct,
  comparisonAvailable,
}) => {
  const client = useDisplayClient(outcome);
  const projection = useGraphProjection(client, INITIAL_QUERY);
  const { query, result, busy } = projection;
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [treeProduct, setTreeProduct] = useState<string | null>(null);
  const closeTree = useCallback(() => setTreeProduct(null), []);

  const toggle = (item: DisplayItem) => {
    if (busy || !item.expandKey) return;
    if (item.open) projection.collapse(item.key);
    else projection.expand(item.expandKey);
  };
  const activate = (item: DisplayItem) => {
    if (busy) return;
    if (item.kind !== 'node') {
      if (item.expandKey) projection.expand(item.expandKey);
      return;
    }
    setSelectedKey(item.key);
    if (item.product) {
      setTreeProduct(item.inventoryId!);
      return;
    }
    const period: Period = item.actual ? 'actual' : 'comparable';
    const value = item[period];
    if (value)
      onSelectNode({
        id: `${outcome.analysisId}:${period}:node:${value.position}`,
        position: value.position,
        inventoryId: item.inventoryId!,
        quantity: Number(value.quantity),
        cost: value.cost == null ? null : Number(value.cost),
      });
  };
  const keyboard = (event: React.KeyboardEvent<HTMLTableRowElement>, item: DisplayItem) => {
    const row = event.currentTarget;
    const move = (target: Element | null) => {
      event.preventDefault();
      (target as HTMLElement | null)?.focus();
    };
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      activate(item);
    } else if (event.key === 'ArrowDown') move(row.nextElementSibling);
    else if (event.key === 'ArrowUp') move(row.previousElementSibling);
    else if (event.key === 'ArrowRight' && item.open === false) {
      event.preventDefault();
      toggle(item);
    } else if (event.key === 'ArrowLeft' && item.open) {
      event.preventDefault();
      toggle(item);
    }
  };

  const stats = result?.stats;
  return (
    <section
      data-analysis-id={outcome.analysisId}
      className="w-full h-full min-h-[400px] flex flex-col bg-surface-canvas relative"
    >
      <div className="shrink-0 border-b bg-white px-3 py-2 flex flex-wrap gap-3 items-center text-xs">
        <label>
          Initial coverage of Σ|ΔCOGS|{' '}
          <select
            aria-label="Initial COGS difference coverage"
            className="border rounded px-1 py-0.5 ml-1"
            value={query.coverage}
            disabled={busy}
            onChange={(event) => projection.setCoverage(Number(event.target.value))}
          >
            {COVERAGE.map((value) => (
              <option key={value} value={value}>
                {value}%
              </option>
            ))}
          </select>
        </label>
        {stats && (
          <span>
            {stats.selectedProducts} of {stats.products} products · cover {stats.coveredAbsolute} of{' '}
            {stats.totalAbsolute} {currency}
            {stats.coveredPerMille != null && ` (${(stats.coveredPerMille / 10).toFixed(1)}%)`} · ΔCOGS{' '}
            {stats.cogsDelta ?? 'unavailable'}
          </span>
        )}
        <button
          disabled={busy || !projection.canUndo}
          className="text-period-actual hover:underline disabled:opacity-40 font-medium"
          onClick={projection.undo}
        >
          Undo expansion
        </button>
        <button
          disabled={busy || !Object.keys(query.expanded).length}
          className="text-period-actual hover:underline disabled:opacity-40 font-medium"
          onClick={projection.collapseAll}
        >
          Collapse all
        </button>
      </div>
      {projection.error && (
        <p
          className="px-3 py-2 text-xs text-variance-unfavourable bg-variance-unfavourable-subtle border-b border-variance-unfavourable-border/40"
          role="alert"
        >
          {projection.error}
        </p>
      )}
      {busy && (
        <p className="px-3 py-1 text-xs" role="status">
          Preparing bounded table…
        </p>
      )}
      {stats && <ProjectionNotes stats={stats} />}
      {result && (
        <div className={`flex-1 min-h-0 overflow-auto ${busy ? 'pointer-events-none opacity-60' : ''}`} aria-busy={busy}>
          <table role="treegrid" aria-label="Dual-BOM comparison table" className="min-w-full text-xs border-collapse">
            <thead className="sticky top-0 z-[2] bg-white shadow-[0_1px_0_#cbd5e1]">
              <tr>
                <th rowSpan={2} className="sticky left-0 z-[3] bg-white px-2 py-1.5 text-left align-bottom">
                  Item
                </th>
                {PERIODS.map((period) => (
                  <th
                    key={period}
                    colSpan={3}
                    className={`px-2 py-1.5 text-left border-l ${
                      period === 'actual'
                        ? 'bg-period-actual-subtle text-period-actual-dark'
                        : 'bg-period-comparable-subtle text-period-comparable-dark'
                    }`}
                  >
                    {period === 'actual' ? 'Actual' : 'Comparable'} {outcome[period].start} to {outcome[period].end}
                  </th>
                ))}
                <th colSpan={2} className="px-2 py-1.5 text-left border-l">
                  Difference (actual − comparable)
                </th>
              </tr>
              <tr className="text-[11px] text-slate-600">
                {PERIODS.map((period) =>
                  ['Quantity', 'Total cost', 'Unit cost'].map((label, at) => (
                    <th key={`${period}${label}`} className={`px-2 py-1 text-right font-medium ${at ? '' : 'border-l'}`}>
                      {label}
                    </th>
                  )),
                )}
                <th className="px-2 py-1 text-right font-medium border-l">Total</th>
                <th className="px-2 py-1 text-right font-medium">Unit</th>
              </tr>
            </thead>
            <tbody>
              {result.items.map((item) => (
                <tr
                  key={item.key}
                  data-key={item.key}
                  tabIndex={0}
                  aria-level={item.depth + 1}
                  aria-expanded={item.open}
                  aria-selected={selectedKey === item.key}
                  onClick={() => activate(item)}
                  onKeyDown={(event) => keyboard(event, item)}
                  className={`border-b border-slate-200 cursor-pointer outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-period-actual ${
                    selectedKey === item.key
                      ? 'bg-period-actual-subtle'
                      : item.abnormal
                        ? 'bg-anomaly-boundary-subtle hover:bg-orange-100'
                        : item.kind === 'node'
                          ? 'bg-white hover:bg-surface-subtle'
                          : 'bg-surface-canvas hover:bg-surface-subtle'
                  }`}
                >
                  <ItemCell item={item} onToggle={() => toggle(item)} />
                  {PERIODS.map((period) => (
                    <PeriodCells key={period} item={item} period={period} />
                  ))}
                  <td className={`${NUMBER} border-l`}>
                    {item.kind === 'node' && <DeltaText value={item.delta} />}
                    {item.parentDelta !== undefined && (
                      <div className="text-[10px] text-slate-500" title="Issued-amount difference within the parent">
                        in parent {item.parentDelta ?? 'unavailable'}
                      </div>
                    )}
                  </td>
                  <td className={NUMBER}>
                    {item.kind === 'node' && item.inventoryId !== COGS_ID && <DeltaText value={item.unitDelta} />}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {treeProduct && (
        <ProductTreeModal
          key={treeProduct}
          client={client}
          outcome={outcome}
          productId={treeProduct}
          comparisonAvailable={comparisonAvailable}
          onClose={closeTree}
          onCompareProduct={onCompareProduct}
        />
      )}
    </section>
  );
};

/** Projection size and the warnings that qualify it: excluded products, unlinked paths, budget and diagnostics. */
const ProjectionNotes: React.FC<{ stats: DisplayResult['stats'] }> = ({ stats }) => (
  <div className="px-3 py-1 text-[11px] text-slate-600 flex flex-wrap gap-x-3">
    <span>
      {stats.totalNodes} recorded nodes · {stats.businessNodes} shown · click a product for its tree, its chevron for
      its materials, and … or +n to expand in place; unit cost is cost ÷ quantity rounded to six decimal places
    </span>
    {stats.unavailableProducts > 0 && (
      <span className="text-amber-700">
        {stats.unavailableProducts} products have unavailable sales cost and are excluded from coverage
      </span>
    )}
    {stats.unlinkedBranches > 0 && (
      <span className="text-amber-700">{stats.unlinkedBranches} abnormal paths have no recorded route to COGS</span>
    )}
    {stats.budgetReached && (
      <span className="text-amber-700">Display budget reached; further content stays folded</span>
    )}
    {stats.diagnostics.map((diagnostic) => (
      <span key={diagnostic} className="text-amber-700">
        {diagnostic}
      </span>
    ))}
  </div>
);
