import React, { useMemo } from 'react';
import dagre from 'dagre';
import {
  Background, Controls, Edge, Node, Position, ReactFlow,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { StreamCsrGraph, StreamNode, formatAmount, formatQuantity } from '../../analysisGraph';

interface DualBomTreeCanvasProps {
  actualGraph: StreamCsrGraph;
  comparableGraph: StreamCsrGraph;
  actualLabel: string;
  comparableLabel: string;
  highlightedNodeIds: ReadonlySet<string>;
  highlightedEdgeIds: ReadonlySet<string>;
  selectedNodeId: string | null;
  onSelectNode: (node: StreamNode) => void;
}

const NODE_WIDTH = 230;
const NODE_HEIGHT = 88;
const EMPTY_IDS: ReadonlySet<string> = new Set();

function layoutGraph(
  graph: StreamCsrGraph,
  highlightedNodeIds: ReadonlySet<string>,
  highlightedEdgeIds: ReadonlySet<string>,
  selectedNodeId: string | null,
): { nodes: Node[]; edges: Edge[] } {
  const layout = new dagre.graphlib.Graph();
  layout.setGraph({ rankdir: 'BT', ranksep: 75, nodesep: 35, marginx: 30, marginy: 30 });
  layout.setDefaultEdgeLabel(() => ({}));

  graph.nodes.forEach((node) => layout.setNode(node.id, {
    width: NODE_WIDTH, height: NODE_HEIGHT,
  }));

  const edges: Edge[] = [];
  for (let from = 0; from < graph.nodes.length; from++) {
    for (let index = graph.offset[from]; index < graph.offset[from + 1]; index++) {
      const edgeId = graph.edgeIds[index];
      const to = graph.successors[index];
      if (to == null || !graph.nodes[to] || !edgeId) continue;
      layout.setEdge(graph.nodes[from].id, graph.nodes[to].id);
      const highlighted = highlightedEdgeIds.has(edgeId);
      edges.push({
        id: edgeId,
        source: graph.nodes[from].id,
        target: graph.nodes[to].id,
        type: 'smoothstep',
        style: { stroke: highlighted ? '#e11d48' : '#94a3b8', strokeWidth: highlighted ? 3 : 1.5 },
        zIndex: highlighted ? 10 : 0,
      });
    }
  }
  dagre.layout(layout);

  const nodes: Node[] = graph.nodes.map((node) => {
    const point = layout.node(node.id);
    const highlighted = highlightedNodeIds.has(node.id);
    return {
      id: node.id,
      position: { x: point.x - NODE_WIDTH / 2, y: point.y - NODE_HEIGHT / 2 },
      sourcePosition: Position.Top,
      targetPosition: Position.Bottom,
      data: {
        label: (
          <div className="text-left font-sans" data-testid={`csr-node-${node.position}`}>
            <div className="font-mono text-[11px] font-bold truncate" title={node.inventoryId}>
              {node.inventoryId}
            </div>
            <div className="mt-1 text-[10px] text-slate-500">Qty {formatQuantity(node.quantity)}</div>
            <div className={`mt-1 text-xs font-semibold ${highlighted ? 'text-rose-700' : 'text-slate-800'}`}>
              {formatAmount(node.cost)}
            </div>
          </div>
        ),
      },
      style: {
        width: NODE_WIDTH,
        height: NODE_HEIGHT,
        border: `2px solid ${highlighted ? '#e11d48' : selectedNodeId === node.id ? '#2563eb' : '#cbd5e1'}`,
        background: highlighted ? '#fff1f2' : '#fff',
        borderRadius: 8,
        boxShadow: highlighted ? '0 0 0 3px #ffe4e6' : undefined,
      },
    };
  });
  return { nodes, edges };
}

interface GraphPaneProps {
  graph: StreamCsrGraph;
  label: string;
  highlightedNodeIds: ReadonlySet<string>;
  highlightedEdgeIds: ReadonlySet<string>;
  selectedNodeId: string | null;
  onSelectNode: (node: StreamNode) => void;
}

const GraphPane: React.FC<GraphPaneProps> = ({
  graph, label, highlightedNodeIds, highlightedEdgeIds, selectedNodeId, onSelectNode,
}) => {
  const { nodes, edges } = useMemo(
    () => layoutGraph(graph, highlightedNodeIds, highlightedEdgeIds, selectedNodeId),
    [graph, highlightedNodeIds, highlightedEdgeIds, selectedNodeId],
  );
  const nodesById = useMemo(() => new Map(graph.nodes.map((node) => [node.id, node])), [graph]);

  return (
    <div className="min-w-0 min-h-[360px] flex-1 flex flex-col border-r border-slate-200 last:border-r-0">
      <div className="shrink-0 px-3 py-2 bg-white border-b border-slate-200 text-xs font-bold text-slate-700">
        {label} <span className="ml-2 font-normal text-slate-500">{graph.nodes.length} nodes</span>
      </div>
      <div className="flex-1 min-h-0" aria-label={`${label} CSR graph`}>
        {nodes.length === 0 ? (
          <div className="p-4 text-xs text-slate-500">No nodes in this period.</div>
        ) : (
          <ReactFlow
            nodes={nodes}
            edges={edges}
            fitView
            minZoom={0.1}
            nodesDraggable={false}
            nodesConnectable={false}
            elementsSelectable
            onNodeClick={(_, item) => {
              const node = nodesById.get(item.id);
              if (node) onSelectNode(node);
            }}
          >
            <Background gap={18} color="#e2e8f0" />
            <Controls showInteractive={false} />
          </ReactFlow>
        )}
      </div>
    </div>
  );
};

export const DualBomTreeCanvas: React.FC<DualBomTreeCanvasProps> = ({
  actualGraph, comparableGraph, actualLabel, comparableLabel,
  highlightedNodeIds, highlightedEdgeIds, selectedNodeId, onSelectNode,
}) => (
  <section className="w-full h-full min-h-[400px] flex bg-slate-50">
    <GraphPane graph={comparableGraph} label={comparableLabel}
      highlightedNodeIds={EMPTY_IDS} highlightedEdgeIds={EMPTY_IDS}
      selectedNodeId={selectedNodeId} onSelectNode={onSelectNode} />
    <GraphPane graph={actualGraph} label={actualLabel}
      highlightedNodeIds={highlightedNodeIds} highlightedEdgeIds={highlightedEdgeIds}
      selectedNodeId={selectedNodeId} onSelectNode={onSelectNode} />
  </section>
);
