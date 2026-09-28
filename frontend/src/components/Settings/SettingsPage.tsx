import React, { FormEvent, useEffect, useState } from 'react';
import {
  AlertCircle,
  CheckCircle2,
  Cpu,
  Download,
  FileCheck,
  FileText,
  LoaderCircle,
  PlugZap,
  RotateCcw,
  Save,
  Server,
  Upload,
  XCircle,
} from 'lucide-react';

type AiSettings = {
  host: string;
  port: number;
  model: string;
  baseUrl: string;
  updatedAt: string | null;
  persisted: boolean;
};

type ConnectionTest = {
  reachable: boolean;
  modelReady: boolean;
  latencyMs: number;
  message: string;
};

type Notice = {
  success: boolean;
  message: string;
};

export type PlaceholderItem = {
  key: string;
  label: string;
  detected: boolean;
  samplePreview: string;
};

export type TemplateMetadata = {
  filename: string;
  sizeBytes: number;
  updatedAt: string;
  isCustom: boolean;
  detectedPlaceholders: PlaceholderItem[];
  matchedCount: number;
  totalExpected: number;
  version: number;
};

const SETTINGS_ENDPOINT = '/api/v1/settings/ai';
const TEMPLATE_ENDPOINT = '/api/v1/settings/template';

const readError = async (response: Response) => {
  try {
    const body = await response.json() as { message?: string };
    return body.message || 'Request failed (HTTP ' + response.status + ')';
  } catch {
    return 'Request failed (HTTP ' + response.status + ')';
  }
};

