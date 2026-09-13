import React, { useState, useEffect } from 'react';
import {
  Network,
  Database,
  Settings,
  FileText,
  DollarSign,
  Play,
  Calendar,
  Package,
} from 'lucide-react';
import { DataPreparation } from './DataPreparation';
import {
  buildAnalysisGraph,
  formatAmount,
  formatQuantity,
  type AnalysisNode,
  type AnalysisResponse,
} from './analysisGraph';
import dagre from 'dagre';
import {
  ReactFlow,
  Background,
  Controls,
  MarkerType,
  useNodesState,
  useEdgesState,
  type Node as FlowNode,
  type Edge as FlowEdge,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';

type AnalysisFlowNode = FlowNode<{ label: React.ReactNode; raw: AnalysisNode }>;

const getLayoutedElements = (
  rawNodes: AnalysisFlowNode[],
  rawEdges: FlowEdge[],
  direction = 'LR'
) => {
  const dagreGraph = new dagre.graphlib.Graph();
  dagreGraph.setDefaultEdgeLabel(() => ({}));

  const nodeWidth = 220;
  const nodeHeight = 94;

  dagreGraph.setGraph({ rankdir: direction, ranksep: 70, nodesep: 35 });

  rawNodes.forEach((node) => {
    dagreGraph.setNode(node.id, { width: nodeWidth, height: nodeHeight });
  });

  rawEdges.forEach((edge) => {
    dagreGraph.setEdge(edge.source, edge.target);
  });

  dagre.layout(dagreGraph);

  const layoutedNodes = rawNodes.map((node) => {
    const nodeWithPosition = dagreGraph.node(node.id);
    return {
      ...node,
      targetPosition: (direction === 'LR' ? 'left' : 'top') as any,
      sourcePosition: (direction === 'LR' ? 'right' : 'bottom') as any,
      position: {
        x: nodeWithPosition.x - nodeWidth / 2,
        y: nodeWithPosition.y - nodeHeight / 2,
      },
    };
  });

  return { nodes: layoutedNodes, edges: rawEdges };
};

const getInitialRoute = (): string => {
  const path = window.location.pathname;
  if (path === '/data-prep') {
    return '/data-prep';
  }
  return '/margin-topology';
};

export const App: React.FC = () => {
  const [currentRoute, setCurrentRoute] = useState<string>(getInitialRoute);

  // The trace accepts a date range and optional comma-separated inventory IDs.
  const [periodFrom, setPeriodFrom] = useState<string>('');
  const [periodTo, setPeriodTo] = useState<string>('');
  const [targetInput, setTargetInput] = useState<string>('');
  const [isAnalyzing, setIsAnalyzing] = useState<boolean>(false);
  const [lastAnalyzedAt, setLastAnalyzedAt] = useState<string | null>(null);
  const [nodes, setNodes, onNodesChange] = useNodesState<AnalysisFlowNode>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<FlowEdge>([]);
  const [selectedNodeData, setSelectedNodeData] = useState<AnalysisNode | null>(null);
  const [analysisError, setAnalysisError] = useState<string | null>(null);

  /** Requests traced adjacency entries, then lays out their recorded Nodes. */
  const handleStartAnalysis = async () => {
    if (!periodFrom || !periodTo) {
      setNodes([]);
      setEdges([]);
      setSelectedNodeData(null);
      setLastAnalyzedAt(null);
      setAnalysisError('Please select both period dates.');
      return;
    }
    if (periodFrom > periodTo) {
      setNodes([]);
      setEdges([]);
      setSelectedNodeData(null);
      setLastAnalyzedAt(null);
      setAnalysisError('Period start must not be after period end.');
      return;
    }

    setIsAnalyzing(true);
    setAnalysisError(null);
    setLastAnalyzedAt(null);

    try {
      const targets = targetInput.split(',').map((target) => target.trim()).filter(Boolean);
      const response = await fetch('/api/v1/analysis/trace', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          startDate: periodFrom,
          endDate: periodTo,
          targets,
        }),
      });

      if (!response.ok) {
        const errorBody = await response.json().catch(() => null) as { message?: string } | null;
        throw new Error(errorBody?.message || `Analysis failed with HTTP status ${response.status}`);
      }

      const data: AnalysisResponse = await response.json();
      if (!Array.isArray(data.results)) {
        throw new Error('The analysis response did not contain adjacency entries.');
      }
      const graph = buildAnalysisGraph(data.results);

      if (graph.nodes.length === 0) {
        setNodes([]);
        setEdges([]);
        setSelectedNodeData(null);
        setAnalysisError('No products found for the selected period.');
      } else {
        const rawNodes: AnalysisFlowNode[] = graph.nodes.map(({ id, node }) => ({
          id,
          data: {
            label: (
              <div className="text-left font-sans">
                <div className="font-semibold text-xs text-slate-800 truncate" title={node.inventoryId}>
                  {node.inventoryId}
                </div>
                <div className="text-[11px] text-blue-700 font-mono mt-1">
                  Qty: {formatQuantity(node.quantity)}
                </div>
                <div className="text-[11px] text-emerald-700 font-mono mt-0.5">
                  Amount: {formatAmount(node.cost)}
                </div>
              </div>
            ),
            raw: node,
          },
          position: { x: 0, y: 0 },
          style: {
            background: '#ffffff',
            border: '1px solid #94a3b8',
            borderRadius: '8px',
            padding: '10px',
            width: 210,
            boxShadow: '0 1px 3px 0 rgb(0 0 0 / 0.1)',
          },
        }));

        const rawEdges: FlowEdge[] = graph.edges.map((e) => ({
          id: e.id,
          source: e.source,
          target: e.target,
          animated: true,
          style: { stroke: '#64748b', strokeWidth: 1.5 },
          markerEnd: { type: MarkerType.ArrowClosed, color: '#64748b' },
        }));

        const layouted = getLayoutedElements(rawNodes, rawEdges, 'LR');
        setNodes(layouted.nodes);
        setEdges(layouted.edges);
        setSelectedNodeData(graph.nodes[0].node);
      }
      setLastAnalyzedAt(new Date().toLocaleTimeString());
    } catch (err) {
      setNodes([]);
      setEdges([]);
      setSelectedNodeData(null);
      setAnalysisError(err instanceof Error ? err.message : 'Error executing analysis');
    } finally {
      setIsAnalyzing(false);
    }
  };

  useEffect(() => {
    // Default route into /margin-topology if accessed via root or legacy /bom-topology
    if (window.location.pathname === '/' || window.location.pathname === '' || window.location.pathname === '/bom-topology') {
      window.history.replaceState({}, '', '/margin-topology');
    }

    const handlePopState = () => {
      setCurrentRoute(window.location.pathname === '/data-prep' ? '/data-prep' : '/margin-topology');
    };

    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, []);

  const navigate = (path: string) => {
    window.history.pushState({}, '', path);
    setCurrentRoute(path);
  };

  return (
    <div className="flex flex-col h-screen w-screen bg-slate-50 text-slate-800 font-sans select-none overflow-hidden">
      {/* 1. Top Bar (Reserved) */}
      <header className="h-12 bg-white border-b border-slate-200 px-6 flex items-center justify-between shrink-0">
        <div className="flex items-center gap-2 text-xs text-slate-400">
          <span className="font-semibold text-slate-700">MarginTrace</span>
          <span>/</span>
          <span className="text-slate-600 font-medium">
            {currentRoute === '/data-prep' ? 'Data Preparation' : 'Margin Topology Analysis'}
          </span>
          <span>/</span>
          <span>Top Bar (Reserved)</span>
        </div>
        <div className="text-xs text-slate-300 font-mono">
          [Top Bar Placeholder]
        </div>
      </header>

      {/* Main Layout (Sidebar + Content Area) */}
      <div className="flex-1 flex min-h-0 overflow-hidden">
        {/* 2. Left Sidebar Navigation */}
        <aside className="w-56 bg-white border-r border-slate-200 flex flex-col justify-between shrink-0">
          <div className="p-3">
            <div className="px-3 py-2 text-xs font-semibold text-slate-400 uppercase tracking-wider">
              Navigation
            </div>
            <nav className="space-y-1">
              {/* Margin Topology Analysis -> /margin-topology */}
              <button
                onClick={() => navigate('/margin-topology')}
                className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-md text-xs font-medium transition-colors ${
                  currentRoute === '/margin-topology'
                    ? 'bg-blue-50 text-blue-700 border border-blue-200/80 font-semibold'
                    : 'text-slate-600 hover:text-slate-900 hover:bg-slate-50'
                }`}
              >
                <Network className="w-4 h-4 text-blue-600 shrink-0" />
                <span>Margin Topology Analysis</span>
              </button>

              {/* Data Preparation -> /data-prep */}
              <button
                onClick={() => navigate('/data-prep')}
                className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-md text-xs font-medium transition-colors ${
                  currentRoute === '/data-prep'
                    ? 'bg-blue-50 text-blue-700 border border-blue-200/80 font-semibold'
                    : 'text-slate-600 hover:text-slate-900 hover:bg-slate-50'
                }`}
              >
                <Database className="w-4 h-4 text-slate-500 shrink-0" />
                <span>Data Preparation</span>
              </button>

              {/* Settings & Parameters */}
              <button
                onClick={() => navigate('/settings')}
                className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-md text-xs font-medium transition-colors ${
                  currentRoute === '/settings'
                    ? 'bg-blue-50 text-blue-700 border border-blue-200/80 font-semibold'
                    : 'text-slate-600 hover:text-slate-900 hover:bg-slate-50'
                }`}
              >
                <Settings className="w-4 h-4 text-slate-500 shrink-0" />
                <span>Settings & Parameters</span>
              </button>
            </nav>
          </div>

          <div className="p-3 border-t border-slate-100 text-[11px] text-slate-400 text-center font-mono">
            HMLV Decision Platform
          </div>
        </aside>

        {/* Dynamic Route Content */}
        {currentRoute === '/data-prep' ? (
          /* ========================================================================= */
          /* PAGE: Data Preparation                                                    */
          /* ========================================================================= */
          <DataPreparation />
        ) : (
          /* ========================================================================= */
          /* PAGE: Margin Topology Canvas (Default: Canvas Center + Detail Panel Right) */
          /* ========================================================================= */
          <div className="flex-1 flex min-w-0 h-full overflow-hidden">
            {/* 3. Center: Main Margin Topology Canvas Workspace */}
            <main className="flex-1 relative bg-slate-50 flex flex-col overflow-hidden">
              {/* Filter Toolbar Above Canvas */}
              <div className="bg-white border-b border-slate-200 px-4 py-2.5 flex items-center justify-between gap-3 shrink-0 z-20">
                <div className="flex items-center gap-2.5 flex-wrap">
                  {/* Period: From and To date pickers */}
                  <div className="flex items-center gap-1.5">
                    <span className="text-xs font-semibold text-slate-500 whitespace-nowrap">
                      Period:
                    </span>
                    <div className="flex items-center gap-1">
                      <div className="relative">
                        <input
                          type="date"
                          value={periodFrom}
                          onChange={(e) => setPeriodFrom(e.target.value)}
                          aria-label="Period from date"
                          className="h-8 pl-7 pr-1 text-xs bg-slate-50 hover:bg-slate-100/70 border border-slate-200 rounded-lg text-slate-800 focus:outline-hidden focus:ring-2 focus:ring-blue-500/20 focus:border-blue-500 transition-all font-mono"
                        />
                        <Calendar className="w-3.5 h-3.5 text-slate-400 absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none" />
                      </div>
                      <span className="text-xs text-slate-400 font-medium">to</span>
                      <div className="relative">
                        <input
                          type="date"
                          value={periodTo}
                          onChange={(e) => setPeriodTo(e.target.value)}
                          aria-label="Period to date"
                          className="h-8 pl-7 pr-1 text-xs bg-slate-50 hover:bg-slate-100/70 border border-slate-200 rounded-lg text-slate-800 focus:outline-hidden focus:ring-2 focus:ring-blue-500/20 focus:border-blue-500 transition-all font-mono"
                        />
                        <Calendar className="w-3.5 h-3.5 text-slate-400 absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none" />
                      </div>
                    </div>
                  </div>

                  {/* Inventory IDs are optional; an empty value traces all period products. */}
                  <div className="flex items-center gap-1.5">
                    <span className="text-xs font-semibold text-slate-500 whitespace-nowrap">
                      Products:
                    </span>
                    <div className="relative">
                      <input
                        type="text"
                        value={targetInput}
                        onChange={(e) => setTargetInput(e.target.value)}
                        aria-label="Product inventory IDs"
                        placeholder="IDs separated by commas (optional)"
                        className="h-8 w-64 pl-7 pr-2 text-xs bg-slate-50 hover:bg-slate-100/70 border border-slate-200 rounded-lg text-slate-800 placeholder:text-slate-400 focus:outline-hidden focus:ring-2 focus:ring-blue-500/20 focus:border-blue-500 transition-all"
                      />
                      <Package className="w-3.5 h-3.5 text-slate-400 absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none" />
                    </div>
                  </div>

                  {/* Start the date-scoped trace. */}
                  <button
                    type="button"
                    onClick={handleStartAnalysis}
                    disabled={isAnalyzing}
                    className="h-8 px-3.5 flex items-center gap-1.5 bg-blue-600 hover:bg-blue-700 active:scale-98 text-white text-xs font-semibold rounded-lg shadow-xs transition-all cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed shrink-0"
                  >
                    <Play className="w-3.5 h-3.5 fill-current" />
                    <span>{isAnalyzing ? 'Analyzing…' : 'Start Analysis'}</span>
                  </button>
                </div>

                {lastAnalyzedAt && (
                  <span className="text-[11px] font-mono text-emerald-600 bg-emerald-50 border border-emerald-200 px-2.5 py-1 rounded-md shrink-0 hidden lg:inline-block">
                    Analyzed at: {lastAnalyzedAt}
                  </span>
                )}
              </div>

              {/* Canvas Container */}
              <div className="flex-1 relative flex flex-col overflow-hidden">
                {nodes.length > 0 ? (
                  <div className="w-full h-full relative">
                    <ReactFlow
                      nodes={nodes}
                      edges={edges}
                      onNodesChange={onNodesChange}
                      onEdgesChange={onEdgesChange}
                      onNodeClick={(_, node) => setSelectedNodeData(node.data.raw)}
                      fitView
                    >
                      <Background color="#e2e8f0" gap={16} />
                      <Controls />
                    </ReactFlow>
                  </div>
                ) : (
                  <div className="flex-1 w-full h-full flex items-center justify-center p-8">
                    <div className="w-full h-full border-2 border-dashed border-slate-200 rounded-xl bg-white/70 flex flex-col items-center justify-center text-slate-400 gap-3">
                      <Network className="w-12 h-12 text-slate-300 stroke-1" />
                      <div className="text-center">
                        <p className="text-sm font-semibold text-slate-600">Margin Topology Main View</p>
                        <p className="text-xs text-slate-400 mt-1">
                          Route: <code className="bg-slate-100 px-1.5 py-0.5 rounded text-blue-600 font-mono">/margin-topology</code> (Default Landing Page)
                        </p>
                        {analysisError ? (
                          <p className="text-xs text-rose-600 font-mono mt-2 bg-rose-50 px-3 py-1.5 rounded border border-rose-200 inline-block">
                            Notice: {analysisError}
                          </p>
                        ) : lastAnalyzedAt ? (
                          <p className="text-xs text-emerald-600 font-mono mt-2 bg-emerald-50 px-3 py-1 rounded border border-emerald-100 inline-block">
                            Active Filters: {[
                              periodFrom || periodTo ? `Period: ${periodFrom || '—'} to ${periodTo || '—'}` : null,
                              targetInput.trim() ? `Products: ${targetInput}` : 'All period products',
                            ].filter(Boolean).join(' | ') || 'All Records (No Filters Applied)'}
                          </p>
                        ) : (
                          <p className="text-xs text-slate-400 mt-2">
                            Configure filters and click &quot;Start Analysis&quot; to build margin topology.
                          </p>
                        )}
                      </div>
                    </div>
                  </div>
                )}
              </div>
            </main>

            {/* 4. Right: Detail Panel */}
            <aside className="w-80 bg-white border-l border-slate-200 flex flex-col shrink-0 overflow-y-auto">
              <div className="h-11 px-4 border-b border-slate-200 flex items-center justify-between">
                <h3 className="text-xs font-bold text-slate-800 uppercase tracking-wider">
                  Details
                </h3>
                {selectedNodeData && (
                  <span className="text-[10px] bg-blue-50 text-blue-700 px-2 py-0.5 rounded-full border border-blue-200 font-medium">
                    Selected node
                  </span>
                )}
              </div>

              <div className="p-4 space-y-4">
                {/* The analyser returns the recorded Node fields only. */}
                <section className="bg-slate-50/70 border border-slate-200 rounded-lg p-3.5 space-y-2">
                  <div className="flex items-center gap-2 text-xs font-semibold text-slate-800 pb-1.5 border-b border-slate-200/60">
                    <FileText className="w-4 h-4 text-blue-600" />
                    <span>Basic Information</span>
                  </div>
                  <div className="space-y-1.5 text-xs">
                    <div className="flex justify-between py-1 border-b border-slate-100">
                      <span className="text-slate-400">Inventory ID</span>
                      <span className="font-medium text-slate-700 break-all text-right pl-2">
                        {selectedNodeData?.inventoryId || '—'}
                      </span>
                    </div>
                    <div className="flex justify-between py-1">
                      <span className="text-slate-400">Quantity</span>
                      <span className="font-mono text-slate-700">
                        {selectedNodeData ? formatQuantity(selectedNodeData.quantity) : '—'}
                      </span>
                    </div>
                  </div>
                </section>

                {/* A null cost is unavailable; it is never displayed as zero. */}
                <section className="bg-slate-50/70 border border-slate-200 rounded-lg p-3.5 space-y-2.5">
                  <div className="flex items-center gap-2 text-xs font-semibold text-slate-800 pb-1.5 border-b border-slate-200/60">
                    <DollarSign className="w-4 h-4 text-emerald-600" />
                    <span>Recorded Amount</span>
                  </div>
                  <div className="space-y-2 text-xs font-mono">
                    <div className="flex items-center justify-between p-2 rounded bg-white border border-slate-200/80">
                      <span className="text-slate-600">Cost</span>
                      <span className="font-semibold text-slate-800 text-right">
                        {selectedNodeData ? formatAmount(selectedNodeData.cost) : '—'}
                      </span>
                    </div>
                  </div>
                </section>
              </div>
            </aside>
          </div>
        )}
      </div>
    </div>
  );
};

export default App;
