import React, { FormEvent, useEffect, useState } from 'react';
import {
  CheckCircle2,
  Cpu,
  LoaderCircle,
  PlugZap,
  Save,
  Server,
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

const SETTINGS_ENDPOINT = '/api/v1/settings/ai';

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
    <main className="flex-1 overflow-y-auto bg-slate-50 p-6 custom-scrollbar">
      <div className="mx-auto max-w-3xl space-y-5">
        <div className="border-b border-slate-200 pb-3">
          <h2 className="text-base font-bold text-slate-800">
            Settings &amp; Parameters
          </h2>
          <p className="mt-0.5 text-xs text-slate-400">
            Configure the llama.cpp connection used by all LangChain4j AI services.
          </p>
        </div>

        <section className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs">
          <div className="flex items-start gap-3 border-b border-slate-100 px-5 py-4">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-blue-200 bg-blue-50 text-blue-600">
              <Cpu className="h-4 w-4" />
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
                  className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5 font-mono text-sm text-slate-800 outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-100 disabled:bg-slate-50"
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
                  className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5 font-mono text-sm text-slate-800 outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-100 disabled:bg-slate-50"
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
                className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5 font-mono text-sm text-slate-800 outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-100 disabled:bg-slate-50"
              />
              <span className="block text-[11px] text-slate-400">
                Must match the alias advertised by llama.cpp.
              </span>
            </label>

            <div className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-[11px] text-slate-500">
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
                    ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
                    : 'border-amber-200 bg-amber-50 text-amber-800'
                )}
              >
                {notice.success
                  ? <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" />
                  : <XCircle className="mt-0.5 h-4 w-4 shrink-0" />}
                <span>{notice.message}</span>
              </div>
            )}

            <div className="flex justify-end gap-2 border-t border-slate-100 pt-4">
              <button
                type="button"
                onClick={testConnection}
                disabled={disabled || incomplete}
                className="inline-flex items-center gap-2 rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-xs font-semibold text-slate-700 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {action === 'test'
                  ? <LoaderCircle className="h-4 w-4 animate-spin" />
                  : <PlugZap className="h-4 w-4" />}
                Test connection
              </button>
              <button
                type="submit"
                disabled={disabled || incomplete}
                className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-3.5 py-2 text-xs font-semibold text-white transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {action === 'save'
                  ? <LoaderCircle className="h-4 w-4 animate-spin" />
                  : <Save className="h-4 w-4" />}
                Save settings
              </button>
            </div>
          </form>
        </section>

        <p className="px-1 text-[11px] leading-5 text-slate-400">
          In Docker, 127.0.0.1 points to the backend container itself.
          Use host.docker.internal or a network-reachable llama.cpp host.
        </p>
      </div>
    </main>
  );
};
