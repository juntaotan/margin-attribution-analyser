import React, { useMemo } from 'react';
import {
  Background,
  Controls,
  Position,
  ReactFlow,
  MarkerType,
  type Edge,
  type Node,
  type Viewport,
} from '@xyflow/react';
import type { DisplayEdge, DisplayItem, DisplayResult, Period } from '../../graphDisplay';

/** Horizontal distance between display columns, in flow coordinates. */
export const COLUMN = 290;
const ROW = 104;
const HEADER = 56;
const NODE = { width: 230, height: 88 };
const FOLD = { width: 170, height: 44 };
const size = (item: DisplayItem) => (item.kind === 'node' || item.kind === 'missing' ? NODE : FOLD);

/** Fixed columns by display depth and rows from the worker's ranked tidy-tree order. */
const place = (item: DisplayItem) => ({
  x: item.column * COLUMN + (NODE.width - size(item).width) / 2,
  y: HEADER + item.row * ROW + (NODE.height - size(item).height) / 2,
});
const columnLabel = (result: DisplayResult, column: number) =>
  column === 0
    ? 'Cost of Goods Sold'
    : column === 1
      ? 'Products'
      : result.terminalColumn && column === result.columns - 1
        ? 'Material terminals'
        : `Level ${column}`;

/** Non-interactive column title nodes along the top of the pane. */
const columnHeaderNodes = (result: DisplayResult): Node[] =>
  Array.from({ length: result.columns }, (_, column) => ({
    id: `column:${column}`,
    className: 'column-header',
    position: { x: column * COLUMN, y: 0 },
    selectable: false,
    focusable: false,
    draggable: false,
    data: {
      label: (
        <div className="text-[11px] font-bold uppercase tracking-wide text-slate-500 text-left">
          {columnLabel(result, column)}
        </div>
      ),
    },
    style: {
      width: NODE.width,
      height: 32,
      border: 'none',
      borderBottom: '2px solid #cbd5e1',
      borderRadius: 0,
      background: 'transparent',
      padding: '6px 4px',
      pointerEvents: 'none' as const,
    },
  }));

/** Card content of a recorded node: its recorded values in this period, Δ and abnormal-boundary evidence. */
const NodeCard: React.FC<{ item: DisplayItem; period: Period }> = ({ item, period }) => {
  const value = item[period];
  return (
    <div className="text-left font-sans text-xs leading-tight">
      <div className="flex gap-1 items-center">
        <span className="font-semibold truncate" title={item.label}>
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
      {!value ? (
        <div className="text-slate-500 mt-1">Not recorded in this period</div>
      ) : (
        <>
          <div className="font-mono mt-1 truncate" title={value.cost ?? 'Unavailable'}>
            {value.cost ?? 'Cost unavailable'}
            {item.inventoryId !== '__COGS__' && <span className="text-slate-500"> · Qty {value.quantity}</span>}
          </div>
          {!value.linked && <div className="text-[10px] text-amber-700">Relationship to parent not recorded</div>}
        </>
      )}
      <div className="font-mono text-[10px] mt-1 text-slate-600 truncate" title="Actual minus comparable">
        Δ {item.delta ?? 'unavailable'}
        {item.parentDelta !== undefined && ` · in parent ${item.parentDelta ?? 'unavailable'}`}
      </div>
      {item.boundary && (
        <div className="text-[10px] text-anomaly-boundary font-medium truncate">
          Boundary difference {item.boundary.difference} · {item.boundary.count} paths
        </div>
      )}
    </div>
  );
};

/**
 * Builds the flow node for a display item. Folds are rounded and dashed; nodes not recorded in this period are
 * dashed and faded; abnormal items are orange; the selected item is outlined in the period colour.
 */
