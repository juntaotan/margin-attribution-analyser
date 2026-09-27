import React, { useEffect, useState } from 'react';
import {
  X,
  FileDown,
  FileText,
  CheckCircle2,
  Loader2,
  Edit3,
  ExternalLink,
  Sparkles,
  Calendar,
  AlertCircle,
  FileCheck,
} from 'lucide-react';
import { DualBomNode, formatCurrency, formatQty } from '../../varianceEngine';

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

export interface ManagementCommentaryModalProps {
  isOpen: boolean;
  onClose: () => void;
  actualStartDate: string;
  actualEndDate: string;
  comparableStartDate: string;
  comparableEndDate: string;
  selectedNode?: DualBomNode | null;
  report?: RootCauseReport | null;
}

interface PlaceholderValues {
  currentPeriod: string;
  comparisonPeriod: string;
  revenue: string;
  revenueRaw: number;
  revenueChangePercent: string;
  revenueChangePercentRaw: number;
  grossMargin: string;
  grossMarginRaw: number;
  grossMarginPercent: string;
  grossMarginPercentRaw: number;
  aiAssisted: boolean;
  replacements: Record<string, string>;
}

interface TemplateMetadata {
  filename: string;
  sizeBytes: number;
  updatedAt: string;
  isCustom: boolean;
  matchedCount: number;
  totalExpected: number;
}

