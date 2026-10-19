import React, { useMemo, useState } from 'react';
import { correspondenceValue, recorded, type FgwResult, type ProductionTree } from '../../analysisRun';

type ValueMode = 'share' | 'mass';
type Cell = [number, number];
type MatrixNode = FgwResult['left']['nodes'][number];
type IndexedNode = { node: MatrixNode; i: number };

function getHeatmapCellAppearance(
  value: number | null,
  ceiling: number,
  tableMode: boolean,
): { background: string; color: string } {
  if (tableMode) return { background: '#ffffff', color: '#0f172a' };
  if (value == null) return { background: '#f8fafc', color: '#94a3b8' };
  if (value <= 0 || ceiling <= 0) return { background: '#ffffff', color: '#94a3b8' };
  const ratio = Math.min(1, Math.max(0, value / ceiling));
  const lightness = 96 - ratio * 68;
  const saturation = 70 + ratio * 25;
  const background = `hsl(217, ${saturation.toFixed(0)}%, ${lightness.toFixed(1)}%)`;
  const color = ratio > 0.5 ? '#ffffff' : '#0f172a';
  return { background, color };
}

const label = (node: { materialId: string; occurrenceId: string }) => `${node.materialId} · ${node.occurrenceId}`;

const valueText = (value: number | null, mode: ValueMode) =>
  value == null ? 'Unavailable' : mode === 'share' ? `${(value * 100).toPrecision(4)}%` : value.toPrecision(6);

/**
 * FGW correspondence matrix between the baseline (rows) and comparison (columns) production trees, shown as a
 * heatmap or plain table of row shares or raw masses. Rows and columns can be filtered by occurrence; filtering
 * keeps the original row denominators. Selecting a cell shows the recorded evidence of both occurrences.
 */
export const CorrespondenceHeatmap: React.FC<{ result: FgwResult; left: ProductionTree; right: ProductionTree }> = ({
  result,
  left,
  right,
}) => {
  const [mode, setMode] = useState<ValueMode>('share');
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<Cell | null>(null);
  const [table, setTable] = useState(false);
  const labels = useMemo(() => ({ rows: result.left.nodes, columns: result.right.nodes }), [result]);
  const query = search.trim().toLowerCase();
  const matches = (node: { materialId: string; occurrenceId: string }, production: string) =>
    `${node.materialId} ${node.occurrenceId} ${production}`.toLowerCase().includes(query);
  const rows = labels.rows.map((node, i) => ({ node, i })).filter(({ node }) => matches(node, left.productionId));
  const columns = labels.columns
    .map((node, i) => ({ node, i }))
    .filter(({ node }) => matches(node, right.productionId));
  const ceiling = mode === 'share' ? 1 : Math.max(...result.p);
  const selectedIds = selected
    ? ([labels.rows[selected[0]].occurrenceId, labels.columns[selected[1]].occurrenceId] as const)
    : null;

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-3">
        <label className="text-xs font-medium text-slate-700">
          Find occurrence{' '}
          <input
            className="ml-2 border border-slate-300 rounded px-2 py-1 text-slate-800 focus:border-period-actual focus:ring-1 focus:ring-period-actual/20"
            aria-label="Find heatmap occurrence"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </label>
        <label className="text-xs font-medium text-slate-700">
          Values{' '}
          <select
            className="ml-2 border border-slate-300 rounded p-1 text-slate-800 focus:border-period-actual focus:ring-1 focus:ring-period-actual/20"
            value={mode}
            onChange={(e) => setMode(e.target.value as ValueMode)}
          >
            <option value="share">Baseline row share</option>
            <option value="mass">Raw correspondence mass</option>
          </select>
        </label>
        <button
          className="border border-slate-300 hover:bg-slate-50 rounded px-2.5 py-1 text-xs font-medium text-slate-700 transition-colors shadow-2xs"
          onClick={() => setTable(!table)}
        >
          {table ? 'Show heatmap' : 'Show equivalent table'}
        </button>
      </div>
      <HeatmapLegend mode={mode} ceiling={ceiling} />
      <p className="text-xs text-slate-600">
        Showing {rows.length}/{result.rows} rows and {columns.length}/{result.columns} columns. Filtering retains the
        original row denominators.
      </p>
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_260px]">
        <CorrespondenceMatrix
          result={result}
          left={left}
          right={right}
          rows={rows}
          columns={columns}
          mode={mode}
          ceiling={ceiling}
          table={table}
          selected={selected}
          onSelect={setSelected}
        />
        <aside
          className="border border-slate-200 bg-white rounded-lg p-3 text-xs space-y-2 shadow-2xs"
          aria-live="polite"
        >
          <h4 className="font-semibold text-slate-900 border-b pb-1.5">Selected correspondence</h4>
          {selected ? (
            <SelectedCorrespondence
              result={result}
              trees={[left, right]}
              cell={selected}
              nodes={[labels.rows[selected[0]], labels.columns[selected[1]]]}
            />
          ) : (
            <p className="text-slate-500">Select a cell to inspect both occurrences and their recorded evidence.</p>
          )}
        </aside>
      </div>
      <ProductionTreesDetails trees={[left, right]} selectedIds={selectedIds} />
    </div>
  );
};

