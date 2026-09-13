import React, { useState, useEffect } from 'react';
import {
  Network,
  Database,
  Settings,
  Layers,
  FileText,
  DollarSign,
  ChevronRight,
  ChevronLeft,
  Play,
  Building2,
  Calendar,
  Package,
  ClipboardList,
} from 'lucide-react';
import { DataPreparation } from './DataPreparation';
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

export interface GraphNodeData {
  id: string;
  name: string;
  category: string;
  quantity: number;
  department: string;
  cost: number | null;
}

export interface GraphEdgeData {
  id: string;
  source: string;
  target: string;
  quantity: number;
  cost: number | null;
}

export interface AnalysisResponse {
  summary: {
    totalNodes: number;
    totalEdges: number;
    analyzedAt: string;
    message?: string;
  };
  nodes: GraphNodeData[];
  edges: GraphEdgeData[];
}

const getLayoutedElements = (
  rawNodes: FlowNode[],
  rawEdges: FlowEdge[],
  direction = 'LR'
) => {
  const dagreGraph = new dagre.graphlib.Graph();
  dagreGraph.setDefaultEdgeLabel(() => ({}));

  const nodeWidth = 180;
  const nodeHeight = 65;

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

  // Margin Topology Filters State
  const [selectedCompany, setSelectedCompany] = useState<string>('');
  const [periodFrom, setPeriodFrom] = useState<string>('');
  const [periodTo, setPeriodTo] = useState<string>('');
  const [productName, setProductName] = useState<string>('');
  const [productionOrder, setProductionOrder] = useState<string>('');
  const [showMoreFilters, setShowMoreFilters] = useState<boolean>(false);
  const [isAnalyzing, setIsAnalyzing] = useState<boolean>(false);
  const [lastAnalyzedAt, setLastAnalyzedAt] = useState<string | null>(null);
  const [nodes, setNodes, onNodesChange] = useNodesState<FlowNode>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<FlowEdge>([]);
  const [selectedNodeData, setSelectedNodeData] = useState<GraphNodeData | null>(null);
  const [analysisError, setAnalysisError] = useState<string | null>(null);

  const handleStartAnalysis = async () => {
    if (!periodFrom || !periodTo) {
      alert('Please select both "Period: From" and "Period: To".');
      return;
    }

    setIsAnalyzing(true);
    setAnalysisError(null);

    try {
      const response = await fetch('/api/v1/analysis/margin-topology', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          company: selectedCompany || undefined,
          startDate: periodFrom,
          endDate: periodTo,
        }),
      });

      if (!response.ok) {
        throw new Error(`Analysis failed with HTTP status ${response.status}`);
      }

      const data: AnalysisResponse = await response.json();

      if (!data.nodes || data.nodes.length === 0) {
        setNodes([]);
        setEdges([]);
        setSelectedNodeData(null);
        setAnalysisError(data.summary?.message || 'No graph data found for the selected period.');
      } else {
        const rawNodes: FlowNode[] = data.nodes.map((n) => ({
          id: n.id,
          data: {
            label: (
              <div className="text-left font-sans">
                <div className="font-semibold text-xs text-slate-800 truncate">{n.name}</div>
                <div className="text-[10px] text-slate-500 mt-0.5">
                  {n.category === 'FINISHED_GOOD' ? 'Finished Good' : 'Raw Material'}
                </div>
                <div className="text-[10px] text-blue-600 font-mono mt-0.5">
                  Qty: {n.quantity}
                </div>
              </div>
            ),
          },
          position: { x: 0, y: 0 },
          style: {
            background: n.category === 'FINISHED_GOOD' ? '#eff6ff' : '#f8fafc',
            border: n.category === 'FINISHED_GOOD' ? '2px solid #3b82f6' : '1px solid #94a3b8',
            borderRadius: '8px',
            padding: '8px',
            width: 170,
            boxShadow: '0 1px 3px 0 rgb(0 0 0 / 0.1)',
          },
          raw: n,
        } as FlowNode));

        const rawEdges: FlowEdge[] = (data.edges || []).map((e) => ({
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
        if (data.nodes.length > 0) {
          setSelectedNodeData(data.nodes[0]);
        }
      }
      setLastAnalyzedAt(new Date().toLocaleTimeString());
    } catch (err: any) {
      setAnalysisError(err.message || 'Error executing analysis');
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
                  {/* Company Name */}
                  <div className="flex items-center gap-1.5">
                    <span className="text-xs font-semibold text-slate-500 whitespace-nowrap">
                      Company:
                    </span>
                    <div className="relative">
                      <input
                        type="text"
                        value={selectedCompany}
                        onChange={(e) => setSelectedCompany(e.target.value)}
                        placeholder="Company name"
                        className="h-8 w-36 pl-7 pr-2.5 text-xs bg-slate-50 hover:bg-slate-100/70 border border-slate-200 rounded-lg text-slate-800 placeholder:text-slate-400 focus:outline-hidden focus:ring-2 focus:ring-blue-500/20 focus:border-blue-500 transition-all"
                      />
                      <Building2 className="w-3.5 h-3.5 text-slate-400 absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none" />
                    </div>
                  </div>

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

                  {/* More Filters: Product Name & Production Order */}
                  {showMoreFilters && (
                    <>
                      {/* Product Name */}
                      <div className="flex items-center gap-1.5">
                        <span className="text-xs font-semibold text-slate-500 whitespace-nowrap">
                          Product:
                        </span>
                        <div className="relative">
                          <input
                            type="text"
                            value={productName}
                            onChange={(e) => setProductName(e.target.value)}
                            placeholder="Product name"
                            className="h-8 w-36 pl-7 pr-2 text-xs bg-slate-50 hover:bg-slate-100/70 border border-slate-200 rounded-lg text-slate-800 placeholder:text-slate-400 focus:outline-hidden focus:ring-2 focus:ring-blue-500/20 focus:border-blue-500 transition-all"
                          />
                          <Package className="w-3.5 h-3.5 text-slate-400 absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none" />
                        </div>
                      </div>

                      {/* Production Order */}
                      <div className="flex items-center gap-1.5">
                        <span className="text-xs font-semibold text-slate-500 whitespace-nowrap">
                          Production Order:
                        </span>
                        <div className="relative">
                          <input
                            type="text"
                            value={productionOrder}
                            onChange={(e) => setProductionOrder(e.target.value)}
                            placeholder="Production order no."
                            className="h-8 w-36 pl-7 pr-2 text-xs bg-slate-50 hover:bg-slate-100/70 border border-slate-200 rounded-lg text-slate-800 placeholder:text-slate-400 focus:outline-hidden focus:ring-2 focus:ring-blue-500/20 focus:border-blue-500 transition-all"
                          />
                          <ClipboardList className="w-3.5 h-3.5 text-slate-400 absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none" />
                        </div>
                      </div>
                    </>
                  )}

                  {/* Toggle Arrow (ChevronRight when collapsed, ChevronLeft when expanded) */}
                  <button
                    type="button"
                    onClick={() => setShowMoreFilters((prev) => !prev)}
                    title={showMoreFilters ? 'Collapse additional filters' : 'Expand additional filters (Product, Production Order)'}
                    className="h-8 w-8 flex items-center justify-center rounded-lg border border-slate-200 bg-slate-50 hover:bg-slate-100 text-slate-600 hover:text-slate-900 transition-all cursor-pointer shrink-0"
                  >
                    {showMoreFilters ? (
                      <ChevronLeft className="w-4 h-4 text-blue-600" />
                    ) : (
                      <ChevronRight className="w-4 h-4 text-slate-600" />
                    )}
                  </button>

                  {/* Start Analysis Button (right of the arrow) */}
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
                      onNodeClick={(_, node) => {
                        if ((node as any).raw) {
                          setSelectedNodeData((node as any).raw);
                        }
                      }}
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
                              selectedCompany ? `Company: ${selectedCompany}` : null,
                              periodFrom || periodTo ? `Period: ${periodFrom || '—'} to ${periodTo || '—'}` : null,
                              productName ? `Product: ${productName}` : null,
                              productionOrder ? `Order: ${productionOrder}` : null,
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
                    {selectedNodeData.category}
                  </span>
                )}
              </div>

              <div className="p-4 space-y-4">
                {/* Section 1: Basic Information */}
                <section className="bg-slate-50/70 border border-slate-200 rounded-lg p-3.5 space-y-2">
                  <div className="flex items-center gap-2 text-xs font-semibold text-slate-800 pb-1.5 border-b border-slate-200/60">
                    <FileText className="w-4 h-4 text-blue-600" />
                    <span>Basic Information</span>
                  </div>
                  <div className="space-y-1.5 text-xs">
                    <div className="flex justify-between py-1 border-b border-slate-100">
                      <span className="text-slate-400">Item / Part Name</span>
                      <span className="font-medium text-slate-700">{selectedNodeData?.name || '[Pending Item]'}</span>
                    </div>
                    <div className="flex justify-between py-1 border-b border-slate-100">
                      <span className="text-slate-400">Part Code / Drawing</span>
                      <span className="font-mono text-slate-700">{selectedNodeData?.id || '[PART-CODE]'}</span>
                    </div>
                    <div className="flex justify-between py-1 border-b border-slate-100">
                      <span className="text-slate-400">Level / Category</span>
                      <span className="text-slate-700">{selectedNodeData?.category || '[Level / Category]'}</span>
                    </div>
                    <div className="flex justify-between py-1">
                      <span className="text-slate-400">Work Center / Owner</span>
                      <span className="text-slate-700">{selectedNodeData?.department || '[Work Center]'}</span>
                    </div>
                  </div>
                </section>

                {/* Section 2: Cost Breakdown (M / L / O) */}
                <section className="bg-slate-50/70 border border-slate-200 rounded-lg p-3.5 space-y-2.5">
                  <div className="flex items-center gap-2 text-xs font-semibold text-slate-800 pb-1.5 border-b border-slate-200/60">
                    <DollarSign className="w-4 h-4 text-emerald-600" />
                    <span>Cost Breakdown</span>
                  </div>
                  <div className="space-y-2 text-xs font-mono">
                    <div className="flex items-center justify-between p-2 rounded bg-white border border-slate-200/80">
                      <span className="text-slate-600 flex items-center gap-1.5">
                        <span className="w-2 h-2 rounded-full bg-sky-500" />
                        Material (M)
                      </span>
                      <span className="font-semibold text-slate-800">
                        {selectedNodeData?.cost != null ? `$${selectedNodeData.cost.toFixed(2)}` : '—'}
                      </span>
                    </div>
                    <div className="flex items-center justify-between p-2 rounded bg-white border border-slate-200/80">
                      <span className="text-slate-600 flex items-center gap-1.5">
                        <span className="w-2 h-2 rounded-full bg-amber-500" />
                        Labor (L)
                      </span>
                      <span className="font-semibold text-slate-800">$0.00</span>
                    </div>
                    <div className="flex items-center justify-between p-2 rounded bg-white border border-slate-200/80">
                      <span className="text-slate-600 flex items-center gap-1.5">
                        <span className="w-2 h-2 rounded-full bg-violet-500" />
                        Overhead (O)
                      </span>
                      <span className="font-semibold text-slate-800">$0.00</span>
                    </div>
                    <div className="pt-1.5 border-t border-slate-200 flex justify-between text-xs font-bold text-slate-900">
                      <span>Total Cost</span>
                      <span>
                        {selectedNodeData?.cost != null ? `$${selectedNodeData.cost.toFixed(2)} NZD` : '—'}
                      </span>
                    </div>
                  </div>
                </section>

                {/* Section 3: Direct Dependencies & Operations */}
                <section className="bg-slate-50/70 border border-slate-200 rounded-lg p-3.5 space-y-2">
                  <div className="flex items-center gap-2 text-xs font-semibold text-slate-800 pb-1.5 border-b border-slate-200/60">
                    <Layers className="w-4 h-4 text-indigo-600" />
                    <span>Direct Dependencies & Operations</span>
                  </div>
                  <div className="border border-dashed border-slate-200 rounded p-3 text-xs bg-white">
                    {selectedNodeData ? (
                      <div className="space-y-1.5 text-slate-600">
                        <div className="flex justify-between">
                          <span className="text-slate-400">Total Quantity:</span>
                          <span className="font-mono font-medium">{selectedNodeData.quantity}</span>
                        </div>
                        <div className="flex justify-between">
                          <span className="text-slate-400">Node Identifier:</span>
                          <span className="font-mono text-[11px] text-blue-600">{selectedNodeData.id}</span>
                        </div>
                      </div>
                    ) : (
                      <div className="text-center text-slate-400 py-2">
                        [Click any node on canvas to view details]
                      </div>
                    )}
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