export const ManagementCommentaryModal: React.FC<ManagementCommentaryModalProps> = ({
  isOpen,
  onClose,
  actualStartDate,
  actualEndDate,
  comparableStartDate,
  comparableEndDate,
  selectedNode,
  report,
}) => {
  const [templateMeta, setTemplateMeta] = useState<TemplateMetadata | null>(null);
  const [loadingTemplate, setLoadingTemplate] = useState(false);
  const [values, setValues] = useState<PlaceholderValues | null>(null);
  const [loadingValues, setLoadingValues] = useState(false);
  const [fetchError, setFetchError] = useState<string | null>(null);

  const [isExporting, setIsExporting] = useState(false);
  const [isOpeningInStudio, setIsOpeningInStudio] = useState(false);
  const [exportSuccess, setExportSuccess] = useState(false);

  // Close on Escape
  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  // Load template info and preview values whenever modal opens
  useEffect(() => {
    if (!isOpen) return;

    let active = true;
    setLoadingTemplate(true);
    setLoadingValues(true);
    setFetchError(null);

    // 1. Fetch template metadata
    fetch('/api/v1/settings/template')
      .then((res) => (res.ok ? res.json() : null))
      .then((meta: TemplateMetadata | null) => {
        if (active && meta) setTemplateMeta(meta);
      })
      .catch((err) => console.warn('Failed to load template info:', err))
      .finally(() => {
        if (active) setLoadingTemplate(false);
      });

    // 2. Fetch placeholder values
    fetch('/api/v1/report/preview-values', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        actualStartDate,
        actualEndDate,
        comparableStartDate,
        comparableEndDate,
      }),
    })
      .then(async (res) => {
        if (!res.ok) {
          const body = (await res.json().catch(() => ({}))) as { message?: string };
          throw new Error(body.message || `Request failed (${res.status})`);
        }
        return res.json() as Promise<PlaceholderValues>;
      })
      .then((data) => {
        if (active) setValues(data);
      })
      .catch((err: Error) => {
        if (active) setFetchError(err.message);
      })
      .finally(() => {
        if (active) setLoadingValues(false);
      });

    return () => {
      active = false;
    };
  }, [isOpen, actualStartDate, actualEndDate, comparableStartDate, comparableEndDate]);

  if (!isOpen) return null;

  // Direct download Word
  const handleExportDocx = async () => {
    try {
      setIsExporting(true);
      const response = await fetch('/api/v1/report/instantiate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          actualStartDate,
          actualEndDate,
          comparableStartDate,
          comparableEndDate,
          mode: 'download',
        }),
      });

      if (!response.ok) {
        throw new Error(`Export failed (${response.status})`);
      }

      // Read blob and trigger download
      const blob = await response.blob();
      const contentDisposition = response.headers.get('Content-Disposition');
      let filename = 'Management_Commentary.docx';
      if (contentDisposition) {
        const match = contentDisposition.match(/filename="?([^"]+)"?/);
        if (match?.[1]) filename = match[1];
      }

      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(url);

      setExportSuccess(true);
      setTimeout(() => setExportSuccess(false), 3000);
    } catch (err) {
      console.error('Failed to export DOCX:', err);
      alert('Failed to export Word document. Please check network or backend service.');
    } finally {
      setIsExporting(false);
    }
  };

  // Open in Report Studio
  const handleOpenInStudio = async () => {
    try {
      setIsOpeningInStudio(true);
      const response = await fetch('/api/v1/report/instantiate', {
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

      if (!response.ok) {
        throw new Error(`Instantiate failed (${response.status})`);
      }

      const result = (await response.json()) as { redirectUrl?: string };
      onClose();
      const queryParams = new URLSearchParams({
        actualStartDate,
        actualEndDate,
        comparableStartDate: comparableStartDate || '',
        comparableEndDate: comparableEndDate || '',
      });
      const targetUrl = result.redirectUrl || `/report-studio?${queryParams.toString()}`;
      window.history.pushState({}, '', targetUrl);
      window.dispatchEvent(new PopStateEvent('popstate'));
    } catch (err) {
      console.error('Failed to instantiate in Report Studio:', err);
      alert('Failed to open in Report Studio. Please try again.');
    } finally {
      setIsOpeningInStudio(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6 bg-slate-900/60 backdrop-blur-xs animate-in fade-in duration-150"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="bg-white rounded-xl shadow-2xl border border-slate-200 flex flex-col w-full max-w-4xl max-h-[92vh] overflow-hidden">
        {/* Modal Top Bar */}
        <div className="px-6 py-3.5 bg-slate-50 border-b border-slate-200 flex items-center justify-between shrink-0">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-lg bg-blue-100 text-blue-700 flex items-center justify-center">
              <FileText className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-slate-800 leading-none">
                Generate Management Commentary Report
              </h3>
              <p className="text-[11px] text-slate-500 font-mono mt-1">
                Data Lake Master Template Clone · Automated Dual-BOM Metrics &amp; Attribution Injection
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => {
                onClose();
                window.history.pushState({}, '', '/settings');
                window.dispatchEvent(new PopStateEvent('popstate'));
              }}
              title="Go to Settings to configure Word master template"
              className="inline-flex items-center gap-1 px-2.5 py-1 text-xs text-slate-600 hover:text-slate-900 bg-white hover:bg-slate-100 border border-slate-300 rounded-md transition shadow-xs cursor-pointer"
            >
              <span>Configure Master</span>
              <ExternalLink className="w-3 h-3 text-slate-400" />
            </button>

            <button
              type="button"
              onClick={onClose}
              className="p-1.5 text-slate-400 hover:text-slate-700 hover:bg-slate-200/80 rounded-md transition cursor-pointer"
              title="Close modal"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        </div>

        {/* Modal Body */}
        <div className="flex-1 overflow-y-auto p-6 space-y-5 bg-slate-100/50 custom-scrollbar">
          {/* Section 1: Template Status Banner */}
          <div className="bg-white border border-slate-200 rounded-lg p-4 shadow-2xs">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
              <div className="flex items-center gap-3">
                <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-indigo-50 text-indigo-600 border border-indigo-100">
                  <FileCheck className="h-4 w-4" />
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-bold text-slate-800">
                      Active Master Template:
                    </span>
                    <span className="text-xs font-mono text-indigo-700 font-medium">
                      {loadingTemplate ? 'Loading template…' : templateMeta?.filename || 'master-template.docx'}
                    </span>
                    {templateMeta && (
                      <span className={`px-1.5 py-0.5 rounded text-[10px] font-medium ${
                        templateMeta.isCustom ? 'bg-purple-50 text-purple-700 border border-purple-200' : 'bg-slate-100 text-slate-600'
                      }`}>
                        {templateMeta.isCustom ? 'Custom Template' : 'System Standard Master'}
                      </span>
                    )}
                  </div>
                  <div className="text-[11px] text-slate-500 mt-0.5">
                    Upon generation, the system clones a fresh copy of this template from the Data Lake and populates analysis metrics into content controls.
                  </div>
                </div>
              </div>

              <div className="flex items-center gap-2 shrink-0">
                <span className="inline-flex items-center gap-1 px-2.5 py-1 bg-emerald-50 text-emerald-700 border border-emerald-200 rounded-md text-[11px] font-semibold">
                  <CheckCircle2 className="w-3.5 h-3.5 text-emerald-600" />
                  {templateMeta ? `${templateMeta.matchedCount}/${templateMeta.totalExpected} Core Placeholders Ready` : 'Checking placeholders...'}
                </span>
              </div>
            </div>
          </div>

          {/* Section 2: Data Extraction & Placeholder Preview */}
          <div className="bg-white border border-slate-200 rounded-lg p-5 shadow-2xs space-y-4">
            <div className="flex items-center justify-between border-b border-slate-100 pb-2.5">
              <div className="flex items-center gap-2">
                <Calendar className="w-4 h-4 text-blue-600" />
                <h4 className="text-xs font-bold text-slate-800 uppercase tracking-wider">
                  Reporting Parameters &amp; Placeholder Injection Preview
                </h4>
              </div>
              {loadingValues && (
                <div className="flex items-center gap-1.5 text-xs text-blue-600">
                  <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  <span>Executing AI &amp; SQL metrics calculation…</span>
                </div>
              )}
            </div>

            {fetchError && (
              <div className="p-3 rounded-lg bg-rose-50 border border-rose-200 text-xs text-rose-700 flex items-start gap-2">
                <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
                <span>{fetchError}</span>
              </div>
            )}

            {/* Target Material focus if selected */}
            {selectedNode && (
              <div className="bg-slate-50 border border-slate-200 rounded-md p-3 text-xs space-y-1.5">
                <div className="flex items-center justify-between font-mono text-[11px]">
                  <span className="font-bold text-slate-800">
                    Inspected Component Focus: {selectedNode.name} ({selectedNode.id})
                  </span>
                  <span className="text-slate-500">ECN: {selectedNode.ecn} · Station: {selectedNode.station}</span>
                </div>
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 pt-1 font-mono text-[11px]">
                  <div className="bg-white p-2 rounded border border-slate-200">
                    <span className="text-slate-400 block text-[10px]">Std Quantity</span>
                    <span className="font-semibold text-slate-800">{formatQty(selectedNode.standardQty)}</span>
                  </div>
                  <div className="bg-white p-2 rounded border border-slate-200">
                    <span className="text-slate-400 block text-[10px]">Actual Issued</span>
                    <span className="font-semibold text-slate-800">
                      {formatQty(selectedNode.actualQty)}{' '}
                      <span className="text-[10px] text-slate-500">
                        ({selectedNode.quantityDeltaPercent > 0 ? '+' : ''}{selectedNode.quantityDeltaPercent.toFixed(1)}%)
                      </span>
                    </span>
                  </div>
                  <div className="bg-white p-2 rounded border border-slate-200">
                    <span className="text-slate-400 block text-[10px]">Baseline Cost</span>
                    <span className="font-semibold text-slate-800">{formatCurrency(selectedNode.baselineCost)}</span>
                  </div>
                  <div className="bg-white p-2 rounded border border-slate-200">
                    <span className="text-slate-400 block text-[10px]">Net Variance</span>
                    <span className={`font-bold ${(selectedNode.costDelta ?? 0) > 0 ? 'text-rose-600' : 'text-emerald-600'}`}>
                      {formatCurrency(selectedNode.costDelta)}
                    </span>
                  </div>
                </div>
              </div>
            )}

            {/* 6 Placeholders Table */}
            <div className="border border-slate-200 rounded-lg overflow-hidden">
              <table className="w-full text-left text-xs border-collapse">
                <thead>
                  <tr className="bg-slate-50 border-b border-slate-200 text-slate-500 font-semibold text-[11px]">
                    <th className="py-2.5 px-3">Placeholder (Tag / Alias)</th>
                    <th className="py-2.5 px-3">Field Description</th>
                    <th className="py-2.5 px-3">Resolution Source</th>
                    <th className="py-2.5 px-3 text-right">Injected Value</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  <tr className="hover:bg-slate-50/50">
                    <td className="py-2.5 px-3 font-mono font-semibold text-blue-700">Current Period</td>
                    <td className="py-2.5 px-3 text-slate-600">Current reporting duration</td>
                    <td className="py-2.5 px-3 text-slate-500">Analysis page context</td>
                    <td className="py-2.5 px-3 text-right font-mono font-medium text-slate-800">
                      {values?.currentPeriod || `${actualStartDate} to ${actualEndDate}`}
                    </td>
                  </tr>

                  <tr className="hover:bg-slate-50/50">
                    <td className="py-2.5 px-3 font-mono font-semibold text-blue-700">Comparison Period</td>
                    <td className="py-2.5 px-3 text-slate-600">Comparable baseline duration</td>
                    <td className="py-2.5 px-3 text-slate-500">Analysis page context</td>
                    <td className="py-2.5 px-3 text-right font-mono font-medium text-slate-800">
                      {values?.comparisonPeriod || `${comparableStartDate} to ${comparableEndDate}`}
                    </td>
                  </tr>

                  <tr className="hover:bg-slate-50/50">
                    <td className="py-2.5 px-3 font-mono font-semibold text-blue-700">Revenue</td>
                    <td className="py-2.5 px-3 text-slate-600">Gross sales revenue cleared</td>
                    <td className="py-2.5 px-3 text-slate-500">
                      <span className="inline-flex items-center gap-1 text-[11px] text-indigo-700">
                        <Sparkles className="w-3 h-3 text-indigo-500" />
                        AI (sales_order)
                      </span>
                    </td>
                    <td className="py-2.5 px-3 text-right font-mono font-bold text-slate-900">
                      {loadingValues ? 'Calculating…' : values?.revenue || '$0.00'}
                    </td>
                  </tr>

                  <tr className="hover:bg-slate-50/50">
                    <td className="py-2.5 px-3 font-mono font-semibold text-blue-700">Revenue Change %</td>
                    <td className="py-2.5 px-3 text-slate-600">Period-over-period revenue growth</td>
                    <td className="py-2.5 px-3 text-slate-500">
                      <span className="inline-flex items-center gap-1 text-[11px] text-indigo-700">
                        <Sparkles className="w-3 h-3 text-indigo-500" />
                        AI (Comparable analysis)
                      </span>
                    </td>
                    <td className="py-2.5 px-3 text-right font-mono font-bold text-slate-900">
                      {loadingValues ? 'Calculating…' : values?.revenueChangePercent || '0.0%'}
                    </td>
                  </tr>

                  <tr className="hover:bg-slate-50/50">
                    <td className="py-2.5 px-3 font-mono font-semibold text-blue-700">Gross Margin</td>
                    <td className="py-2.5 px-3 text-slate-600">Total gross margin contribution</td>
                    <td className="py-2.5 px-3 text-slate-500">
                      <span className="inline-flex items-center gap-1 text-[11px] text-indigo-700">
                        <Sparkles className="w-3 h-3 text-indigo-500" />
                        AI (Revenue minus Cost)
                      </span>
                    </td>
                    <td className="py-2.5 px-3 text-right font-mono font-bold text-emerald-600">
                      {loadingValues ? 'Calculating…' : values?.grossMargin || '$0.00'}
                    </td>
                  </tr>

                  <tr className="hover:bg-slate-50/50">
                    <td className="py-2.5 px-3 font-mono font-semibold text-blue-700">Gross Margin %</td>
                    <td className="py-2.5 px-3 text-slate-600">Effective gross margin rate</td>
                    <td className="py-2.5 px-3 text-slate-500">
                      <span className="inline-flex items-center gap-1 text-[11px] text-indigo-700">
                        <Sparkles className="w-3 h-3 text-indigo-500" />
                        AI (Margin rate model)
                      </span>
                    </td>
                    <td className="py-2.5 px-3 text-right font-mono font-bold text-emerald-600">
                      {loadingValues ? 'Calculating…' : values?.grossMarginPercent || '0.0%'}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            {/* AI Root-cause synthesis if report exists */}
            {report?.summary && (
              <div className="bg-blue-50/70 border border-blue-200 rounded-md p-3 text-xs text-blue-900 leading-relaxed">
                <span className="font-bold flex items-center gap-1 text-blue-800 mb-1">
                  <Sparkles className="w-3.5 h-3.5 text-blue-600" />
                  AI Root-Cause Diagnostic Summary ({report.categoryLabel}):
                </span>
                <p>{report.summary}</p>
              </div>
            )}
          </div>
        </div>

        {/* Modal Footer */}
        <div className="px-6 py-3.5 bg-slate-50 border-t border-slate-200 flex flex-col sm:flex-row sm:items-center justify-between gap-3 text-xs shrink-0">
          <div className="text-slate-500 text-[11px] flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full bg-emerald-500"></span>
            <span>Ready: Document generation clones the Data Lake master template and preserves all corporate styling.</span>
          </div>

          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              className="px-3.5 py-1.5 bg-white hover:bg-slate-100 text-slate-700 border border-slate-300 rounded-md font-medium transition cursor-pointer"
            >
              Cancel
            </button>

            <button
              type="button"
              onClick={handleOpenInStudio}
              disabled={isOpeningInStudio || isExporting}
              className="inline-flex items-center gap-1.5 px-3.5 py-1.5 bg-indigo-50 hover:bg-indigo-100 text-indigo-700 border border-indigo-200 rounded-md font-semibold transition shadow-xs disabled:opacity-60 cursor-pointer"
            >
              {isOpeningInStudio ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <Edit3 className="w-3.5 h-3.5" />
              )}
              <span>{isOpeningInStudio ? 'Loading into Studio…' : 'Edit in Report Studio'}</span>
            </button>

            <button
              type="button"
              onClick={handleExportDocx}
              disabled={isExporting || isOpeningInStudio}
              className="inline-flex items-center gap-1.5 px-4 py-1.5 bg-blue-600 hover:bg-blue-700 active:bg-blue-800 text-white rounded-md font-semibold transition shadow-xs disabled:opacity-60 cursor-pointer"
            >
              {isExporting ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : exportSuccess ? (
                <CheckCircle2 className="w-3.5 h-3.5 text-emerald-300" />
              ) : (
                <FileDown className="w-3.5 h-3.5" />
              )}
              <span>{isExporting ? 'Generating DOCX…' : exportSuccess ? 'Exported!' : 'Export to DOCX'}</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