const HeatmapLegend: React.FC<{ mode: ValueMode; ceiling: number }> = ({ mode, ceiling }) => (
  <>
    <p className="text-xs text-slate-600">
      {mode === 'share' ? 'Row share P[i,j] / p[i]: 0–100%' : `Raw mass P[i,j]: 0–${ceiling.toPrecision(4)}`} · Higher
      intensity means greater correspondence weight, not confidence or causation.
    </p>
    <div className="flex items-center gap-2 text-xs" aria-label="Heatmap legend">
      <span className="font-mono text-slate-500">0</span>
      <div
        className="h-3 w-48 rounded border border-slate-300 shadow-2xs"
        style={{
          background:
            'linear-gradient(to right, #ffffff 0%, hsl(217, 70%, 96%) 4%, hsl(217, 85%, 62%) 50%, hsl(217, 95%, 28%) 100%)',
        }}
      />
      <span className="font-mono text-slate-700 font-semibold">
        {mode === 'share' ? '100%' : ceiling.toPrecision(4)}
      </span>
    </div>
  </>
);

interface CorrespondenceMatrixProps {
  result: FgwResult;
  left: ProductionTree;
  right: ProductionTree;
  rows: IndexedNode[];
  columns: IndexedNode[];
  mode: ValueMode;
  ceiling: number;
  /** Shows plain cells instead of heatmap colours. */
  table: boolean;
  selected: Cell | null;
  onSelect: (cell: Cell) => void;
}

