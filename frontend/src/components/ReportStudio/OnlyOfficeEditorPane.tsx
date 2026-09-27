import React, { useCallback, useEffect, useState } from 'react';
import { AlertCircle, FileText, LoaderCircle, RefreshCw } from 'lucide-react';
import { DocumentEditor } from '@onlyoffice/document-editor-react';
import type { Config } from '@onlyoffice/doceditor-types';

interface OnlyOfficeEditorConfigResponse {
  documentServerUrl: string;
  config: Config;
}

const CONFIG_ENDPOINT = '/api/report-studio/onlyoffice/config';

export const OnlyOfficeEditorPane: React.FC = () => {
  const [editorConfig, setEditorConfig] = useState<OnlyOfficeEditorConfigResponse>();
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string>();

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
  }, [loadConfig]);

  if (isLoading) {
    return (
      <div className="flex h-full items-center justify-center bg-slate-100">
        <div className="flex items-center gap-2 text-sm text-slate-500">
          <LoaderCircle className="h-5 w-5 animate-spin text-blue-600" />
          <span>Loading ONLYOFFICE editor…</span>
        </div>
      </div>
    );
  }

  if (error || !editorConfig) {
    return (
      <div className="flex h-full items-center justify-center bg-slate-100 p-8">
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
    <div className="flex h-full min-h-0 flex-col bg-white">
      <div className="flex h-10 shrink-0 items-center justify-between border-b border-slate-200 bg-white px-4">
        <div className="flex items-center gap-2 text-xs font-semibold text-slate-700">
          <FileText className="h-4 w-4 text-blue-600" />
          <span>ONLYOFFICE Document Editor</span>
        </div>
        <span className="text-[10px] font-mono text-emerald-700">Connected</span>
      </div>
      <div className="min-h-0 flex-1">
        <DocumentEditor
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
