import React, { useEffect, useState } from 'react';
import {
  AlertCircle,
  CheckCircle2,
  Download,
  FileCheck,
  FileText,
  LoaderCircle,
  RotateCcw,
  Upload,
  XCircle,
} from 'lucide-react';
import {
  fetchTemplate,
  PlaceholderItem,
  resetTemplate,
  TEMPLATE_DOWNLOAD_URL,
  TemplateMetadata,
  uploadTemplate,
} from './settingsApi';
import { SettingsSection } from './SettingsSection';

type Notice = {
  success: boolean;
  message: string;
};

/**
 * Loads the management-report master template metadata on mount and uploads or resets the template.
 * Only `.docx` files are accepted; other files are rejected locally without a request.
 */
function useReportTemplate() {
  const [templateMeta, setTemplateMeta] = useState<TemplateMetadata | null>(null);
  const [templateLoading, setTemplateLoading] = useState(true);
  const [templateUploading, setTemplateUploading] = useState(false);
  const [templateNotice, setTemplateNotice] = useState<Notice | null>(null);

  useEffect(() => {
    fetchTemplate()
      .then(setTemplateMeta)
      .catch((err) => console.warn('Failed to load template metadata:', err))
      .finally(() => setTemplateLoading(false));
  }, []);

  const replaceTemplate = async (
    task: () => Promise<TemplateMetadata>,
    success: (data: TemplateMetadata) => string,
    failure: string,
  ) => {
    setTemplateUploading(true);
    setTemplateNotice(null);
    try {
      const data = await task();
      setTemplateMeta(data);
      setTemplateNotice({ success: true, message: success(data) });
    } catch (err) {
      setTemplateNotice({ success: false, message: err instanceof Error ? err.message : failure });
    } finally {
      setTemplateUploading(false);
    }
  };

  const handleUploadTemplate = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!file.name.toLowerCase().endsWith('.docx')) {
      setTemplateNotice({ success: false, message: 'Only Microsoft Word (.docx) files are supported' });
      return;
    }
    await replaceTemplate(
      () => uploadTemplate(file),
      (data) =>
        `Template "${data.filename}" successfully uploaded to Data Lake (${data.matchedCount}/${data.totalExpected} placeholders detected).`,
      'Failed to upload template',
    );
    // Reset input value so same file can be selected again
    e.target.value = '';
  };

  const handleResetTemplate = async () => {
    if (!window.confirm('Reset template to system built-in master template?')) return;
    await replaceTemplate(
      resetTemplate,
      () => 'Successfully reset to system default master template.',
      'Failed to reset template',
    );
  };

  return {
    templateMeta,
    templateLoading,
    templateUploading,
    templateNotice,
    handleUploadTemplate,
    handleResetTemplate,
  };
}

/** Settings section for the Word master template that management commentary reports are created from. */
export const ReportTemplateSettings: React.FC = () => {
  const template = useReportTemplate();
  const { templateMeta, templateLoading, templateNotice } = template;

  return (
    <SettingsSection
      contentId="template-settings-content"
      icon={<FileText className="h-4 w-4 text-sky-600" />}
      title="Management Report Template"
      badge={
        templateMeta && (
          <span
            className={`inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium ${
              templateMeta.isCustom
                ? 'bg-purple-50 text-purple-700 border border-purple-200/80'
                : 'bg-slate-100 text-slate-600 border border-slate-200/80'
            }`}
          >
            {templateMeta.isCustom ? 'Custom Template' : 'Built-in System Master'}
          </span>
        )
      }
      description="Configure the Word (.docx) master template used to instantiate management commentary reports. The template is persisted in the MinIO Data Lake. Each report generation clones this master and injects analysis data."
      summary={
        <>
          {templateLoading ? 'Loading template…' : templateMeta?.filename || 'Template unavailable'}
          {templateNotice ? ` · ${templateNotice.message}` : ''}
        </>
      }
      contentClassName="p-5 space-y-4"
    >
      <div className="rounded-lg border border-slate-200/80 bg-[#f8fafc] p-4">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <TemplateFileInfo templateMeta={templateMeta} templateLoading={templateLoading} />
          <TemplateActions
            canReset={Boolean(templateMeta?.isCustom)}
            templateLoading={templateLoading}
            templateUploading={template.templateUploading}
            onUpload={(e) => void template.handleUploadTemplate(e)}
            onReset={() => void template.handleResetTemplate()}
          />
        </div>
        <PlaceholderReadiness templateMeta={templateMeta} />
      </div>

      {templateNotice && (
        <div
          role="status"
          className={`flex items-start gap-2 rounded-lg border px-3 py-2.5 text-xs ${
            templateNotice.success
              ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
              : 'border-amber-200 bg-amber-50 text-amber-800'
          }`}
        >
          {templateNotice.success ? (
            <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" />
          ) : (
            <XCircle className="mt-0.5 h-4 w-4 shrink-0" />
          )}
          <span>{templateNotice.message}</span>
        </div>
      )}
    </SettingsSection>
  );
};

