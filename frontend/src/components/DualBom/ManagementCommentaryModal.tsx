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

/** Periods and optional selected node and root-cause report to preview. */
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

/** The actual and comparable periods the report is generated for. */
type ReportPeriods = Pick<
  ManagementCommentaryModalProps,
  'actualStartDate' | 'actualEndDate' | 'comparableStartDate' | 'comparableEndDate'
>;

/** Navigates within the single-page application without a full page load. */
function navigateTo(url: string) {
  window.history.pushState({}, '', url);
  window.dispatchEvent(new PopStateEvent('popstate'));
}

/** Asks the backend to clone the master template and fill it for the given periods. */
async function instantiateReport(periods: ReportPeriods, mode: 'download' | 'open_in_studio', failure: string) {
  const response = await fetch('/api/v1/report/instantiate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...periods, mode }),
  });
  if (!response.ok) {
    throw new Error(`${failure} (${response.status})`);
  }
  return response;
}

/** Saves a response body as a file, using the server's `Content-Disposition` filename when there is one. */
async function downloadResponse(response: Response, defaultFilename: string) {
  const blob = await response.blob();
  const match = response.headers.get('Content-Disposition')?.match(/filename="?([^"]+)"?/);
  const url = window.URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = match?.[1] || defaultFilename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.URL.revokeObjectURL(url);
}

async function fetchPreviewValues(periods: ReportPeriods): Promise<PlaceholderValues> {
  const res = await fetch('/api/v1/report/preview-values', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(periods),
  });
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { message?: string };
    throw new Error(body.message || `Request failed (${res.status})`);
  }
  return res.json() as Promise<PlaceholderValues>;
}

/**
 * Loads the active master template and the placeholder values for the periods each time the modal opens or the
 * periods change. Template failures are only logged; value failures are returned in `fetchError`.
 */