/** Scrollable matrix with sticky headers; the row and column of the selected or hovered cell are highlighted. */
const CorrespondenceMatrix: React.FC<CorrespondenceMatrixProps> = ({
  result,
  left,
  right,
  rows,
  columns,
  mode,
  ceiling,
  table,
  selected,
  onSelect,
}) => {
  const [hovered, setHovered] = useState<Cell | null>(null);
  return (
    <div
      className="overflow-auto max-h-[48vh] border border-slate-200 rounded-lg custom-scrollbar shadow-2xs"
      tabIndex={0}
      aria-label="Correspondence matrix viewport"
    >
      <table className="text-xs border-collapse w-max min-w-full">
        <caption className="sr-only">Baseline occurrences in rows; comparison occurrences in columns</caption>
        <thead>
          <tr>
            <th className="sticky top-0 left-0 bg-slate-50 z-20 border border-slate-200 p-2 font-mono text-[11px] text-slate-600">
              <span className="text-period-comparable font-semibold">{left.productionId} ↓</span> /{' '}
              <span className="text-period-actual font-semibold">{right.productionId} →</span>
            </th>
            {columns.map(({ node, i }) => (
              <th
                key={node.occurrenceId}
                scope="col"
                title={node.occurrenceId}
                className={`sticky top-0 z-10 border border-slate-200 p-2 max-w-[130px] transition-colors ${selected?.[1] === i || hovered?.[1] === i ? 'bg-period-actual-subtle text-period-actual-dark' : 'bg-slate-50'}`}
              >
                <span className="block font-semibold truncate">{node.materialId}</span>
                <span className="font-normal text-slate-500 block truncate">
                  #{i} · {right.productionId}
                </span>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map(({ node, i }) => (
            <tr key={node.occurrenceId}>
              <th
                scope="row"
                title={node.occurrenceId}
                className={`sticky left-0 z-10 border border-slate-200 p-2 text-left max-w-[180px] transition-colors ${selected?.[0] === i || hovered?.[0] === i ? 'bg-period-comparable-subtle text-period-comparable-dark' : 'bg-slate-50'}`}
              >
                <span className="block font-semibold truncate">{node.materialId}</span>
                <span className="font-normal text-slate-500 block truncate">
                  #{i} · {left.productionId}
                </span>
              </th>
              {columns.map(({ node: column, i: j }) => {
                const value = correspondenceValue(result, i, j, mode);
                const isSelectedCell = selected?.[0] === i && selected[1] === j;
                const { background, color } = getHeatmapCellAppearance(value, ceiling, table);
                return (
                  <td key={column.occurrenceId} className="border border-slate-200 p-0">
                    <button
                      type="button"
                      onMouseEnter={() => setHovered([i, j])}
                      onMouseLeave={() => setHovered(null)}
                      className={`heatmap-cell min-w-[72px] min-h-[44px] w-full p-2 transition-transform ${isSelectedCell ? 'ring-2 ring-inset ring-period-actual font-bold' : 'hover:scale-[0.98]'}`}
                      style={{ background, color }}
                      aria-label={`${label(node)} to ${label(column)}: ${valueText(value, mode)}`}
                      aria-pressed={isSelectedCell}
                      title={`P[${i},${j}] = ${correspondenceValue(result, i, j, 'mass') ?? 'Unavailable'}`}
                      onClick={() => onSelect([i, j])}
                    >
                      {valueText(value, mode)}
                    </button>
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      {(!rows.length || !columns.length) && (
        <p className="p-4 text-xs text-slate-500">No occurrences match this filter.</p>
      )}
    </div>
  );
};

interface SelectedCorrespondenceProps {
  result: FgwResult;
  /** Baseline and comparison trees. */
  trees: [ProductionTree, ProductionTree];
  cell: Cell;
  /** Matrix row and column nodes of the selected cell. */
  nodes: [MatrixNode, MatrixNode];
}

/** Exact mass and row share of the selected cell, and the recorded evidence of both occurrences. */
const SelectedCorrespondence: React.FC<SelectedCorrespondenceProps> = ({ result, trees, cell, nodes }) => {
  const selectedMass = correspondenceValue(result, cell[0], cell[1], 'mass');
  const selectedShare = correspondenceValue(result, cell[0], cell[1], 'share');
  return (
    <>
      <p className="break-all font-medium text-slate-800">
        {label(nodes[0])} ↔ {label(nodes[1])}
      </p>
      <div className="bg-slate-50 p-2 rounded border border-slate-200/80 space-y-1 font-mono text-[11px]">
        <p>
          Raw mass:{' '}
          <span className="font-semibold text-slate-900">
            {selectedMass == null ? 'Unavailable' : selectedMass.toPrecision(10)}
          </span>
        </p>
        <p>
          Row share:{' '}
          <span className="font-semibold text-slate-900">
            {selectedShare == null ? 'Unavailable' : `${(selectedShare * 100).toPrecision(8)}%`}
          </span>
        </p>
      </div>
      {trees.map((tree, index) => (
        <OccurrenceEvidence
          key={tree.productionId}
          tree={tree}
          occurrenceId={nodes[index].occurrenceId}
          isActualTree={index === 1}
        />
      ))}
    </>
  );
};

const OccurrenceEvidence: React.FC<{ tree: ProductionTree; occurrenceId: string; isActualTree: boolean }> = ({
  tree,
  occurrenceId,
  isActualTree,
}) => {
  const occurrence = tree.nodes.find((node) => node.occurrenceId === occurrenceId);
  return (
    <div
      className={`border rounded p-2.5 space-y-1 ${isActualTree ? 'border-period-actual-border/50 bg-period-actual-subtle/30' : 'border-period-comparable-border/50 bg-period-comparable-subtle/30'}`}
    >
      <div className="flex items-center gap-1.5 font-semibold text-slate-800">
        <span className={`w-1.5 h-1.5 rounded-full ${isActualTree ? 'bg-period-actual' : 'bg-period-comparable'}`} />
        <span>
          {tree.productionId} ({isActualTree ? 'Actual' : 'Comparable'})
        </span>
      </div>
      <p>
        Quantity:{' '}
        <span className="font-mono">
          {recorded(occurrence?.quantity)} {occurrence?.unit}
        </span>
      </p>
      <p>
        Recorded amount: <span className="font-mono">{recorded(occurrence?.amount)}</span>
      </p>
      <p className="break-all text-slate-600 text-[11px]">
        Source issues: {occurrence?.issueLineIds.join(', ') || 'Unavailable'}
      </p>
      {occurrence?.statuses.length ? (
        <p className="text-[10px] text-slate-500">{occurrence.statuses.join(', ')}</p>
      ) : null}
    </div>
  );
};

/** Both production trees as occurrence and edge lists, with the selected occurrences highlighted. */
const ProductionTreesDetails: React.FC<{
  trees: [ProductionTree, ProductionTree];
  selectedIds: readonly [string, string] | null;
}> = ({ trees, selectedIds }) => (
  <details className="border border-slate-200 rounded-lg p-3 bg-white shadow-2xs">
    <summary className="cursor-pointer text-xs font-semibold text-slate-800">
      Production trees and linked occurrences
    </summary>
    <div className="grid sm:grid-cols-2 gap-3 mt-3">
      {trees.map((tree, side) => (
        <div key={tree.productionId} className="border rounded p-2.5 bg-slate-50/50">
          <h4 className="text-xs font-bold text-slate-800 mb-2">{tree.productionId}</h4>
          {tree.nodes.map((node) => (
            <p
              key={node.occurrenceId}
              className={`p-1 text-xs break-all rounded transition-colors ${selectedIds && node.occurrenceId === selectedIds[side] ? 'bg-period-actual-subtle outline outline-1 outline-period-actual text-period-actual-dark font-medium' : ''}`}
            >
              {node.index === tree.rootIndex ? 'Root' : 'Occurrence'} #{node.index} · {node.materialId} ·{' '}
              {node.occurrenceId}
            </p>
          ))}
          <ul className="text-xs mt-2 border-t pt-2 space-y-1 font-mono text-[11px] text-slate-600">
            {tree.edges.map((edge, i) => (
              <li key={i}>
                #{edge.materialIndex} {tree.nodes[edge.materialIndex]?.materialId} → #{edge.receivingIndex}{' '}
                {tree.nodes[edge.receivingIndex]?.materialId}
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  </details>
);
