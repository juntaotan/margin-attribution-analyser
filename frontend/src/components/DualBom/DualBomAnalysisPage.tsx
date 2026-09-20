import React, { useState, useEffect, useRef } from 'react';
import { AnalysisHeader } from './AnalysisHeader';
import { ScopeControlsBar } from './ScopeControlsBar';
import { KpiSummaryBar } from './KpiSummaryBar';
import { DualBomTreeCanvas } from './DualBomTreeCanvas';
import { MaterialLedgerWorkbench } from './MaterialLedgerWorkbench';
import { AuditSidebar } from './AuditSidebar';
import { ReconciliationPathsPanel } from './ReconciliationPathsPanel';
import {
  DualBomNode,
  DualBomReconciliationResult,
  reconcileDualBom,
} from '../../varianceEngine';
import {
  ReconciliationPath, StreamCsrGraph, StreamNode, csrToAdjacency, readAnalysisStream,
} from '../../analysisGraph';

export const DualBomAnalysisPage: React.FC = () => {
  const [periodFrom, setPeriodFrom] = useState<string>('2026-01-01');
  const [periodTo, setPeriodTo] = useState<string>('2026-01-31');
  const [threshold, setThreshold] = useState<number>(250.0);
  const [comparablePeriodFrom, setComparablePeriodFrom] = useState<string>('2025-01-01');
  const [comparablePeriodTo, setComparablePeriodTo] = useState<string>('2025-01-31');
  const [leafThreshold, setLeafThreshold] = useState<number>(100);
  const [reconciliationPaths, setReconciliationPaths] = useState<ReconciliationPath[] | null>(null);
  const [pathError, setPathError] = useState<string | null>(null);
  const [graphSnapshot, setGraphSnapshot] = useState<{
    analysisId: string; actual: StreamCsrGraph; comparable: StreamCsrGraph;
    actualLabel: string; comparableLabel: string;
    actualStartDate: string; actualEndDate: string;
    comparableStartDate: string; comparableEndDate: string;
  } | null>(null);
  const [highlightedNodeIds, setHighlightedNodeIds] = useState<ReadonlySet<string>>(new Set());
  const [highlightedEdgeIds, setHighlightedEdgeIds] = useState<ReadonlySet<string>>(new Set());
  const [selectedGraphNodeId, setSelectedGraphNodeId] = useState<string | null>(null);
  const requestVersion = useRef(0);
  const abortRef = useRef<AbortController | null>(null);

  const [isAnalyzing, setIsAnalyzing] = useState<boolean>(false);
  const [analysisError, setAnalysisError] = useState<string | null>(null);
  const [result, setResult] = useState<DualBomReconciliationResult | null>(null);
  const [selectedNode, setSelectedNode] = useState<DualBomNode | null>(null);

  // Resizable vertical split between Tree (default 70%) and Material Ledger (default 30%)
  const [treeHeightPct, setTreeHeightPct] = useState<number>(70);
  const [isDraggingSplitter, setIsDraggingSplitter] = useState<boolean>(false);
  const leftAreaRef = useRef<HTMLDivElement>(null);

  const handleSplitterMouseDown = (e: React.MouseEvent) => {
    e.preventDefault();
    setIsDraggingSplitter(true);

    const handleMouseMove = (moveEvent: MouseEvent) => {
      if (!leftAreaRef.current) return;
      const rect = leftAreaRef.current.getBoundingClientRect();
      const relativeY = moveEvent.clientY - rect.top;
      const newPct = (relativeY / rect.height) * 100;
      // Allow dragging up to expand the ledger (down to 15% tree) or down (up to 85% tree)
      const clamped = Math.max(15, Math.min(85, newPct));
      setTreeHeightPct(clamped);
    };

    const handleMouseUp = () => {
      setIsDraggingSplitter(false);
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
    };

    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);
  };

  // Focus and pulse animation micro-interaction
  const handleInspectNode = (node: DualBomNode) => {
    setSelectedNode(node);
    const graphNode = graphSnapshot?.actual.nodes.find((item) => item.inventoryId === node.id);
    setSelectedGraphNodeId(graphNode?.id ?? null);
  };

  const handleSelectGraphNode = (node: StreamNode) => {
    setSelectedGraphNodeId(node.id);
    setSelectedNode(result?.nodesById.get(node.inventoryId) ?? null);
  };

  const handleRunAnalysis = async () => {
    if (!periodFrom || !periodTo) {
      setAnalysisError('Please select both Start Date and End Date.');
      return;
    }
    if (periodFrom > periodTo) {
      setAnalysisError('Start Date must not be after End Date.');
      return;
    }
    if (!comparablePeriodFrom || !comparablePeriodTo || comparablePeriodFrom > comparablePeriodTo) {
      setAnalysisError('Please select an ordered comparable period.');
      return;
    }
    if (!Number.isFinite(leafThreshold) || leafThreshold < 0
      || !Number.isFinite(threshold) || threshold < 0) {
      setAnalysisError('Cost thresholds must be non-negative numbers.');
      return;
    }

    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const version = ++requestVersion.current;
    setIsAnalyzing(true);
    setAnalysisError(null);
    setPathError(null);
    setReconciliationPaths(null);
    setGraphSnapshot(null);
    setResult(null);
    setSelectedNode(null);
    setSelectedGraphNodeId(null);
    setHighlightedNodeIds(new Set());
    setHighlightedEdgeIds(new Set());

    let receivedGraph = false;
    try {
      const response = await fetch('/api/v1/analysis/reconcile-periods/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/x-ndjson' },
        signal: controller.signal,
        body: JSON.stringify({
          actualStartDate: periodFrom,
          actualEndDate: periodTo,
          comparableStartDate: comparablePeriodFrom,
          comparableEndDate: comparablePeriodTo,
          leafThreshold,
          stopThreshold: threshold,
        }),
      });
      if (!response.ok) throw new Error(`Analysis failed (${response.status})`);

      let snapshot: {
        analysisId: string; actual: StreamCsrGraph; comparable: StreamCsrGraph;
        actualLabel: string; comparableLabel: string;
        actualStartDate: string; actualEndDate: string;
        comparableStartDate: string; comparableEndDate: string;
      } | null = null;
      let receivedDiff = false;
      let streamError: string | null = null;
      await readAnalysisStream(response, (event) => {
        if (version !== requestVersion.current) return;
        if (event.type === 'graph') {
          receivedGraph = true;
          snapshot = {
            analysisId: event.analysisId,
            actual: event.actualGraph,
            comparable: event.comparableGraph,
            actualLabel: `Actual ${periodFrom} to ${periodTo}`,
            comparableLabel: `Comparable ${comparablePeriodFrom} to ${comparablePeriodTo}`,
            actualStartDate: periodFrom,
            actualEndDate: periodTo,
            comparableStartDate: comparablePeriodFrom,
            comparableEndDate: comparablePeriodTo,
          };
          setGraphSnapshot(snapshot);
          const reconciled = reconcileDualBom(
            csrToAdjacency(event.actualGraph), csrToAdjacency(event.comparableGraph), threshold,
          );
          setResult(reconciled);
          const first = reconciled.nodes
            .filter((node) => (node.costDelta ?? 0) > 0)
            .sort((left, right) => (right.costDelta ?? 0) - (left.costDelta ?? 0))[0]
            ?? reconciled.nodes[0];
          setSelectedNode(first ?? null);
          setSelectedGraphNodeId(event.actualGraph.nodes.find((node) => node.inventoryId === first?.id)?.id ?? null);
        } else if (event.type === 'diff') {
          if (!snapshot || snapshot.analysisId !== event.analysisId) return;
          receivedDiff = true;
          setHighlightedNodeIds(new Set(event.paths.flatMap((path) => path.nodeIds)));
          setHighlightedEdgeIds(new Set(event.paths.flatMap((path) => path.edgeIds)));
          setReconciliationPaths(event.paths.map((path) => ({
            nodes: path.positions.map((position) => snapshot!.actual.nodes[position]),
            edges: path.edgeIndexes.map((edgeIndex, index) => ({
              edgeIndex,
              fromPosition: path.positions[index],
              toPosition: path.positions[index + 1],
            })),
            endReason: path.endReason,
            endingCostDifference: path.endingCostDifference,
          })));
        } else if (event.type === 'error' && (!snapshot || snapshot.analysisId === event.analysisId)) {
          streamError = event.message;
        }
      });
      if (streamError) throw new Error(streamError);
      if (!snapshot) throw new Error('Analysis returned no graph.');
      if (!receivedDiff) throw new Error('Analysis returned no diff result.');
    } catch (err) {
      if (version === requestVersion.current && !controller.signal.aborted) {
        const message = err instanceof Error ? err.message : 'Analysis failed.';
        if (receivedGraph) setPathError(message);
        else setAnalysisError(message);
      }
    } finally {
      if (version === requestVersion.current) setIsAnalyzing(false);
    }
  };

  // Run initial trace on mount to immediately display the workbench
  useEffect(() => {
    handleRunAnalysis();
    return () => {
      requestVersion.current++;
      abortRef.current?.abort();
    };
  }, []);

  const topOverrunNode = result?.nodes
    .filter((node) => (node.costDelta ?? 0) > 0)
    .sort((left, right) => (right.costDelta ?? 0) - (left.costDelta ?? 0))[0];

  return (
    <div className="flex-1 flex flex-col h-full bg-slate-50 text-slate-800 font-sans overflow-hidden select-none">
      {/* 1. Header with System ID & Engine Status */}
      <AnalysisHeader />

      {/* 2. Full-Width Scope Parameters Bar */}
      <ScopeControlsBar
        periodFrom={periodFrom}
        setPeriodFrom={setPeriodFrom}
        periodTo={periodTo}
        setPeriodTo={setPeriodTo}
        threshold={threshold}
        setThreshold={setThreshold}
        comparablePeriodFrom={comparablePeriodFrom}
        setComparablePeriodFrom={setComparablePeriodFrom}
        comparablePeriodTo={comparablePeriodTo}
        setComparablePeriodTo={setComparablePeriodTo}
        leafThreshold={leafThreshold}
        setLeafThreshold={setLeafThreshold}
        isAnalyzing={isAnalyzing}
        onRunAnalysis={handleRunAnalysis}
      />

      <ReconciliationPathsPanel paths={reconciliationPaths} error={pathError} />

      {/* 3. Top Summary KPI Bar */}
      <KpiSummaryBar
        netVariance={result?.netVariance ?? 0}
        penetratedNodesCount={result?.penetratedNodesCount ?? 0}
        riskTier={result?.riskTier ?? 'Nominal In-Spec'}
        topOverrunNodeId={topOverrunNode?.id}
        onOpenAiDossier={() => {
          if (topOverrunNode) handleInspectNode(topOverrunNode);
        }}
        onInspectTopNode={() => {
          if (topOverrunNode) handleInspectNode(topOverrunNode);
        }}
      />

      {/* 4. Main Split: Left Scrollable Canvas vs Right Fixed Sidebar */}
      <div className="flex-1 flex flex-col lg:flex-row w-full overflow-hidden min-h-0">
        {/* Left Area: Symmetrical Tree Structure + Resizable Material Ledger */}
        <div
          ref={leftAreaRef}
          className={`flex-1 flex flex-col min-w-0 h-full overflow-hidden border-r border-slate-200 ${
            isDraggingSplitter ? 'select-none cursor-row-resize' : ''
          }`}
        >
          {analysisError && (
            <div className="m-3 p-2.5 bg-rose-50 border border-rose-200 rounded text-xs text-rose-700 shrink-0">
              Notice: {analysisError}
            </div>
          )}

          {result && graphSnapshot && (
            <>
              {/* SECTION 1: Dual-BOM True Tree Canvas (Default 70% Vertical Height Space, adjustable) */}
              <div
                style={{ height: `${treeHeightPct}%` }}
                className="min-h-0 overflow-y-auto overflow-x-auto border-b border-slate-200 flex flex-col shrink-0"
              >
                <DualBomTreeCanvas
                  actualGraph={graphSnapshot.actual}
                  comparableGraph={graphSnapshot.comparable}
                  actualLabel={graphSnapshot.actualLabel}
                  comparableLabel={graphSnapshot.comparableLabel}
                  highlightedNodeIds={highlightedNodeIds}
                  highlightedEdgeIds={highlightedEdgeIds}
                  selectedNodeId={selectedGraphNodeId}
                  onSelectNode={handleSelectGraphNode}
                />
              </div>

              {/* VERTICAL SPLITTER: Draggable Bar allowing users to pull the bottom table upwards */}
              <div
                onMouseDown={handleSplitterMouseDown}
                onDoubleClick={() => setTreeHeightPct(70)}
                className={`h-3 bg-slate-100 hover:bg-blue-100 active:bg-blue-200 border-y border-slate-200 cursor-row-resize flex items-center justify-center transition-colors group relative z-20 select-none shrink-0 ${
                  isDraggingSplitter ? 'bg-blue-200 border-blue-400' : ''
                }`}
                title="Drag up or down to adjust table height (Double-click to reset to 70%)"
              >
                <div className="flex items-center gap-1">
                  <div className="w-10 h-1 rounded-full bg-slate-300 group-hover:bg-blue-500 group-active:bg-blue-600 transition-colors" />
                </div>
                {/* Visual indicator / tooltip */}
                <div
                  className={`absolute right-4 text-[10px] font-mono font-medium px-2 py-0.5 rounded shadow-sm transition-opacity pointer-events-none ${
                    isDraggingSplitter
                      ? 'bg-slate-900 text-white opacity-100'
                      : 'bg-white text-slate-500 border border-slate-200 opacity-0 group-hover:opacity-100'
                  }`}
                >
                  Tree: {Math.round(treeHeightPct)}% / Table: {Math.round(100 - treeHeightPct)}% (Drag to resize)
                </div>
              </div>

              {/* SECTION 2: Full-Width Production Material Ledger Workbench (Default 30%, pulled upwards via splitter) */}
              <div
                style={{ height: `calc(${100 - treeHeightPct}% - 12px)` }}
                className="min-h-0 flex flex-col overflow-hidden"
              >
                <MaterialLedgerWorkbench
                  items={result.ledgerItems}
                  selectedNodeId={selectedNode?.id ?? null}
                  onInspectNode={handleInspectNode}
                />
              </div>
            </>
          )}
        </div>

        {/* Right Fixed Sidebar (50/50 Split) */}
        <AuditSidebar
          selectedNode={selectedNode}
          actualStartDate={graphSnapshot?.actualStartDate ?? periodFrom}
          actualEndDate={graphSnapshot?.actualEndDate ?? periodTo}
          comparableStartDate={graphSnapshot?.comparableStartDate ?? comparablePeriodFrom}
          comparableEndDate={graphSnapshot?.comparableEndDate ?? comparablePeriodTo}
        />
      </div>
    </div>
  );
};
