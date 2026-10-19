import React, { FormEvent, useEffect, useState } from 'react';
import { CheckCircle2, Cpu, LoaderCircle, PlugZap, Save, Server, XCircle } from 'lucide-react';
import { AiSettings, fetchAiSettings, saveAiSettings, testAiConnection } from './settingsApi';
import { SettingsSection } from './SettingsSection';

type Notice = {
  success: boolean;
  message: string;
};

const INPUT_CLASS =
  'w-full rounded-lg border border-slate-300/80 bg-white px-3 py-2.5 font-mono text-sm text-slate-800 outline-none transition focus:border-slate-600 focus:ring-1 focus:ring-slate-300 disabled:bg-[#f8fafc] shadow-2xs';

const settingsKey = (host: string, port: string | number, model: string) => `${host}|${port}|${model}`;

const errorMessage = (error: unknown, fallback: string) => (error instanceof Error ? error.message : fallback);

/**
 * Holds the llama.cpp connection form, loads the saved settings on mount, and tests or saves the entered values.
 * Testing never saves; `unsaved` reports whether the form differs from the last loaded or saved settings.
 */
function useAiConnectionSettings() {
  const [savedKey, setSavedKey] = useState('');
  const [host, setHost] = useState('');
  const [port, setPort] = useState('8081');
  const [model, setModel] = useState('local');
  const [activeUrl, setActiveUrl] = useState('');
  const [persisted, setPersisted] = useState(false);
  const [loading, setLoading] = useState(true);
  const [action, setAction] = useState<'test' | 'save' | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);

  const showSettings = (settings: AiSettings) => {
    setHost(settings.host);
    setPort(String(settings.port));
    setModel(settings.model);
    setActiveUrl(settings.baseUrl);
    setPersisted(settings.persisted);
    setSavedKey(settingsKey(settings.host, settings.port, settings.model));
  };

  useEffect(() => {
    let active = true;
    fetchAiSettings()
      .then((settings) => {
        if (active) showSettings(settings);
      })
      .catch((error: Error) => {
        if (active) setNotice({ success: false, message: error.message });
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const requestBody = () => ({ host: host.trim(), port: Number(port), model: model.trim() });

  const runAction = async (name: 'test' | 'save', task: () => Promise<Notice>, failure: string) => {
    setAction(name);
    setNotice(null);
    try {
      setNotice(await task());
    } catch (error) {
      setNotice({ success: false, message: errorMessage(error, failure) });
    } finally {
      setAction(null);
    }
  };

  const testConnection = () =>
    runAction(
      'test',
      async () => {
        const result = await testAiConnection(requestBody());
        return {
          success: result.reachable && result.modelReady,
          message: `${result.message} (${result.latencyMs} ms)`,
        };
      },
      'Connection test failed',
    );

  const saveSettings = (event: FormEvent) => {
    event.preventDefault();
    return runAction(
      'save',
      async () => {
        showSettings(await saveAiSettings(requestBody()));
        return { success: true, message: 'Settings saved. New AI requests now use this connection.' };
      },
      'Unable to save settings',
    );
  };

  return {
    form: { host, port, model, setHost, setPort, setModel },
    activeUrl,
    persisted,
    loading,
    action,
    notice,
    unsaved: Boolean(savedKey) && savedKey !== settingsKey(host, port, model),
    disabled: loading || action !== null,
    incomplete: !host.trim() || !port || !model.trim(),
    testConnection,
    saveSettings,
  };
}

/** Settings section for the llama.cpp server used by all LangChain4j AI services. */
export const AiConnectionSettings: React.FC = () => {
  const ai = useAiConnectionSettings();
  const { host, port, model, setHost, setPort, setModel } = ai.form;

  return (
    <SettingsSection
      contentId="ai-settings-content"
      icon={<Cpu className="h-4 w-4 text-sky-600" />}
      title="llama.cpp connection"
      description="This address must be reachable from the backend process or container. Testing does not save the entered values."
      summary={
        <>
          {ai.loading ? 'Loading saved connection…' : ai.activeUrl || 'Connection unavailable'} · {model}
          {ai.unsaved ? ' · Unsaved' : ''}
          {ai.notice ? ` · ${ai.notice.message}` : ''}
        </>
      }
    >
      <form onSubmit={ai.saveSettings} className="space-y-5 p-5">
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
              disabled={ai.disabled}
              className={INPUT_CLASS}
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
              disabled={ai.disabled}
              className={INPUT_CLASS}
            />
          </label>
        </div>

        <label className="block space-y-1.5">
          <span className="text-xs font-semibold text-slate-700">Model name</span>
          <input
            value={model}
            onChange={(event) => setModel(event.target.value)}
            placeholder="local"
            autoComplete="off"
            disabled={ai.disabled}
            className={INPUT_CLASS}
          />
          <span className="block text-[11px] text-slate-400">Must match the alias advertised by llama.cpp.</span>
        </label>

        <div className="rounded-lg border border-slate-200/80 bg-[#f8fafc] px-3 py-2 text-[11px] text-slate-500">
          <div>
            <span className="font-semibold text-slate-600">Active URL: </span>
            <span className="font-mono">{ai.loading ? 'Loading…' : ai.activeUrl || 'Unavailable'}</span>
          </div>
          <div className="mt-1">Source: {ai.persisted ? 'PostgreSQL settings' : 'LLAMA_BASE_URL default'}</div>
        </div>

        {ai.notice && <ConnectionNotice notice={ai.notice} />}

        <div className="flex justify-end gap-2 border-t border-slate-100 pt-4">
          <button
            type="button"
            onClick={() => void ai.testConnection()}
            disabled={ai.disabled || ai.incomplete}
            className="inline-flex items-center gap-2 rounded-lg border border-slate-300/80 bg-white px-3.5 py-2 text-xs font-semibold text-slate-700 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50 shadow-2xs"
          >
            {ai.action === 'test' ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <PlugZap className="h-4 w-4" />}
            Test connection
          </button>
          <button
            type="submit"
            disabled={ai.disabled || ai.incomplete}
            className="inline-flex items-center gap-2 rounded-lg bg-slate-900 px-3.5 py-2 text-xs font-semibold text-white transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-50 shadow-xs border border-slate-800"
          >
            {ai.action === 'save' ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
            Save settings
          </button>
        </div>
      </form>
    </SettingsSection>
  );
};

const ConnectionNotice: React.FC<{ notice: Notice }> = ({ notice }) => (
  <div
    role="status"
    className={
      'flex items-start gap-2 rounded-lg border px-3 py-2.5 text-xs ' +
      (notice.success
        ? 'border-emerald-200/80 bg-emerald-50/70 text-emerald-800'
        : 'border-amber-200/80 bg-amber-50/70 text-amber-800')
    }
  >
    {notice.success ? (
      <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-600" />
    ) : (
      <XCircle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" />
    )}
    <span>{notice.message}</span>
  </div>
);
