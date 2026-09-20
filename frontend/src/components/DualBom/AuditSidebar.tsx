import React, { useEffect, useState } from 'react';
import {
  Crosshair,
  Sparkles,
  RefreshCw,
  FileText,
} from 'lucide-react';
import {
  DualBomNode,
  formatCurrency,
  formatQty,
} from '../../varianceEngine';
import { ManagementCommentaryModal } from './ManagementCommentaryModal';

interface AuditSidebarProps {
  selectedNode: DualBomNode | null;
  actualStartDate: string;
  actualEndDate: string;
  comparableStartDate: string;
  comparableEndDate: string;
}

interface RootCauseReport {
  inventoryId: string;
  category: string;
  categoryLabel: string;
  certainty: string;
  evidence: string[];
  summary: string | null;
  aiGenerated: boolean;
  aiMessage: string | null;
}

export const AuditSidebar: React.FC<AuditSidebarProps> = ({
  selectedNode,
  actualStartDate,
  actualEndDate,
  comparableStartDate,
  comparableEndDate,
}) => {
  const [report, setReport] = useState<RootCauseReport | null>(null);
  const [reportError, setReportError] = useState<string | null>(null);
  const [loadingReport, setLoadingReport] = useState(false);
  const [retryCount, setRetryCount] = useState(0);
  const [isCommentaryModalOpen, setIsCommentaryModalOpen] = useState(false);
  const selectedId = selectedNode?.id;

  useEffect(() => {
    if (!selectedId) {
      setReport(null);
      return;
    }
    const controller = new AbortController();
    setReport(null);
    setReportError(null);
    setLoadingReport(true);
    fetch('/api/v1/analysis/root-cause-report', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      signal: controller.signal,
      body: JSON.stringify({
        actualStartDate, actualEndDate, comparableStartDate, comparableEndDate,
        inventoryId: selectedId,
      }),
    }).then(async (response) => {
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.message ?? `Report failed (${response.status})`);
      }
      return response.json() as Promise<RootCauseReport>;
    }).then(setReport).catch((error) => {
      if (!controller.signal.aborted) setReportError(error instanceof Error ? error.message : 'Report failed');
    }).finally(() => {
      if (!controller.signal.aborted) setLoadingReport(false);
    });
    return () => controller.abort();
  }, [selectedId, actualStartDate, actualEndDate, comparableStartDate, comparableEndDate, retryCount]);

  if (!selectedNode) {
    return (
      <>
        <aside className="w-full lg:w-96 xl:w-96 bg-white flex flex-col shrink-0 border-t lg:border-t-0 border-slate-200 p-6 text-center justify-center items-center text-slate-400">
          <Crosshair className="w-10 h-10 text-slate-300 stroke-1 mb-2" />
          <p className="text-xs font-semibold text-slate-600">No Node Selected</p>
          <p className="text-[11px] text-slate-400 mt-1 max-w-[220px]">
            Click any component card in the Dual-BOM tree or material ledger table to inspect detailed specifications and causal audit reports.
          </p>
          <button
            type="button"
            onClick={() => setIsCommentaryModalOpen(true)}
            className="mt-4 px-3 py-1.5 bg-blue-50 hover:bg-blue-100 text-blue-700 border border-blue-200 rounded text-xs font-medium flex items-center gap-1.5 transition-colors"
          >
            <FileText className="w-3.5 h-3.5 text-blue-600" />
            <span>Management Commentary</span>
          </button>
        </aside>
        <ManagementCommentaryModal
          isOpen={isCommentaryModalOpen}
          onClose={() => setIsCommentaryModalOpen(false)}
          actualStartDate={actualStartDate}
          actualEndDate={actualEndDate}
          comparableStartDate={comparableStartDate}
          comparableEndDate={comparableEndDate}
          selectedNode={null}
          report={null}
        />
      </>
    );
  }

  const isOverrun = (selectedNode.costDelta ?? 0) > 0;

  return (
    <aside className="w-full lg:w-96 xl:w-96 bg-white flex flex-col shrink-0 border-t lg:border-t-0 border-slate-200 overflow-y-auto">
      {/* ================= Upper 50%: Node Inspector Specification ================= */}
      <div className="p-3.5 border-b border-slate-200 flex flex-col bg-white">
        <div className="flex items-center justify-between border-b border-slate-100 pb-2 mb-2.5">
          <div className="truncate pr-2">
            <span className="font-mono text-xs font-bold text-blue-700 flex items-center gap-1">
              <Crosshair className="w-3.5 h-3.5 text-blue-600" />
              {selectedNode.id} Spec
            </span>
            <h4 className="text-xs font-bold text-slate-900 truncate" title={selectedNode.name}>
              {selectedNode.name}
            </h4>
          </div>
          <span className="font-mono text-[10px] bg-slate-100 px-2 py-0.5 rounded border border-slate-200 text-slate-600 shrink-0">
            {selectedNode.ecn}
          </span>
        </div>

        {/* 4-Grid Metrics */}
        <div className="grid grid-cols-2 gap-2 font-mono text-xs mb-2.5">
          <div className="bg-slate-50 p-2 rounded border border-slate-200">
            <span className="text-slate-400 block text-[10px] uppercase font-bold tracking-wider">
              Standard Qty
            </span>
            <span className="font-semibold text-slate-800">
              {formatQty(selectedNode.standardQty)}
            </span>
          </div>

          <div
            className={`p-2 rounded border ${
              isOverrun
                ? 'bg-rose-50 border-rose-200 text-rose-700'
                : 'bg-emerald-50 border-emerald-200 text-emerald-700'
            }`}
          >
            <span className="block text-[10px] uppercase font-bold tracking-wider opacity-80">
              Actual Issued
            </span>
            <span className="font-bold">
              {formatQty(selectedNode.actualQty)}{' '}
              {selectedNode.quantityDeltaPercent !== 0 && (
                <span className="text-[10px]">
                  ({selectedNode.quantityDeltaPercent > 0 ? '+' : ''}
                  {selectedNode.quantityDeltaPercent.toFixed(1)}%)
                </span>
              )}
            </span>
          </div>

          <div className="bg-slate-50 p-2 rounded border border-slate-200">
            <span className="text-slate-400 block text-[10px] uppercase font-bold tracking-wider">
              Baseline Cost
            </span>
            <span className="font-semibold text-slate-800">
              {formatCurrency(selectedNode.baselineCost)}
            </span>
          </div>

          <div
            className={`p-2 rounded border ${
              isOverrun
                ? 'bg-rose-50 border-rose-200 text-rose-700'
                : 'bg-emerald-50 border-emerald-200 text-emerald-700'
            }`}
          >
            <span className="block text-[10px] uppercase font-bold tracking-wider opacity-80">
              Net Variance
            </span>
            <span className="font-bold">
              {formatCurrency(selectedNode.costDelta)}
            </span>
          </div>
        </div>

        {/* Hierarchy Context */}
        <div className="bg-slate-50 p-2 rounded border border-slate-200 text-xs mb-2">
          <div className="flex justify-between items-center text-[11px] mb-1 font-mono">
            <span className="text-slate-500">
              Station: <strong className="text-slate-800">{selectedNode.station}</strong>
            </span>
            <span className="text-slate-500">
              Level: <strong className="text-slate-800">Level {selectedNode.level}</strong>
            </span>
          </div>
          <div className="text-slate-600 text-[11px] truncate">
            Material: High-Grade Composite Specification Class-A
          </div>
        </div>

      </div>

      <div className="p-3.5 flex-1 flex flex-col bg-slate-50/60">
          <div className="flex items-center justify-between border-b border-slate-200 pb-1.5">
            <span className="text-xs font-bold uppercase tracking-wider text-slate-700 flex items-center gap-1.5">
              <Sparkles className="w-3.5 h-3.5 text-blue-600" />
              AI Root-Cause Audit Report
            </span>
            <div className="flex items-center gap-1.5">
              {loadingReport && <RefreshCw className="w-3.5 h-3.5 animate-spin text-blue-600" />}
              <button
                type="button"
                onClick={() => setIsCommentaryModalOpen(true)}
                className="inline-flex items-center gap-1 px-2 py-0.5 text-[11px] font-medium text-blue-700 bg-blue-50 hover:bg-blue-100 active:bg-blue-200 border border-blue-200 rounded transition-colors"
                title="Open Management Commentary Report"
              >
                <FileText className="w-3 h-3 text-blue-600" />
                <span>Commentary</span>
              </button>
            </div>
          </div>
          <div className="bg-white border border-slate-200 rounded p-3 mt-3 text-xs">
            <h5 className="text-[10px] font-bold text-slate-500 uppercase tracking-wider mb-2">
              Analysis report summary
            </h5>
            {loadingReport && <p className="text-slate-500">Analyzing the selected material…</p>}
            {reportError && <p className="text-rose-700">{reportError}</p>}
            {report && (
              <>
                <p className="font-semibold text-slate-900">{report.categoryLabel} · {report.certainty}</p>
                <ul className="mt-2 list-disc pl-4 space-y-1 text-slate-600">
                  {report.evidence.map((item, index) => <li key={index}>{item}</li>)}
                </ul>
                {report.aiGenerated && report.summary && (
                  <p className="mt-3 rounded bg-blue-50 border border-blue-100 p-2 text-blue-900 leading-relaxed">
                    <span className="font-bold">llama.cpp summary: </span>{report.summary}
                  </p>
                )}
                {!report.aiGenerated && <p className="mt-3 text-amber-700">AI summary unavailable: {report.aiMessage}</p>}
              </>
            )}
            {!loadingReport && (reportError || (report && !report.aiGenerated)) && (
              <button type="button" onClick={() => setRetryCount((count) => count + 1)}
                className="mt-2 text-blue-700 hover:underline font-semibold block">
                Retry AI summary
              </button>
            )}

            {/* Quick Action to open the full Management Commentary */}
            <div className="mt-3 pt-2.5 border-t border-slate-100">
              <button
                type="button"
                onClick={() => setIsCommentaryModalOpen(true)}
                className="w-full py-1.5 px-3 bg-blue-600 hover:bg-blue-700 active:bg-blue-800 text-white rounded text-xs font-medium flex items-center justify-center gap-1.5 shadow-xs transition-colors"
              >
                <FileText className="w-3.5 h-3.5" />
                <span>Open Management Commentary Report</span>
              </button>
            </div>
          </div>
      </div>
      <ManagementCommentaryModal
        isOpen={isCommentaryModalOpen}
        onClose={() => setIsCommentaryModalOpen(false)}
        actualStartDate={actualStartDate}
        actualEndDate={actualEndDate}
        comparableStartDate={comparableStartDate}
        comparableEndDate={comparableEndDate}
        selectedNode={selectedNode}
        report={report}
      />
    </aside>
  );
};