const TemplateFileInfo: React.FC<{ templateMeta: TemplateMetadata | null; templateLoading: boolean }> = ({
  templateMeta,
  templateLoading,
}) => (
  <div className="flex items-center gap-3">
    <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-700 border border-slate-200/80">
      <FileCheck className="h-5 w-5 text-sky-600" />
    </div>
    <div>
      <div className="text-sm font-semibold text-slate-800 flex items-center gap-2">
        <span>{templateLoading ? 'Loading template…' : templateMeta?.filename || 'master-template.docx'}</span>
        {templateMeta && (
          <span className="text-[11px] font-normal text-slate-400">
            ({(templateMeta.sizeBytes / 1024).toFixed(1)} KB)
          </span>
        )}
      </div>
      <div className="text-xs text-slate-500 mt-0.5 font-mono">
        Last updated: {templateMeta?.updatedAt ? new Date(templateMeta.updatedAt).toLocaleString() : '—'}
      </div>
    </div>
  </div>
);

interface TemplateActionsProps {
  canReset: boolean;
  templateLoading: boolean;
  templateUploading: boolean;
  onUpload: (e: React.ChangeEvent<HTMLInputElement>) => void;
  onReset: () => void;
}

const TemplateActions: React.FC<TemplateActionsProps> = ({
  canReset,
  templateLoading,
  templateUploading,
  onUpload,
  onReset,
}) => (
  <div className="flex items-center gap-2">
    <button
      type="button"
      onClick={() => {
        window.location.href = TEMPLATE_DOWNLOAD_URL;
      }}
      disabled={templateLoading}
      className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-300/80 bg-white text-xs font-semibold text-slate-700 hover:bg-slate-50 transition shadow-2xs disabled:opacity-50 cursor-pointer"
    >
      <Download className="h-3.5 w-3.5 text-slate-500" />
      Download Master
    </button>

    <label className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 text-xs font-semibold text-white transition shadow-xs cursor-pointer border border-slate-800">
      {templateUploading ? <LoaderCircle className="h-3.5 w-3.5 animate-spin" /> : <Upload className="h-3.5 w-3.5" />}
      <span>{templateUploading ? 'Uploading…' : 'Upload New Master (.docx)'}</span>
      <input type="file" accept=".docx" onChange={onUpload} disabled={templateUploading} className="hidden" />
    </label>

    {canReset && (
      <button
        type="button"
        onClick={onReset}
        disabled={templateUploading}
        title="Reset to built-in system default template"
        className="p-1.5 text-slate-400 hover:text-slate-600 hover:bg-slate-200 rounded-md transition cursor-pointer"
      >
        <RotateCcw className="h-4 w-4" />
      </button>
    )}
  </div>
);

const PlaceholderReadiness: React.FC<{ templateMeta: TemplateMetadata | null }> = ({ templateMeta }) => (
  <div className="mt-4 pt-3 border-t border-slate-200/80">
    <div className="flex items-center justify-between mb-2">
      <span className="text-xs font-bold text-slate-700">Core Placeholder Readiness</span>
      <span className="text-xs font-semibold text-slate-600">
        {templateMeta ? `${templateMeta.matchedCount} / ${templateMeta.totalExpected} Ready` : 'Checking…'}
      </span>
    </div>
    <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-2">
      {templateMeta?.detectedPlaceholders.map((item) => <PlaceholderStatus key={item.key} item={item} />)}
    </div>
  </div>
);

const PlaceholderStatus: React.FC<{ item: PlaceholderItem }> = ({ item }) => (
  <div
    className={`flex items-start gap-2 p-2 rounded border text-xs ${
      item.detected
        ? 'bg-emerald-50/60 border-emerald-200 text-emerald-900'
        : 'bg-slate-100 border-slate-200 text-slate-500'
    }`}
  >
    {item.detected ? (
      <CheckCircle2 className="h-3.5 w-3.5 text-emerald-600 shrink-0 mt-0.5" />
    ) : (
      <AlertCircle className="h-3.5 w-3.5 text-amber-500 shrink-0 mt-0.5" />
    )}
    <div className="min-w-0">
      <div className="font-semibold truncate">{item.key}</div>
      <div className="text-[10px] text-slate-500">{item.label}</div>
    </div>
  </div>
);
