import React, { ChangeEvent, useCallback, useEffect, useRef, useState } from 'react';
import { AlertCircle, LoaderCircle, RefreshCw, Upload } from 'lucide-react';
import { DocumentEditor } from '@onlyoffice/document-editor-react';
import type { Config } from '@onlyoffice/doceditor-types';

interface OnlyOfficeEditorConfigResponse {
  documentServerUrl: string;
  config: Config;
}

const CONFIG_ENDPOINT = '/api/report-studio/onlyoffice/config';
const DOCUMENT_ENDPOINT = '/api/report-studio/onlyoffice/document';

interface OnlyOfficeEditorPaneProps {
  onDocumentChanged?: () => void;
  reloadKey?: number;
}

export const OnlyOfficeEditorPane: React.FC<OnlyOfficeEditorPaneProps> = ({
  onDocumentChanged,
  reloadKey = 0,
}) => {
  const [editorConfig, setEditorConfig] = useState<OnlyOfficeEditorConfigResponse>();
  const [editorRevision, setEditorRevision] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [isUploading, setIsUploading] = useState(false);
  const [error, setError] = useState<string>();
  const [uploadError, setUploadError] = useState<string>();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const loadConfig = useCallback(async () => {
    setIsLoading(true);
    setError(undefined);

    try {
      const response = await fetch(CONFIG_ENDPOINT, {
        headers: { Accept: 'application/json' },
      });

      if (!response.ok) {
        throw new Error(`Configuration request failed (${response.status})`);
      }

      const payload = (await response.json()) as OnlyOfficeEditorConfigResponse;
      if (!payload.documentServerUrl || !payload.config) {
        throw new Error('The ONLYOFFICE configuration response is incomplete');
      }

      setEditorConfig(payload);
      setEditorRevision((current) => current + 1);
    } catch (requestError) {
      setError(
        requestError instanceof Error
          ? requestError.message
          : 'Unable to load the ONLYOFFICE configuration'
      );
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadConfig();
  }, [loadConfig, reloadKey]);

  const handleDocumentSelected = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;

    setIsUploading(true);
    setUploadError(undefined);

    try {
      const body = new FormData();
      body.append('file', file);
      const response = await fetch(DOCUMENT_ENDPOINT, {
        method: 'POST',
        body,
      });

      if (!response.ok) {
        const problem = (await response.json().catch(() => undefined)) as
          | { detail?: string; message?: string }
          | undefined;
        throw new Error(
          problem?.detail ?? problem?.message ?? `Document upload failed (${response.status})`
        );
      }

      await loadConfig();
      onDocumentChanged?.();
    } catch (requestError) {
      setUploadError(
        requestError instanceof Error ? requestError.message : 'Unable to open the DOCX document'
      );
    } finally {
      setIsUploading(false);
    }
  };

  if (isLoading) {
    return (
      <div className="flex h-full items-center justify-center" style={{ backgroundColor: "#f3f3f3" }}>
        <div className="flex items-center gap-2 text-sm text-slate-500">
          <LoaderCircle className="h-5 w-5 animate-spin text-blue-600" />
          <span>Loading ONLYOFFICE editor…</span>
        </div>
      </div>
    );
  }

  if (error || !editorConfig) {
    return (
      <div className="flex h-full items-center justify-center p-8" style={{ backgroundColor: "#f3f3f3" }}>
        <div className="w-full max-w-lg rounded-xl border border-amber-200 bg-white p-6 shadow-sm">
          <div className="flex items-start gap-3">
            <AlertCircle className="mt-0.5 h-5 w-5 shrink-0 text-amber-600" />
            <div className="space-y-3">
              <div>
                <h2 className="text-sm font-bold text-slate-900">ONLYOFFICE is not configured yet</h2>
                <p className="mt-1 text-xs leading-relaxed text-slate-600">
                  The editor needs a signed configuration from <code>{CONFIG_ENDPOINT}</code>.
                  The backend endpoint and Document Server will be connected in the next integration step.
                </p>
              </div>
              {error && (
                <p className="rounded-md bg-amber-50 px-2.5 py-2 font-mono text-[11px] text-amber-800">
                  {error}
                </p>
              )}
              <button
                type="button"
                onClick={() => void loadConfig()}
                className="inline-flex items-center gap-1.5 rounded-md bg-slate-800 px-3 py-1.5 text-xs font-semibold text-white hover:bg-slate-900"
              >
                <RefreshCw className="h-3.5 w-3.5" />
                Retry connection
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col" style={{ backgroundColor: "#f3f3f3" }}>
      <div className="flex h-10 shrink-0 items-center justify-between border-b border-slate-200 px-4" style={{ backgroundColor: "#f3f3f3" }}>
        <div className="flex items-center gap-2 text-xs font-semibold text-slate-700">
          <input
            ref={fileInputRef}
            type="file"
            accept=".docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            onChange={(event) => void handleDocumentSelected(event)}
            className="hidden"
          />
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            disabled={isUploading}
            className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-2.5 py-1 text-[11px] font-semibold text-slate-700 transition-colors hover:border-blue-300 hover:bg-blue-50 hover:text-blue-700 disabled:cursor-wait disabled:opacity-60"
          >
            {isUploading ? (
              <LoaderCircle className="h-3.5 w-3.5 animate-spin" />
            ) : (
              <Upload className="h-3.5 w-3.5" />
            )}
            {isUploading ? 'Opening…' : 'Open DOCX'}
          </button>
        </div>
        <span className="text-[10px] font-mono text-emerald-700">Connected</span>
      </div>
      {uploadError && (
        <div className="flex shrink-0 items-center gap-2 border-b border-amber-200 bg-amber-50 px-4 py-2 text-[11px] text-amber-800">
          <AlertCircle className="h-3.5 w-3.5 shrink-0" />
          <span className="min-w-0 flex-1 truncate">{uploadError}</span>
          <button
            type="button"
            onClick={() => setUploadError(undefined)}
            className="font-semibold hover:text-amber-950"
          >
            Dismiss
          </button>
        </div>
      )}
      <div className="min-h-0 flex-1">
        <DocumentEditor
          key={editorRevision}
          id="margintrace-report-editor"
          documentServerUrl={editorConfig.documentServerUrl}
          config={editorConfig.config}
          height="100%"
          width="100%"
          onLoadComponentError={(_errorCode, description) => setError(description)}
          events_onError={(event) => {
            const editorError = event as { data?: { errorDescription?: string } };
            setError(editorError.data?.errorDescription ?? 'ONLYOFFICE reported an editor error');
          }}
        />
      </div>
    </div>
  );
};
