import React, { useEffect, useState } from 'react';
import {
  Crosshair,
  Sparkles,
  RefreshCw,
  FileText,
  LoaderCircle,
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
  const [isNavigatingToStudio, setIsNavigatingToStudio] = useState(false);
  const selectedId = selectedNode?.id;

  const handleOpenReportStudio = async () => {
    try {
      setIsNavigatingToStudio(true);
      await fetch('/api/v1/report/instantiate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          actualStartDate,
          actualEndDate,
          comparableStartDate,
          comparableEndDate,
          mode: 'open_in_studio',
        }),
      });
    } catch (err) {
      console.warn('Instantiate before navigation warning:', err);
    } finally {
      setIsNavigatingToStudio(false);
      const queryParams = new URLSearchParams({
        actualStartDate,
        actualEndDate,
        comparableStartDate: comparableStartDate || '',
        comparableEndDate: comparableEndDate || '',
      });
      const targetUrl = `/report-studio?${queryParams.toString()}`;
      window.history.pushState({}, '', targetUrl);
      window.dispatchEvent(new PopStateEvent('popstate'));
    }
  };

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
        <aside className="w-full lg:w-96 xl:w-96 bg-white flex flex-col shrink-0 border-t lg:border-t-0 border-slate-200/80 p-6 text-center justify-center items-center text-slate-400">
          <Crosshair className="w-10 h-10 text-slate-300 stroke-1 mb-2" />
          <p className="text-xs font-semibold text-slate-600">No Node Selected</p>
          <p className="text-[11px] text-slate-400 mt-1 max-w-[220px]">
            Click any component card in the Dual-BOM tree or material ledger table to inspect detailed specifications and causal audit reports.
          </p>

          <div className="mt-5 w-full space-y-2">
            <button
              type="button"
              onClick={handleOpenReportStudio}
              disabled={isNavigatingToStudio}
              className="w-full py-2 px-3 bg-slate-900 hover:bg-slate-800 active:bg-slate-950 text-white rounded-lg text-xs font-semibold flex items-center justify-center gap-1.5 shadow-xs transition-colors cursor-pointer disabled:opacity-60 border border-slate-800"
            >
              {isNavigatingToStudio ? (
                <LoaderCircle className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <Sparkles className="w-3.5 h-3.5 text-sky-400" />
              )}
              <span>{isNavigatingToStudio ? 'Opening Report Studio…' : 'Open in Report Studio'}</span>
            </button>

            <button
              type="button"
              onClick={() => setIsCommentaryModalOpen(true)}
              className="w-full py-1.5 px-3 bg-[#f8fafc] hover:bg-slate-100 text-slate-700 border border-slate-300/80 rounded-lg text-xs font-medium flex items-center justify-center gap-1.5 transition-colors cursor-pointer shadow-2xs"
            >
              <FileText className="w-3.5 h-3.5 text-slate-500" />
              <span>Export &amp; Preview Options</span>
            </button>
          </div>
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
    <aside className="w-full lg:w-96 xl:w-96 bg-white flex flex-col shrink-0 border-t lg:border-t-0 border-slate-200/80 overflow-y-auto">
      {/* ================= Upper 50%: Node Inspector Specification ================= */}
      <div className="p-3.5 border-b border-slate-200/80 flex flex-col bg-white">
        <div className="flex items-center justify-between border-b border-slate-100 pb-2 mb-2.5">
          <div className="truncate pr-2">
            <span className="font-mono text-xs font-bold text-sky-700 flex items-center gap-1">
              <Crosshair className="w-3.5 h-3.5 text-sky-600" />
              {selectedNode.id} Spec
            </span>
            <h4 className="text-xs font-bold text-slate-900 truncate" title={selectedNode.name}>
              {selectedNode.name}
            </h4>
          </div>
          <span className="font-mono text-[10px] bg-[#f8fafc] px-2 py-0.5 rounded border border-slate-200/80 text-slate-600 shrink-0">
            {selectedNode.ecn}
          </span>
        </div>

        {/* 4-Grid Metrics */}
        <div className="grid grid-cols-2 gap-2 font-mono text-xs mb-2.5">
          <div className="bg-[#f8fafc] p-2 rounded border border-slate-200/80">
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
                ? 'bg-rose-50/80 border-rose-200/80 text-rose-700'
                : 'bg-emerald-50/80 border-emerald-200/80 text-emerald-700'
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

          <div className="bg-[#f8fafc] p-2 rounded border border-slate-200/80">
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
                ? 'bg-rose-50/80 border-rose-200/80 text-rose-700'
                : 'bg-emerald-50/80 border-emerald-200/80 text-emerald-700'
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
        <div className="bg-[#f8fafc] p-2 rounded border border-slate-200/80 text-xs mb-2">
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

      <div className="p-3.5 flex-1 flex flex-col bg-[#f8fafc]">
          <div className="flex items-center justify-between border-b border-slate-200/80 pb-1.5">
            <span className="text-xs font-bold uppercase tracking-wider text-slate-700 flex items-center gap-1.5">
              <Sparkles className="w-3.5 h-3.5 text-sky-600" />
              AI Root-Cause Audit Report
            </span>
            <div className="flex items-center gap-1.5">
              {loadingReport && <RefreshCw className="w-3.5 h-3.5 animate-spin text-sky-600" />}
              <button
                type="button"
                onClick={() => setIsCommentaryModalOpen(true)}
                className="inline-flex items-center gap-1 px-2 py-0.5 text-[11px] font-medium text-slate-700 bg-white hover:bg-slate-50 active:bg-slate-100 border border-slate-300/80 rounded transition-colors shadow-2xs"
                title="Open Management Commentary Report"
              >
                <FileText className="w-3 h-3 text-slate-500" />
                <span>Commentary</span>
              </button>
            </div>
          </div>
          <div className="bg-white border border-slate-200/80 rounded p-3 mt-3 text-xs shadow-2xs">
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
                  <p className="mt-3 rounded bg-sky-50/60 border border-sky-100 p-2 text-sky-950 leading-relaxed">
                    <span className="font-bold">llama.cpp summary: </span>{report.summary}
                  </p>
                )}
                {!report.aiGenerated && <p className="mt-3 text-amber-700">AI summary unavailable: {report.aiMessage}</p>}
              </>
            )}
            {!loadingReport && (reportError || (report && !report.aiGenerated)) && (
              <button type="button" onClick={() => setRetryCount((count) => count + 1)}
                className="mt-2 text-sky-700 hover:underline font-semibold block">
                Retry AI summary
              </button>
            )}

            {/* Quick Actions to open full Management Commentary or jump to Report Studio */}
            <div className="mt-3 pt-2.5 border-t border-slate-100 space-y-2">
              <button
                type="button"
                onClick={handleOpenReportStudio}
                disabled={isNavigatingToStudio}
                className="w-full py-2 px-3 bg-slate-900 hover:bg-slate-800 active:bg-slate-950 text-white rounded-lg text-xs font-semibold flex items-center justify-center gap-1.5 shadow-xs transition-colors cursor-pointer disabled:opacity-60 border border-slate-800"
              >
                {isNavigatingToStudio ? (
                  <LoaderCircle className="w-3.5 h-3.5 animate-spin" />
                ) : (
                  <Sparkles className="w-3.5 h-3.5 text-sky-400" />
                )}
                <span>{isNavigatingToStudio ? 'Opening Report Studio…' : 'Open in Report Studio'}</span>
              </button>

              <button
                type="button"
                onClick={() => setIsCommentaryModalOpen(true)}
                className="w-full py-1.5 px-3 bg-white hover:bg-slate-50 text-slate-700 border border-slate-300/80 rounded-lg text-xs font-medium flex items-center justify-center gap-1.5 transition-colors cursor-pointer shadow-2xs"
              >
                <FileText className="w-3.5 h-3.5 text-slate-500" />
                <span>Export &amp; Preview Options</span>
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