function useCommentaryPreview(isOpen: boolean, periods: ReportPeriods) {
  const [templateMeta, setTemplateMeta] = useState<TemplateMetadata | null>(null);
  const [loadingTemplate, setLoadingTemplate] = useState(false);
  const [values, setValues] = useState<PlaceholderValues | null>(null);
  const [loadingValues, setLoadingValues] = useState(false);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const { actualStartDate, actualEndDate, comparableStartDate, comparableEndDate } = periods;

  useEffect(() => {
    if (!isOpen) return;

    let active = true;
    setLoadingTemplate(true);
    setLoadingValues(true);
    setFetchError(null);

    fetch('/api/v1/settings/template')
      .then((res) => (res.ok ? res.json() : null))
      .then((meta: TemplateMetadata | null) => {
        if (active && meta) setTemplateMeta(meta);
      })
      .catch((err) => console.warn('Failed to load template info:', err))
      .finally(() => {
        if (active) setLoadingTemplate(false);
      });

    fetchPreviewValues({ actualStartDate, actualEndDate, comparableStartDate, comparableEndDate })
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

  return { templateMeta, loadingTemplate, values, loadingValues, fetchError };
}

/** Calls `onClose` when Escape is pressed while the modal is open. */
function useCloseOnEscape(isOpen: boolean, onClose: () => void) {
  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);
}

/**
 * Previews the values injected into the management commentary template for the selected periods, and either
 * downloads the generated Word document or opens it in Report Studio.
 */
export const ManagementCommentaryModal: React.FC<ManagementCommentaryModalProps> = ({
  isOpen,
  onClose,
  selectedNode,
  report,
  ...periods
}) => {
  const preview = useCommentaryPreview(isOpen, periods);
  const [isExporting, setIsExporting] = useState(false);
  const [isOpeningInStudio, setIsOpeningInStudio] = useState(false);
  const [exportSuccess, setExportSuccess] = useState(false);
  useCloseOnEscape(isOpen, onClose);

  if (!isOpen) return null;

  const handleExportDocx = async () => {
    try {
      setIsExporting(true);
      const response = await instantiateReport(periods, 'download', 'Export failed');
      await downloadResponse(response, 'Management_Commentary.docx');
      setExportSuccess(true);
      setTimeout(() => setExportSuccess(false), 3000);
    } catch (err) {
      console.error('Failed to export DOCX:', err);
      alert('Failed to export Word document. Please check network or backend service.');
    } finally {
      setIsExporting(false);
    }
  };

  const handleOpenInStudio = async () => {
    try {
      setIsOpeningInStudio(true);
      const response = await instantiateReport(periods, 'open_in_studio', 'Instantiate failed');
      const result = (await response.json()) as { redirectUrl?: string };
      onClose();
      const queryParams = new URLSearchParams({
        actualStartDate: periods.actualStartDate,
        actualEndDate: periods.actualEndDate,
        comparableStartDate: periods.comparableStartDate || '',
        comparableEndDate: periods.comparableEndDate || '',
      });
      navigateTo(result.redirectUrl || `/report-studio?${queryParams.toString()}`);
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
        <ModalTopBar onClose={onClose} />

        <div className="flex-1 overflow-y-auto p-6 space-y-5 bg-[#f8fafc] custom-scrollbar">
          <TemplateStatusBanner templateMeta={preview.templateMeta} loadingTemplate={preview.loadingTemplate} />

          <div className="bg-white border border-slate-200/80 rounded-lg p-5 shadow-2xs space-y-4">
            <div className="flex items-center justify-between border-b border-slate-100 pb-2.5">
              <div className="flex items-center gap-2">
                <Calendar className="w-4 h-4 text-sky-600" />
                <h4 className="text-xs font-bold text-slate-800 uppercase tracking-wider">
                  Reporting Parameters &amp; Placeholder Injection Preview
                </h4>
              </div>
              {preview.loadingValues && (
                <div className="flex items-center gap-1.5 text-xs text-blue-600">
                  <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  <span>Executing AI &amp; SQL metrics calculation…</span>
                </div>
              )}
            </div>

            {preview.fetchError && (
              <div className="p-3 rounded-lg bg-rose-50 border border-rose-200 text-xs text-rose-700 flex items-start gap-2">
                <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
                <span>{preview.fetchError}</span>
              </div>
            )}

            {selectedNode && <SelectedNodeFocus node={selectedNode} />}

            <PlaceholderPreviewTable periods={periods} values={preview.values} loadingValues={preview.loadingValues} />

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

        <ModalFooter
          isExporting={isExporting}
          isOpeningInStudio={isOpeningInStudio}
          exportSuccess={exportSuccess}
          onClose={onClose}
          onOpenInStudio={() => void handleOpenInStudio()}
          onExport={() => void handleExportDocx()}
        />
      </div>
    </div>
  );
};

const ModalTopBar: React.FC<{ onClose: () => void }> = ({ onClose }) => (
  <div className="px-6 py-3.5 bg-slate-50 border-b border-slate-200 flex items-center justify-between shrink-0">
    <div className="flex items-center gap-2.5">
      <div className="w-8 h-8 rounded-lg bg-blue-100 text-blue-700 flex items-center justify-center">
        <FileText className="w-4 h-4" />
      </div>
      <div>
        <h3 className="text-sm font-bold text-slate-800 leading-none">Generate Management Commentary Report</h3>
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
          navigateTo('/settings');
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
);

const TemplateStatusBanner: React.FC<{ templateMeta: TemplateMetadata | null; loadingTemplate: boolean }> = ({
  templateMeta,
  loadingTemplate,
}) => (
  <div className="bg-white border border-slate-200/80 rounded-lg p-4 shadow-2xs">
    <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
      <div className="flex items-center gap-3">
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-700 border border-slate-200/80">
          <FileCheck className="h-4 w-4 text-sky-600" />
        </div>
        <div>
          <div className="flex items-center gap-2">
            <span className="text-xs font-bold text-slate-800">Active Master Template:</span>
            <span className="text-xs font-mono text-slate-800 font-medium">
              {loadingTemplate ? 'Loading template…' : templateMeta?.filename || 'master-template.docx'}
            </span>
            {templateMeta && (
              <span
                className={`px-1.5 py-0.5 rounded text-[10px] font-medium ${
                  templateMeta.isCustom
                    ? 'bg-purple-50 text-purple-700 border border-purple-200/80'
                    : 'bg-slate-100 text-slate-600 border border-slate-200/80'
                }`}
              >
                {templateMeta.isCustom ? 'Custom Template' : 'System Standard Master'}
              </span>
            )}
          </div>
          <div className="text-[11px] text-slate-500 mt-0.5">
            Upon generation, the system clones a fresh copy of this template from the Data Lake and populates analysis
            metrics into content controls.
          </div>
        </div>
      </div>

      <div className="flex items-center gap-2 shrink-0">
        <span className="inline-flex items-center gap-1 px-2.5 py-1 bg-emerald-50/70 text-emerald-800 border border-emerald-200/80 rounded-md text-[11px] font-semibold">
          <CheckCircle2 className="w-3.5 h-3.5 text-emerald-600" />
          {templateMeta
            ? `${templateMeta.matchedCount}/${templateMeta.totalExpected} Core Placeholders Ready`
            : 'Checking placeholders...'}
        </span>
      </div>
    </div>
  </div>
);

const FocusMetric: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div className="bg-white p-2 rounded border border-slate-200">
    <span className="text-slate-400 block text-[10px]">{label}</span>
    {children}
  </div>
);

/** Planned and actual figures for the component selected on the analysis page. */
const SelectedNodeFocus: React.FC<{ node: DualBomNode }> = ({ node }) => (
  <div className="bg-slate-50 border border-slate-200 rounded-md p-3 text-xs space-y-1.5">
    <div className="flex items-center justify-between font-mono text-[11px]">
      <span className="font-bold text-slate-800">
        Inspected Component Focus: {node.name} ({node.id})
      </span>
      <span className="text-slate-500">
        ECN: {node.ecn} · Station: {node.station}
      </span>
    </div>
    <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 pt-1 font-mono text-[11px]">
      <FocusMetric label="Std Quantity">
        <span className="font-semibold text-slate-800">{formatQty(node.standardQty)}</span>
      </FocusMetric>
      <FocusMetric label="Actual Issued">
        <span className="font-semibold text-slate-800">
          {formatQty(node.actualQty)}{' '}
          <span className="text-[10px] text-slate-500">
            ({node.quantityDeltaPercent > 0 ? '+' : ''}
            {node.quantityDeltaPercent.toFixed(1)}%)
          </span>
        </span>
      </FocusMetric>
      <FocusMetric label="Baseline Cost">
        <span className="font-semibold text-slate-800">{formatCurrency(node.baselineCost)}</span>
      </FocusMetric>
      <FocusMetric label="Net Variance">
        <span className={`font-bold ${(node.costDelta ?? 0) > 0 ? 'text-rose-600' : 'text-emerald-600'}`}>
          {formatCurrency(node.costDelta)}
        </span>
      </FocusMetric>
    </div>
  </div>
);

interface PreviewRow {
  placeholder: string;
  description: string;
  /** Plain-text source, or an AI-assisted source shown with a sparkle icon. */
  source: { text: string; ai: boolean };
  valueClassName: string;
  value: (values: PlaceholderValues | null, periods: ReportPeriods, loading: boolean) => string;
}

const calculated = (loading: boolean, value: string | undefined, fallback: string) =>
  loading ? 'Calculating…' : value || fallback;

const PREVIEW_ROWS: PreviewRow[] = [
  {
    placeholder: 'Current Period',
    description: 'Current reporting duration',
    source: { text: 'Analysis page context', ai: false },
    valueClassName: 'font-medium text-slate-800',
    value: (values, p) => values?.currentPeriod || `${p.actualStartDate} to ${p.actualEndDate}`,
  },
  {
    placeholder: 'Comparison Period',
    description: 'Comparable baseline duration',
    source: { text: 'Analysis page context', ai: false },
    valueClassName: 'font-medium text-slate-800',
    value: (values, p) => values?.comparisonPeriod || `${p.comparableStartDate} to ${p.comparableEndDate}`,
  },
  {
    placeholder: 'Revenue',
    description: 'Gross sales revenue cleared',
    source: { text: 'sales_details', ai: true },
    valueClassName: 'font-bold text-slate-900',
    value: (values, _, loading) => calculated(loading, values?.revenue, 'N/A'),
  },
  {
    placeholder: 'Revenue Change %',
    description: 'Period-over-period revenue growth',
    source: { text: 'AI (Comparable analysis)', ai: true },
    valueClassName: 'font-bold text-slate-900',
    value: (values, _, loading) => calculated(loading, values?.revenueChangePercent, '0.0%'),
  },
  {
    placeholder: 'Gross Margin',
    description: 'Total gross margin contribution',
    source: { text: 'AI (Revenue minus Cost)', ai: true },
    valueClassName: 'font-bold text-emerald-600',
    value: (values, _, loading) => calculated(loading, values?.grossMargin, '$0.00'),
  },
  {
    placeholder: 'Gross Margin %',
    description: 'Effective gross margin rate',
    source: { text: 'AI (Margin rate model)', ai: true },
    valueClassName: 'font-bold text-emerald-600',
    value: (values, _, loading) => calculated(loading, values?.grossMarginPercent, '0.0%'),
  },
];

interface PlaceholderPreviewTableProps {
  periods: ReportPeriods;
  values: PlaceholderValues | null;
  loadingValues: boolean;
}

/** Lists the six template placeholders, where each value comes from, and the value that will be injected. */
const PlaceholderPreviewTable: React.FC<PlaceholderPreviewTableProps> = ({ periods, values, loadingValues }) => (
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
        {PREVIEW_ROWS.map((row) => (
          <tr key={row.placeholder} className="hover:bg-slate-50/50">
            <td className="py-2.5 px-3 font-mono font-semibold text-blue-700">{row.placeholder}</td>
            <td className="py-2.5 px-3 text-slate-600">{row.description}</td>
            <td className="py-2.5 px-3 text-slate-500">
              {row.source.ai ? (
                <span className="inline-flex items-center gap-1 text-[11px] text-indigo-700">
                  <Sparkles className="w-3 h-3 text-indigo-500" />
                  {row.source.text}
                </span>
              ) : (
                row.source.text
              )}
            </td>
            <td className={`py-2.5 px-3 text-right font-mono ${row.valueClassName}`}>
              {row.value(values, periods, loadingValues)}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  </div>
);

interface ModalFooterProps {
  isExporting: boolean;
  isOpeningInStudio: boolean;
  exportSuccess: boolean;
  onClose: () => void;
  onOpenInStudio: () => void;
  onExport: () => void;
}

const ModalFooter: React.FC<ModalFooterProps> = ({
  isExporting,
  isOpeningInStudio,
  exportSuccess,
  onClose,
  onOpenInStudio,
  onExport,
}) => (
  <div className="px-6 py-3.5 bg-[#f8fafc] border-t border-slate-200/80 flex flex-col sm:flex-row sm:items-center justify-between gap-3 text-xs shrink-0">
    <div className="text-slate-500 text-[11px] flex items-center gap-1.5">
      <span className="w-2 h-2 rounded-full bg-emerald-500"></span>
      <span>Ready: Document generation clones the Data Lake master template and preserves all corporate styling.</span>
    </div>

    <div className="flex items-center gap-2">
      <button
        type="button"
        onClick={onClose}
        className="px-3.5 py-1.5 bg-white hover:bg-slate-50 text-slate-700 border border-slate-300/80 rounded-md font-medium transition cursor-pointer shadow-2xs"
      >
        Cancel
      </button>

      <button
        type="button"
        onClick={onOpenInStudio}
        disabled={isOpeningInStudio || isExporting}
        className="inline-flex items-center gap-1.5 px-3.5 py-1.5 bg-slate-100 hover:bg-slate-200/80 text-slate-800 border border-slate-300/80 rounded-md font-semibold transition shadow-2xs disabled:opacity-60 cursor-pointer"
      >
        {isOpeningInStudio ? (
          <Loader2 className="w-3.5 h-3.5 animate-spin" />
        ) : (
          <Edit3 className="w-3.5 h-3.5 text-slate-600" />
        )}
        <span>{isOpeningInStudio ? 'Loading into Studio…' : 'Edit in Report Studio'}</span>
      </button>

      <button
        type="button"
        onClick={onExport}
        disabled={isExporting || isOpeningInStudio}
        className="inline-flex items-center gap-1.5 px-4 py-1.5 bg-slate-900 hover:bg-slate-800 active:bg-slate-950 text-white rounded-md font-semibold transition shadow-xs disabled:opacity-60 cursor-pointer border border-slate-800"
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
);