const displayNode = (item: DisplayItem, period: Period, selectedKey: string | null): Node => {
  const fold = item.kind !== 'node';
  const missing = item.kind === 'node' && !item[period];
  const borderColour =
    selectedKey === item.key ? (period === 'actual' ? '#1d4ed8' : '#475569') : item.abnormal ? '#c2410c' : '#94a3b8';
  return {
    id: item.key,
    position: place(item),
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    ariaLabel: fold ? `${item.label}: ${item.note}` : item.label,
    data: {
      label: fold ? (
        <div className="text-xs font-semibold truncate" title={item.note}>
          <span>{item.label}</span>
          <div className="text-[10px] font-normal text-slate-600 truncate">{item.note}</div>
        </div>
      ) : (
        <NodeCard item={item} period={period} />
      ),
    },
    style: {
      ...size(item),
      borderRadius: fold ? 22 : 6,
      padding: fold ? '4px 10px' : 8,
      cursor: fold && !item.expandKey ? 'default' : 'pointer',
      border: `1.5px ${fold || missing ? 'dashed' : 'solid'} ${borderColour}`,
      background: missing ? '#f8fafc' : item.abnormal ? '#fff7ed' : fold ? '#f1f5f9' : '#fff',
      opacity: missing ? 0.7 : 1,
    },
  };
};

/** Edges not recorded in this period are pale and dashed, as are edges into or out of folds. */
const displayEdge = (edge: DisplayEdge, period: Period): Edge => {
  const recorded = edge[period];
  return {
    id: edge.id,
    source: edge.source,
    target: edge.target,
    type: 'smoothstep',
    markerEnd: { type: MarkerType.ArrowClosed, color: '#94a3b8' },
    style: {
      stroke: recorded ? '#94a3b8' : '#e2e8f0',
      strokeDasharray: edge.folded || !recorded ? '5 5' : undefined,
    },
  };
};

interface GraphPaneProps {
  result: DisplayResult;
  label: string;
  period: Period;
  selectedKey: string | null;
  viewport: Viewport;
  onViewport: (viewport: Viewport) => void;
  onActivate: (item: DisplayItem, period: Period) => void;
}

/**
 * One period's view of the shared display layout. Both panes share the viewport, so they pan and zoom together.
 * Items are activated by click or by Enter or Space on a focused node.
 */
export const GraphPane: React.FC<GraphPaneProps> = ({
  result,
  label,
  period,
  selectedKey,
  viewport,
  onViewport,
  onActivate,
}) => {
  const lookup = useMemo(() => new Map(result.items.map((item) => [item.key, item])), [result]);
  const nodes: Node[] = useMemo(
    () => [...columnHeaderNodes(result), ...result.items.map((item) => displayNode(item, period, selectedKey))],
    [result, period, selectedKey],
  );
  const edges: Edge[] = useMemo(() => result.edges.map((edge) => displayEdge(edge, period)), [result, period]);
  const activate = (id: string) => {
    const item = lookup.get(id);
    if (item) onActivate(item, period);
  };
  return (
    <div className="min-w-0 min-h-[360px] flex-1 flex flex-col border-r last:border-r-0">
      <div
        className={`px-3 py-2 border-b text-xs font-bold flex items-center justify-between ${
          period === 'actual'
            ? 'bg-period-actual-subtle text-period-actual-dark border-period-actual-border/40'
            : 'bg-period-comparable-subtle text-period-comparable-dark border-period-comparable-border/40'
        }`}
      >
        <div className="flex items-center gap-1.5">
          <span
            className={`w-2 h-2 rounded-full ${period === 'actual' ? 'bg-period-actual' : 'bg-period-comparable'}`}
          />
          <span>{label}</span>
        </div>
        <span className="text-[10px] font-mono uppercase tracking-wider opacity-75">
          {period === 'actual' ? 'Actual Execution' : 'Comparable Baseline'}
        </span>
      </div>
      <div className="flex-1 min-h-0" aria-label={`${label} graph`}>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          viewport={viewport}
          onViewportChange={onViewport}
          minZoom={0.1}
          maxZoom={1.5}
          nodesDraggable={false}
          nodesConnectable={false}
          onNodeClick={(_, node) => activate(node.id)}
          onKeyDownCapture={(event) => {
            if (event.key !== 'Enter' && event.key !== ' ') return;
            const id = (event.target as HTMLElement).closest('[data-id]')?.getAttribute('data-id');
            if (id) {
              event.preventDefault();
              event.stopPropagation();
              activate(id);
            }
          }}
        >
          <Background gap={20} color="#cbd5e1" size={1} />
          <Controls showInteractive={false} fitViewOptions={{ padding: 0.08, minZoom: 0.1 }} />
        </ReactFlow>
      </div>
    </div>
  );
};