export const SettingsPage: React.FC = () => {
  const [host, setHost] = useState('');
  const [port, setPort] = useState('8081');
  const [model, setModel] = useState('local');
  const [activeUrl, setActiveUrl] = useState('');
  const [persisted, setPersisted] = useState(false);
  const [loading, setLoading] = useState(true);
  const [action, setAction] = useState<'test' | 'save' | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);

  // Template management state
  const [templateMeta, setTemplateMeta] = useState<TemplateMetadata | null>(null);
  const [templateLoading, setTemplateLoading] = useState(true);
  const [templateUploading, setTemplateUploading] = useState(false);
  const [templateNotice, setTemplateNotice] = useState<Notice | null>(null);

  const loadTemplate = async () => {
    try {
      setTemplateLoading(true);
      const res = await fetch(TEMPLATE_ENDPOINT);
      if (!res.ok) throw new Error(await readError(res));
      const data = await res.json() as TemplateMetadata;
      setTemplateMeta(data);
    } catch (err) {
      console.warn('Failed to load template metadata:', err);
    } finally {
      setTemplateLoading(false);
    }
  };

  useEffect(() => {
    loadTemplate();
  }, []);

  const handleUploadTemplate = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!file.name.toLowerCase().endsWith('.docx')) {
      setTemplateNotice({ success: false, message: 'Only Microsoft Word (.docx) files are supported' });
      return;
    }

    setTemplateUploading(true);
    setTemplateNotice(null);

    const formData = new FormData();
    formData.append('file', file);

    try {
      const res = await fetch(TEMPLATE_ENDPOINT + '/upload', {
        method: 'POST',
        body: formData,
      });
      if (!res.ok) throw new Error(await readError(res));
      const data = await res.json() as TemplateMetadata;
      setTemplateMeta(data);
      setTemplateNotice({
        success: true,
        message: `Template "${data.filename}" successfully uploaded to Data Lake (${data.matchedCount}/${data.totalExpected} placeholders detected).`,
      });
    } catch (err) {
      setTemplateNotice({
        success: false,
        message: err instanceof Error ? err.message : 'Failed to upload template',
      });
    } finally {
      setTemplateUploading(false);
      // Reset input value so same file can be selected again
      e.target.value = '';
    }
  };

  const handleDownloadTemplate = () => {
    window.location.href = TEMPLATE_ENDPOINT + '/download';
  };

  const handleResetTemplate = async () => {
    if (!window.confirm('Reset template to system built-in master template?')) return;
    setTemplateUploading(true);
    setTemplateNotice(null);
    try {
      const res = await fetch(TEMPLATE_ENDPOINT + '/reset', { method: 'POST' });
      if (!res.ok) throw new Error(await readError(res));
      const data = await res.json() as TemplateMetadata;
      setTemplateMeta(data);
      setTemplateNotice({
        success: true,
        message: 'Successfully reset to system default master template.',
      });
    } catch (err) {
      setTemplateNotice({
        success: false,
        message: err instanceof Error ? err.message : 'Failed to reset template',
      });
    } finally {
      setTemplateUploading(false);
    }
  };

  useEffect(() => {
    let active = true;
    fetch(SETTINGS_ENDPOINT)
      .then(async (response) => {
        if (!response.ok) throw new Error(await readError(response));
        return response.json() as Promise<AiSettings>;
      })
      .then((settings) => {
        if (!active) return;
        setHost(settings.host);
        setPort(String(settings.port));
        setModel(settings.model);
        setActiveUrl(settings.baseUrl);
        setPersisted(settings.persisted);
      })
      .catch((error: Error) => {
        if (active) setNotice({ success: false, message: error.message });
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => { active = false; };
  }, []);

  const requestBody = () => ({
    host: host.trim(),
    port: Number(port),
    model: model.trim(),
  });

  const testConnection = async () => {
    setAction('test');
    setNotice(null);
    try {
      const response = await fetch(SETTINGS_ENDPOINT + '/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(requestBody()),
      });
      if (!response.ok) throw new Error(await readError(response));
      const result = await response.json() as ConnectionTest;
      setNotice({
        success: result.reachable && result.modelReady,
        message: result.message + ' (' + result.latencyMs + ' ms)',
      });
    } catch (error) {
      setNotice({
        success: false,
        message: error instanceof Error ? error.message : 'Connection test failed',
      });
    } finally {
      setAction(null);
    }
  };

  const saveSettings = async (event: FormEvent) => {
    event.preventDefault();
    setAction('save');
    setNotice(null);
    try {
      const response = await fetch(SETTINGS_ENDPOINT, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(requestBody()),
      });
      if (!response.ok) throw new Error(await readError(response));
      const settings = await response.json() as AiSettings;
      setHost(settings.host);
      setPort(String(settings.port));
      setModel(settings.model);
      setActiveUrl(settings.baseUrl);
      setPersisted(settings.persisted);
      setNotice({
        success: true,
        message: 'Settings saved. New AI requests now use this connection.',
      });
    } catch (error) {
      setNotice({
        success: false,
        message: error instanceof Error ? error.message : 'Unable to save settings',
      });
    } finally {
      setAction(null);
    }
  };

  const disabled = loading || action !== null;
  const incomplete = !host.trim() || !port || !model.trim();

  return (
    <main className="flex-1 overflow-y-auto bg-[#f6f8fa] p-6 custom-scrollbar">
      <div className="mx-auto max-w-3xl space-y-5">
        <div className="border-b border-slate-200/80 pb-3">
          <h2 className="text-base font-bold text-slate-800">
            Settings &amp; Parameters
          </h2>
          <p className="mt-0.5 text-xs text-slate-400">
            Configure the llama.cpp connection used by all LangChain4j AI services.
          </p>
        </div>

        <section className="overflow-hidden rounded-xl border border-slate-200/80 bg-white shadow-2xs">
          <div className="flex items-start gap-3 border-b border-slate-100 px-5 py-4">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-slate-200/80 bg-slate-100 text-slate-700">
              <Cpu className="h-4 w-4 text-sky-600" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-slate-800">
                llama.cpp connection
              </h3>
              <p className="mt-1 text-xs leading-5 text-slate-500">
                This address must be reachable from the backend process or container.
                Testing does not save the entered values.
              </p>
            </div>
          </div>

          <form onSubmit={saveSettings} className="space-y-5 p-5">
            <div className="grid gap-4 sm:grid-cols-[1fr_150px]">
              <label className="space-y-1.5">
                <span className="flex items-center gap-1.5 text-xs font-semibold text-slate-700">
                  <Server className="h-3.5 w-3.5" />
                  IP address / hostname
                </span>
                <input
                  value={host}
                  onChange={(event) => setHost(event.target.value)}
                  placeholder="host.docker.internal"
                  autoComplete="off"
                  disabled={disabled}
                  className="w-full rounded-lg border border-slate-300/80 bg-white px-3 py-2.5 font-mono text-sm text-slate-800 outline-none transition focus:border-slate-600 focus:ring-1 focus:ring-slate-300 disabled:bg-[#f8fafc] shadow-2xs"
                />
              </label>

              <label className="space-y-1.5">
                <span className="text-xs font-semibold text-slate-700">Port</span>
                <input
                  type="number"
                  min={1}
                  max={65535}
                  value={port}
                  onChange={(event) => setPort(event.target.value)}
                  disabled={disabled}
                  className="w-full rounded-lg border border-slate-300/80 bg-white px-3 py-2.5 font-mono text-sm text-slate-800 outline-none transition focus:border-slate-600 focus:ring-1 focus:ring-slate-300 disabled:bg-[#f8fafc] shadow-2xs"
                />
              </label>
            </div>

            <label className="block space-y-1.5">
              <span className="text-xs font-semibold text-slate-700">
                Model name
              </span>
              <input
                value={model}
                onChange={(event) => setModel(event.target.value)}
                placeholder="local"
                autoComplete="off"
                disabled={disabled}
                className="w-full rounded-lg border border-slate-300/80 bg-white px-3 py-2.5 font-mono text-sm text-slate-800 outline-none transition focus:border-slate-600 focus:ring-1 focus:ring-slate-300 disabled:bg-[#f8fafc] shadow-2xs"
              />
              <span className="block text-[11px] text-slate-400">
                Must match the alias advertised by llama.cpp.
              </span>
            </label>

            <div className="rounded-lg border border-slate-200/80 bg-[#f8fafc] px-3 py-2 text-[11px] text-slate-500">
              <div>
                <span className="font-semibold text-slate-600">Active URL: </span>
                <span className="font-mono">
                  {loading ? 'Loading…' : activeUrl || 'Unavailable'}
                </span>
              </div>
              <div className="mt-1">
                Source: {persisted ? 'PostgreSQL settings' : 'LLAMA_BASE_URL default'}
              </div>
            </div>

            {notice && (
              <div
                role="status"
                className={'flex items-start gap-2 rounded-lg border px-3 py-2.5 text-xs ' + (
                  notice.success
                    ? 'border-emerald-200/80 bg-emerald-50/70 text-emerald-800'
                    : 'border-amber-200/80 bg-amber-50/70 text-amber-800'
                )}
              >
                {notice.success
                  ? <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-600" />
                  : <XCircle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" />}
                <span>{notice.message}</span>
              </div>
            )}

            <div className="flex justify-end gap-2 border-t border-slate-100 pt-4">
              <button
                type="button"
                onClick={testConnection}
                disabled={disabled || incomplete}
                className="inline-flex items-center gap-2 rounded-lg border border-slate-300/80 bg-white px-3.5 py-2 text-xs font-semibold text-slate-700 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50 shadow-2xs"
              >
                {action === 'test'
                  ? <LoaderCircle className="h-4 w-4 animate-spin" />
                  : <PlugZap className="h-4 w-4" />}
                Test connection
              </button>
              <button
                type="submit"
                disabled={disabled || incomplete}
                className="inline-flex items-center gap-2 rounded-lg bg-slate-900 px-3.5 py-2 text-xs font-semibold text-white transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-50 shadow-xs border border-slate-800"
              >
                {action === 'save'
                  ? <LoaderCircle className="h-4 w-4 animate-spin" />
                  : <Save className="h-4 w-4" />}
                Save settings
              </button>
            </div>
          </form>
        </section>

        {/* Management Report Template Section */}
        <section className="overflow-hidden rounded-xl border border-slate-200/80 bg-white shadow-2xs">
          <div className="flex items-start gap-3 border-b border-slate-100 px-5 py-4">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-slate-200/80 bg-slate-100 text-slate-700">
              <FileText className="h-4 w-4 text-sky-600" />
            </div>
            <div className="flex-1">
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-bold text-slate-800">
                  Management Report Template
                </h3>
                {templateMeta && (
                  <span className={`inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium ${
                    templateMeta.isCustom ? 'bg-purple-50 text-purple-700 border border-purple-200/80' : 'bg-slate-100 text-slate-600 border border-slate-200/80'
                  }`}>
                    {templateMeta.isCustom ? 'Custom Template' : 'Built-in System Master'}
                  </span>
                )}
              </div>
              <p className="mt-1 text-xs leading-5 text-slate-500">
                Configure the Word (.docx) master template used to instantiate management commentary reports. The template is persisted in the MinIO Data Lake. Each report generation clones this master and injects analysis data.
              </p>
            </div>
          </div>

          <div className="p-5 space-y-4">
            {/* Template Info Card */}
            <div className="rounded-lg border border-slate-200/80 bg-[#f8fafc] p-4">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
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

                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={handleDownloadTemplate}
                    disabled={templateLoading}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-300/80 bg-white text-xs font-semibold text-slate-700 hover:bg-slate-50 transition shadow-2xs disabled:opacity-50 cursor-pointer"
                  >
                    <Download className="h-3.5 w-3.5 text-slate-500" />
                    Download Master
                  </button>

                  <label className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 text-xs font-semibold text-white transition shadow-xs cursor-pointer border border-slate-800">
                    {templateUploading ? (
                      <LoaderCircle className="h-3.5 w-3.5 animate-spin" />
                    ) : (
                      <Upload className="h-3.5 w-3.5" />
                    )}
                    <span>{templateUploading ? 'Uploading…' : 'Upload New Master (.docx)'}</span>
                    <input
                      type="file"
                      accept=".docx"
                      onChange={handleUploadTemplate}
                      disabled={templateUploading}
                      className="hidden"
                    />
                  </label>

                  {templateMeta?.isCustom && (
                    <button
                      type="button"
                      onClick={handleResetTemplate}
                      disabled={templateUploading}
                      title="Reset to built-in system default template"
                      className="p-1.5 text-slate-400 hover:text-slate-600 hover:bg-slate-200 rounded-md transition cursor-pointer"
                    >
                      <RotateCcw className="h-4 w-4" />
                    </button>
                  )}
                </div>
              </div>

              {/* Placeholders Health Check */}
              <div className="mt-4 pt-3 border-t border-slate-200/80">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-xs font-bold text-slate-700">
                    Core Placeholder Readiness
                  </span>
                  <span className="text-xs font-semibold text-slate-600">
                    {templateMeta ? `${templateMeta.matchedCount} / ${templateMeta.totalExpected} Ready` : 'Checking…'}
                  </span>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-2">
                  {templateMeta?.detectedPlaceholders.map((item) => (
                    <div
                      key={item.key}
                      className={`flex items-start gap-2 p-2 rounded border text-xs ${
                        item.detected ? 'bg-emerald-50/60 border-emerald-200 text-emerald-900' : 'bg-slate-100 border-slate-200 text-slate-500'
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
                  ))}
                </div>
              </div>
            </div>

            {templateNotice && (
              <div
                role="status"
                className={`flex items-start gap-2 rounded-lg border px-3 py-2.5 text-xs ${
                  templateNotice.success ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-amber-200 bg-amber-50 text-amber-800'
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
          </div>
        </section>

        <p className="px-1 text-[11px] leading-5 text-slate-400">
          In Docker, 127.0.0.1 points to the backend container itself.
          Use host.docker.internal or a network-reachable llama.cpp host.
        </p>
      </div>
    </main>
  );
};
