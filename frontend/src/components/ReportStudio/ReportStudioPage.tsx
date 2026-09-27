import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  AlertCircle,
  Calculator,
  Database,
  Edit3,
  Filter,
  Layers,
  LoaderCircle,
  Play,
  RefreshCw,
  Sparkles,
} from 'lucide-react';
import {
  ApplyPlaceholderResponse,
  DocumentContentControl,
  GeneratePlaceholderResponse,
  PlaceholderConfig,
  PlaceholderToken,
  SemanticExecutionPlan,
  SemanticQueryResult,
} from './types';
import { createDefaultDocument } from './defaultTemplate';
import { getPreconfiguredPrompt } from './defaultPrompts';
import { OnlyOfficeEditorPane } from './OnlyOfficeEditorPane';

interface DurationInsight {
  status: 'idle' | 'loading' | 'ready' | 'error';
  duration: string;
  sentences: string[];
  message?: string;
}

const userPromptWithoutDuration = (prompt: string) =>
  prompt.replace(/^Duration:[^\r\n]*(?:\r?\n)?/i, '');

const durationFromPrompt = (prompt: string) => {
  const match = prompt.match(/^Duration:\s*([^\r\n]*)/i);
  return match?.[1]?.trim() || undefined;
};

export const ReportStudioPage: React.FC = () => {
  // Document State
  const [doc, setDoc] = useState(createDefaultDocument);
  const [contentControls, setContentControls] = useState<DocumentContentControl[]>([]);
  const [controlsLoading, setControlsLoading] = useState(true);
  const [controlsError, setControlsError] = useState<string>();
  const [documentVersion, setDocumentVersion] = useState<number>();
  const [durationDetectionAttempt, setDurationDetectionAttempt] = useState(0);
  const [durationInsight, setDurationInsight] = useState<DurationInsight>({
    status: 'idle',
    duration: 'Select a tagged placeholder',
    sentences: [],
  });
  const [durationOverrides, setDurationOverrides] = useState<Record<string, string>>({});
  const durationRequestId = useRef(0);
  const [persistedConfigs, setPersistedConfigs] = useState<Record<string, PlaceholderConfig>>({});
  const [activeRunId, setActiveRunId] = useState<string>();
  const [activeRunRevision, setActiveRunRevision] = useState<number>();
  // Currently selected content control for inspection in secondary column
  const [activeTokenId, setActiveTokenId] = useState<string>('');
  // Read query parameters passed from analysis page
  const searchParams = typeof window !== 'undefined' ? new URLSearchParams(window.location.search) : null;
  const urlActualStart = searchParams?.get('actualStartDate') || undefined;
  const urlActualEnd = searchParams?.get('actualEndDate') || undefined;
  const initialUrlDuration = urlActualStart && urlActualEnd ? `${urlActualStart} to ${urlActualEnd}` : undefined;

  const placeholderTokens: PlaceholderToken[] = contentControls.map((control) => {
    const persisted = control.tag ? persistedConfigs[control.tag] : undefined;
    const existingToken = doc.tokens[control.id] || (control.alias ? doc.tokens[control.alias] : undefined);
    const preconfigured = getPreconfiguredPrompt(control.tag || control.alias || control.id);
    const defaultPrompt = preconfigured?.prompt || '';

    if (persisted) {
      const firstVal = persisted.lastRun?.result?.rows?.[0]?.[persisted.lastRun.result.columns[0]];
      let resolvedValue = firstVal != null ? String(firstVal) : undefined;
      if (resolvedValue && persisted.format === 'percentage' && !resolvedValue.endsWith('%')) {
        resolvedValue += '%';
      }
      return {
        id: control.id,
        label: control.alias || control.tag,
        prompt: persisted.prompt || defaultPrompt,
        status: persisted.lastRun?.status === 'SUCCEEDED' || persisted.lastRun?.status === 'APPLIED'
          ? 'executed'
          : (persisted.lastRun?.status === 'FAILED' ? 'error' : (persisted.executionPlan ? 'analyzed' : 'draft')),
        semanticPlan: persisted.executionPlan,
        resolvedValue: resolvedValue ?? existingToken?.resolvedValue,
        unit: persisted.format === 'percentage' ? '%' : undefined,
        updatedAt: persisted.updatedAt ? new Date(persisted.updatedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : undefined,
      };
    }
    return existingToken
      ? { ...existingToken, id: control.id, label: control.alias || control.tag, prompt: existingToken.prompt || defaultPrompt }
      : {
          id: control.id,
          label: control.alias || control.tag,
          prompt: defaultPrompt,
          status: 'draft',
        };
  });
  // Secondary column prompt input buffer
  const activeToken = placeholderTokens.find((token) => token.id === activeTokenId);
  const activeControl = contentControls.find((control) => control.id === activeTokenId);
  const [promptInput, setPromptInput] = useState<string>(activeToken?.prompt || '');
  const [generatedSemanticPlan, setGeneratedSemanticPlan] = useState<SemanticExecutionPlan>();
  const [blueprintLoading, setBlueprintLoading] = useState(false);
  const [blueprintError, setBlueprintError] = useState<string>();
  const [queryResult, setQueryResult] = useState<SemanticQueryResult>();
  const [queryLoading, setQueryLoading] = useState(false);
  const [queryError, setQueryError] = useState<string>();
  const [applyLoading, setApplyLoading] = useState(false);
  const [applyError, setApplyError] = useState<string>();
  const [applySuccess, setApplySuccess] = useState<string>();
  const [editorReloadKey, setEditorReloadKey] = useState(0);
  const blueprintRequestId = useRef(0);

  const loadContentControls = useCallback(async () => {
    setControlsLoading(true);
    setControlsError(undefined);

    try {
      const response = await fetch('/api/report-studio/onlyoffice/content-controls', {
        headers: { Accept: 'application/json' },
      });
      if (!response.ok) {
        throw new Error(`Placeholder scan failed (${response.status})`);
      }

      const payload = (await response.json()) as {
        documentVersion: number;
        controls: DocumentContentControl[];
      };
      const controls = Array.isArray(payload.controls) ? payload.controls : [];
      setContentControls(controls);
      setDocumentVersion(payload.documentVersion);

      // Sync & batch load persisted placeholder configs from PostgreSQL
      try {
        const syncItems = controls
          .filter((c) => c.tag)
          .map((c) => ({ tag: c.tag, alias: c.alias }));
        const configRes = await fetch('/api/report-studio/placeholders/batch', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          body: JSON.stringify({ documentId: 'default', placeholders: syncItems }),
        });
        if (configRes.ok) {
          const configs = (await configRes.json()) as PlaceholderConfig[];
          const map: Record<string, PlaceholderConfig> = {};
          configs.forEach((cfg) => {
            if (cfg.tag) map[cfg.tag] = cfg;
          });
          setPersistedConfigs(map);
        }
      } catch (err) {
        console.warn('Failed to sync persisted placeholder configs:', err);
      }

      setActiveTokenId((currentId) =>
        controls.some((control) => control.id === currentId)
          ? currentId
          : (controls[0]?.id ?? '')
      );
    } catch (requestError) {
      setControlsError(
        requestError instanceof Error
          ? requestError.message
          : 'Unable to scan document placeholders'
      );
    } finally {
      setControlsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadContentControls();
  }, [loadContentControls]);

  // Synchronize state when activeControl or persistedConfigs changes
  useEffect(() => {
    if (!activeControl) {
      setPromptInput('');
      setGeneratedSemanticPlan(undefined);
      setQueryResult(undefined);
      setActiveRunId(undefined);
      setActiveRunRevision(undefined);
      setQueryError(undefined);
      setApplySuccess(undefined);
      setApplyError(undefined);
      return;
    }

    const preconfigured = getPreconfiguredPrompt(activeControl.tag || activeControl.alias || activeControl.id);
    const persisted = activeControl.tag ? persistedConfigs[activeControl.tag] : undefined;

    // 2. Auto-configure AI prompt: persisted prompt -> activeToken prompt -> preconfigured default prompt
    const promptSource = (persisted?.prompt && persisted.prompt.trim().length > 0)
      ? persisted.prompt
      : (activeToken?.prompt && activeToken.prompt.trim().length > 0)
        ? activeToken.prompt
        : (preconfigured?.prompt || '');

    // 1. Auto-configure duration: URL param duration -> local overrides -> persisted duration -> duration from prompt -> default January 2026
    const promptDuration = durationFromPrompt(promptSource);
    const effectiveDur = initialUrlDuration || durationOverrides[activeControl.id] || persisted?.duration || promptDuration || '2026-01-01 to 2026-01-31';
    if (effectiveDur) {
      setDurationOverrides((current) => ({
        ...current,
        [activeControl.id]: effectiveDur,
      }));
      setDurationInsight({
        status: 'ready',
        duration: effectiveDur,
        sentences: [],
      });
    }

    setPromptInput(userPromptWithoutDuration(promptSource));

    if (persisted) {
      setGeneratedSemanticPlan(persisted.executionPlan);
      setQueryResult(persisted.lastRun?.result);
      setActiveRunId(persisted.lastRun?.id);
      setActiveRunRevision(persisted.lastRun?.configRevision);
      if (persisted.lastRun?.status === 'FAILED') {
        setQueryError(persisted.lastRun.errorMessage);
      } else {
        setQueryError(undefined);
      }
      if (persisted.lastRun?.status === 'APPLIED') {
        setApplySuccess('Saved to the source document');
      } else {
        setApplySuccess(undefined);
      }
    } else {
      setGeneratedSemanticPlan(activeToken?.semanticPlan);
      setQueryResult(undefined);
      setActiveRunId(undefined);
      setActiveRunRevision(undefined);
      setQueryError(undefined);
      setApplySuccess(undefined);
    }
  }, [activeTokenId, persistedConfigs, initialUrlDuration]);

  useEffect(() => {
    const control = contentControls.find((item) => item.id === activeTokenId);
    if (!control) {
      setDurationInsight({
        status: 'idle',
        duration: 'Select a tagged placeholder',
        sentences: [],
      });
      return;
    }

    const persisted = control.tag ? persistedConfigs[control.tag] : undefined;
    if (persisted?.duration) {
      return;
    }

    const requestId = ++durationRequestId.current;
    const abortController = new AbortController();
    setDurationInsight({
      status: 'loading',
      duration: 'Detecting…',
      sentences: [],
    });

    void fetch('/api/report-studio/onlyoffice/content-controls/duration', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ tag: control.tag, wordId: control.wordId, alias: control.alias }),
      signal: abortController.signal,
    })
      .then(async (response) => {
        const payload = (await response.json()) as {
          duration?: string;
          sentences?: string[];
          analyzed?: boolean;
          message?: string;
          detail?: string;
        };
        if (!response.ok) {
          throw new Error(payload.detail ?? `Duration detection failed (${response.status})`);
        }
        if (durationRequestId.current !== requestId) return;
        const detectedDuration = payload.duration ?? 'Not specified';
        setDurationOverrides((current) => ({
          ...current,
          [control.id]: detectedDuration,
        }));
        setDurationInsight({
          status: payload.analyzed ? 'ready' : 'error',
          duration: detectedDuration,
          sentences: payload.sentences ?? [],
          message: payload.message,
        });
      })
      .catch((requestError) => {
        if (abortController.signal.aborted || durationRequestId.current !== requestId) return;
        setDurationInsight({
          status: 'error',
          duration: 'Unavailable',
          sentences: [],
          message:
            requestError instanceof Error
              ? requestError.message
              : 'Unable to detect the reporting period',
        });
      });

    return () => abortController.abort();
  }, [
    activeTokenId,
    documentVersion,
    contentControls,
    durationDetectionAttempt,
    persistedConfigs,
  ]);

  const effectiveDuration =
    durationOverrides[activeTokenId] ?? durationInsight.duration;
  const packagedPrompt =
    "Duration: " + effectiveDuration + (promptInput.trim() ? "\n" + promptInput : "");

  const persisted = activeControl?.tag ? persistedConfigs[activeControl.tag] : undefined;
  const isPromptDirty = Boolean(
    activeRunId &&
    ((persisted?.prompt && persisted.prompt.trim() !== packagedPrompt.trim()) ||
     (persisted?.revision != null && activeRunRevision != null && persisted.revision !== activeRunRevision))
  );

  const handleGenerateBlueprint = async () => {
    if (!activeControl) return;
    const tag = activeControl.tag || activeControl.id;
    const alias = activeControl.alias;
    const requestId = ++blueprintRequestId.current;

    setBlueprintLoading(true);
    setBlueprintError(undefined);
    setGeneratedSemanticPlan(undefined);
    setQueryResult(undefined);
    setQueryError(undefined);
    setApplyError(undefined);
    setApplySuccess(undefined);

    try {
      const response = await fetch(
        "/api/report-studio/placeholders/generate",
        {
          method: "POST",
          headers: { "Content-Type": "application/json", Accept: "application/json" },
          body: JSON.stringify({
            documentId: "default",
            tag,
            alias,
            prompt: packagedPrompt,
          }),
        }
      );
      const payload = (await response.json()) as GeneratePlaceholderResponse & { detail?: string };
      if (!response.ok) {
        throw new Error(payload.detail ?? payload.error ?? `Generate failed (${response.status})`);
      }
      if (blueprintRequestId.current !== requestId) return;

      setGeneratedSemanticPlan(payload.blueprint);
      setActiveRunId(payload.runId);
      setActiveRunRevision(payload.revision);

      if (payload.result) {
        setQueryResult(payload.result);
      }
      if (payload.error) {
        setQueryError(payload.error);
      }

      const updatedConfig: PlaceholderConfig = {
        id: payload.configId,
        documentId: "default",
        tag,
        alias,
        prompt: packagedPrompt,
        duration: effectiveDuration,
        executionPlan: payload.blueprint,
        revision: payload.revision,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        lastRun: {
          id: payload.runId,
          configRevision: payload.revision,
          promptSnapshot: packagedPrompt,
          planSnapshot: payload.blueprint,
          executedSql: payload.result?.sql || "",
          result: payload.result,
          status: payload.error ? "FAILED" : "SUCCEEDED",
          errorMessage: payload.error,
          executedAt: new Date().toISOString(),
        },
      };

      setPersistedConfigs((curr) => ({
        ...curr,
        [tag]: updatedConfig,
      }));

      const firstValue = payload.result?.rows?.[0]?.[payload.result.columns[0]];
      let resolved = "";
      if (firstValue != null) {
        if (payload.blueprint.format === "currency" && typeof firstValue === "number") {
          resolved = "$" + Number(firstValue).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        } else if (payload.blueprint.format === "percentage") {
          const num = typeof firstValue === "number" ? firstValue : parseFloat(String(firstValue));
          resolved = !isNaN(num) ? `${num.toFixed(1)}%` : String(firstValue);
        } else {
          resolved = String(firstValue);
        }
      }

      setDoc((currentDoc) => ({
        ...currentDoc,
        tokens: {
          ...currentDoc.tokens,
          [activeControl.id]: {
            id: activeControl.id,
            label: activeControl.alias,
            prompt: packagedPrompt,
            status: payload.error ? "error" : "executed",
            semanticPlan: payload.blueprint,
            resolvedValue: resolved,
            unit: payload.blueprint.format === "percentage" ? "%" : undefined,
            updatedAt: new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }),
          },
        },
      }));
    } catch (requestError) {
      if (blueprintRequestId.current !== requestId) return;
      setBlueprintError(
        requestError instanceof Error ? requestError.message : "Unable to generate Blueprint"
      );
    } finally {
      if (blueprintRequestId.current === requestId) setBlueprintLoading(false);
    }
  };

  const handleApplyResult = async () => {
    if (!activeRunId || !activeControl?.alias) return;

    if (isPromptDirty) {
      setApplyError("Prompt has changed since this result was generated. Generate again before applying.");
      return;
    }

    const firstValue = queryResult?.rows?.[0]?.[queryResult.columns[0]];
    let displayVal = "";
    if (firstValue != null) {
      if (generatedSemanticPlan?.format === "currency" && typeof firstValue === "number") {
        displayVal = "$" + Number(firstValue).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      } else if (generatedSemanticPlan?.format === "percentage") {
        const num = typeof firstValue === "number" ? firstValue : parseFloat(String(firstValue));
        displayVal = !isNaN(num) ? `${num.toFixed(1)}%` : String(firstValue);
      } else {
        displayVal = String(firstValue);
      }
    }

    const confirmed = window.confirm(
      'Save "' + (displayVal || 'result') + '" to the content control with Alias "' +
        activeControl.alias + '"? The document will reload.'
    );
    if (!confirmed) return;

    setApplyLoading(true);
    setApplyError(undefined);
    setApplySuccess(undefined);

    try {
      const response = await fetch(
        "/api/report-studio/placeholders/apply",
        {
          method: "POST",
          headers: { "Content-Type": "application/json", Accept: "application/json" },
          body: JSON.stringify({ runId: activeRunId, alias: activeControl.alias }),
        }
      );
      const payload = (await response.json()) as ApplyPlaceholderResponse & { detail?: string };
      if (!response.ok) {
        throw new Error(payload.detail ?? `Save failed (${response.status})`);
      }

      await loadContentControls();
      setEditorReloadKey((current) => current + 1);
      const updatedControls = payload.updatedControls ?? 1;
      setApplySuccess(
        updatedControls === 1
          ? "Saved to the source document"
          : `Saved to ${updatedControls} matching controls`
      );
    } catch (requestError) {
      setApplyError(
        requestError instanceof Error ? requestError.message : "Unable to save the result"
      );
    } finally {
      setApplyLoading(false);
    }
  };

  return (
    <div className="flex h-full w-full min-h-0 overflow-hidden font-sans" style={{ backgroundColor: "#f3f3f3" }}>
      <main className="min-w-0 flex-1" style={{ backgroundColor: "#f3f3f3" }}>
        <OnlyOfficeEditorPane
          reloadKey={editorReloadKey}
          onDocumentChanged={() => void loadContentControls()}
        />
      </main>

      <aside className="w-96 lg:w-[420px] xl:w-[450px] border-l border-slate-200 flex flex-col shrink-0 overflow-y-auto custom-scrollbar shadow-lg" style={{ backgroundColor: "#f3f3f3" }}>
          {/* Header */}
          <div className="p-4 border-b border-slate-200 flex items-center justify-between shrink-0" style={{ backgroundColor: "#f3f3f3" }}>
            <div className="flex items-center gap-2">
              <div className="w-7 h-7 rounded-lg bg-blue-100 text-blue-700 flex items-center justify-center font-bold">
                <Sparkles className="w-4 h-4" />
              </div>
              <div>
                <h3 className="text-xs font-bold text-slate-900">
                  AI Semantic Sensing &amp; Query Studio
                </h3>
                <p className="text-[10px] text-slate-500 font-mono">
                  Prompt-to-Query Variable Inspector
                </p>
              </div>
            </div>
            {activeControl && (
              <span
                className={`text-[10px] font-mono px-2 py-0.5 rounded-full font-semibold ${
                  persisted?.lastRun?.status === 'APPLIED'
                    ? 'bg-teal-100 text-teal-800'
                    : persisted?.lastRun?.status === 'SUCCEEDED' || activeToken?.status === 'executed'
                    ? 'bg-emerald-100 text-emerald-800'
                    : persisted?.lastRun?.status === 'FAILED'
                    ? 'bg-rose-100 text-rose-800'
                    : 'bg-amber-100 text-amber-800'
                }`}
              >
                {persisted?.lastRun?.status === 'APPLIED'
                  ? 'Applied to Document'
                  : persisted?.lastRun?.status === 'SUCCEEDED' || activeToken?.status === 'executed'
                  ? 'Ready / Executed'
                  : persisted?.lastRun?.status === 'FAILED'
                  ? 'Execution Failed'
                  : 'Pending Execution'}
              </span>
            )}
          </div>

          {/* Secondary Column Body */}
          <div className="p-4 space-y-4 flex-1">
            {/* 1. Content controls discovered in the saved Word document */}
            <div className="space-y-1.5">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <label className="text-[11px] font-bold text-slate-600 uppercase tracking-wider block">
                    Document Data Placeholders
                  </label>
                  <p className="mt-0.5 text-[10px] text-slate-400">
                    {documentVersion
                      ? `Saved document v${documentVersion} · ${contentControls.length} controls`
                      : 'Scanning saved Word content controls'}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => void loadContentControls()}
                  disabled={controlsLoading}
                  className="inline-flex shrink-0 items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-1 text-[10px] font-semibold text-slate-600 transition-colors hover:bg-slate-50 disabled:cursor-wait disabled:opacity-60"
                  title="Save the document in ONLYOFFICE, then refresh this list"
                >
                  {controlsLoading ? (
                    <LoaderCircle className="h-3 w-3 animate-spin" />
                  ) : (
                    <RefreshCw className="h-3 w-3" />
                  )}
                  Refresh
                </button>
              </div>

              {controlsError && (
                <div className="flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 p-2.5 text-[11px] text-amber-800">
                  <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                  <span>{controlsError}</span>
                </div>
              )}

              {!controlsLoading && !controlsError && contentControls.length === 0 && (
                <div className="rounded-lg border border-dashed border-slate-300 bg-slate-50 px-3 py-4 text-center">
                  <p className="text-xs font-semibold text-slate-600">No content controls found</p>
                  <p className="mt-1 text-[10px] leading-relaxed text-slate-400">
                    Create a control and enter its Alias in ONLYOFFICE, save the document, then
                    refresh this list.
                  </p>
                </div>
              )}

              <div className="max-h-52 space-y-1.5 overflow-y-auto pr-1 custom-scrollbar">
                {contentControls.map((control) => {
                  const token = placeholderTokens.find((item) => item.id === control.id);
                  const controlPersisted = control.tag ? persistedConfigs[control.tag] : undefined;
                  return (
                    <button
                      key={control.id}
                      type="button"
                      onClick={() => {
                        setApplyError(undefined);
                        setApplySuccess(undefined);
                        blueprintRequestId.current += 1;
                        setBlueprintLoading(false);
                        setQueryLoading(false);
                        setBlueprintError(undefined);
                        setActiveTokenId(control.id);
                        setDurationDetectionAttempt((current) => current + 1);
                      }}
                      className={`w-full rounded-lg border px-3 py-2 text-left transition-all ${
                        activeTokenId === control.id
                          ? 'border-blue-500 bg-blue-50 shadow-2xs'
                          : 'border-slate-200 bg-white hover:border-slate-300 hover:bg-slate-50'
                      }`}
                    >
                    <span className="flex items-center justify-between gap-2">
                      <span
                        className={`truncate text-xs font-semibold ${
                          activeTokenId === control.id ? 'text-blue-800' : 'text-slate-700'
                        }`}
                      >
                        {control.tag || control.alias}
                      </span>
                      <span className="flex shrink-0 items-center gap-1">
                        {controlPersisted?.lastRun?.status === 'APPLIED' && (
                          <span className="rounded bg-teal-100 px-1.5 py-0.5 text-[9px] font-medium text-teal-800">
                            Applied
                          </span>
                        )}
                        {control.occurrences > 1 && (
                          <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[9px] font-medium text-slate-500">
                            ×{control.occurrences}
                          </span>
                        )}
                        {!control.tagged && (
                          <span className="rounded bg-amber-100 px-1.5 py-0.5 text-[9px] font-medium text-amber-700">
                            No Tag
                          </span>
                        )}
                      </span>
                    </span>
                    <span className="mt-1 flex items-center justify-between gap-2 text-[10px]">
                      <span className="truncate font-mono text-slate-400">
                        {control.alias || `Word ID: ${control.wordId || 'unassigned'}`}
                      </span>
                      {token?.resolvedValue && (
                        <span className="shrink-0 font-mono text-emerald-700">
                          {token.resolvedValue}
                        </span>
                      )}
                    </span>
                    {control.preview && (
                      <span className="mt-1 block truncate text-[10px] text-slate-400">
                        {control.preview}
                      </span>
                    )}
                    </button>
                  );
                })}
              </div>
            </div>

            {activeToken ? (
              <>
                {/* 2. Prompt Input Box */}
                <div className="space-y-2 bg-slate-50 p-3.5 rounded-xl border border-slate-200">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-bold text-slate-800 flex items-center gap-1.5">
                      <Edit3 className="w-3.5 h-3.5 text-blue-600" />
                      AI Prompt Instructions:
                    </span>
                    <div className="flex items-center gap-2">
                      <button
                        type="button"
                        onClick={() => {
                          if (!activeControl) return;
                          const pre = getPreconfiguredPrompt(activeControl.tag || activeControl.alias || activeControl.id);
                          if (pre?.prompt) {
                            setPromptInput(pre.prompt);
                          }
                        }}
                        title="Reset to preconfigured AI prompt"
                        className="text-[10px] text-indigo-600 hover:text-indigo-800 font-medium flex items-center gap-1 cursor-pointer bg-indigo-50 border border-indigo-200 px-1.5 py-0.5 rounded"
                      >
                        <RefreshCw className="w-2.5 h-2.5" />
                        <span>Preconfigured</span>
                      </button>
                      <span className="text-[10px] text-slate-400 font-mono">
                        {activeToken.id}
                      </span>
                    </div>
                  </div>

                  <div className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-2xs focus-within:border-blue-500 focus-within:ring-1 focus-within:ring-blue-500">
                    <div
                      className={`flex items-center gap-1.5 border-b px-2.5 py-2 font-mono text-[11px] ${
                        durationInsight.status === 'error'
                          ? 'border-amber-200 bg-amber-50 text-amber-800'
                          : 'border-blue-100 bg-blue-50 text-blue-800'
                      }`}
                    >
                      {durationInsight.status === 'loading' && (
                        <LoaderCircle className="h-3 w-3 shrink-0 animate-spin" />
                      )}
                      <span className="font-semibold">Duration:</span>
                      <input
                        type="text"
                        value={effectiveDuration}
                        onChange={(event) => {
                          durationRequestId.current += 1;
                          setGeneratedSemanticPlan(undefined);
                          setQueryResult(undefined);
                          setQueryError(undefined);
                          setApplyError(undefined);
                          setApplySuccess(undefined);
                        blueprintRequestId.current += 1;
                        setBlueprintLoading(false);
                        setQueryLoading(false);
                        setBlueprintError(undefined);
                          setDurationOverrides((current) => ({
                            ...current,
                            [activeTokenId]: event.target.value,
                          }));
                          setDurationInsight((current) => ({
                            ...current,
                            status: 'ready',
                            duration: event.target.value,
                            message: undefined,
                          }));
                        }}
                        disabled={!activeToken}
                        aria-label="Reporting duration"
                        className="min-w-0 flex-1 border-0 bg-transparent font-mono text-[11px] text-inherit outline-none placeholder:text-slate-400"
                        placeholder="Enter or correct the reporting period"
                      />
                    </div>
                    <textarea
                      rows={3}
                      value={promptInput}
                      onChange={(e) => {
                        setPromptInput(e.target.value);
                        setGeneratedSemanticPlan(undefined);
                        setQueryResult(undefined);
                        setQueryError(undefined);
                        setApplyError(undefined);
                        setApplySuccess(undefined);
                        blueprintRequestId.current += 1;
                        setBlueprintLoading(false);
                        setQueryLoading(false);
                        setBlueprintError(undefined);
                      }}
                      placeholder="Enter natural language instructions (e.g. source table, filter criteria, calculation formula)..."
                      className="w-full resize-y border-0 bg-white p-2.5 text-xs leading-relaxed text-slate-800 outline-none"
                    />
                  </div>
                  {durationInsight.message && (
                    <p className="text-[10px] leading-relaxed text-amber-700">
                      {durationInsight.message}
                    </p>
                  )}
                  {durationInsight.sentences[0] && (
                    <p
                      className="truncate text-[10px] text-slate-400"
                      title={durationInsight.sentences.join("\n")}
                    >
                      Context: {durationInsight.sentences[0]}
                    </p>
                  )}

                  <button
                    type="button"
                    onClick={handleGenerateBlueprint}
                    disabled={durationInsight.status === "loading" || blueprintLoading}
                    className="inline-flex w-full items-center justify-center gap-1.5 rounded-lg bg-blue-600 px-3 py-2 text-xs font-semibold text-white shadow-xs transition-colors hover:bg-blue-700 disabled:cursor-wait disabled:opacity-60"
                  >
                    {durationInsight.status === "loading" || blueprintLoading ? (
                      <LoaderCircle className="h-3.5 w-3.5 animate-spin" />
                    ) : (
                      <Sparkles className="h-3.5 w-3.5" />
                    )}
                    {blueprintLoading ? "Generating & Executing…" : "Generate Blueprint"}
                  </button>
                  {blueprintError && (
                    <p className="text-[10px] leading-relaxed text-red-600">
                      {blueprintError}
                    </p>
                  )}
                </div>

                {(queryLoading || queryResult || queryError) && (
                  <div className="rounded-xl border border-emerald-200 bg-white p-4 shadow-xs">
                    <div className="mb-3 flex items-center justify-between border-b border-emerald-100 pb-2">
                      <div className="flex items-center gap-1.5 text-xs font-bold text-emerald-950">
                        <Database className="h-4 w-4 text-emerald-600" />
                        <span>Result</span>
                      </div>
                      {queryResult && (
                        <span className="rounded bg-emerald-100 px-1.5 py-0.5 font-mono text-[10px] font-medium text-emerald-800">
                          {queryResult.rowCount} row{queryResult.rowCount === 1 ? '' : 's'}
                        </span>
                      )}
                    </div>

                    {queryLoading && (
                      <div className="flex items-center justify-center gap-2 py-5 text-xs text-slate-500">
                        <LoaderCircle className="h-4 w-4 animate-spin text-emerald-600" />
                        Running query...
                      </div>
                    )}
                    {queryError && (
                      <div className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 p-2.5 text-[11px] text-red-700">
                        <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                        <span>{queryError}</span>
                      </div>
                    )}
                    {queryResult && !queryLoading && (
                      <div className="overflow-x-auto rounded-lg border border-slate-200">
                        <table className="min-w-full divide-y divide-slate-200 text-left text-[11px]">
                          <thead className="bg-slate-50 text-slate-500">
                            <tr>
                              {queryResult.columns.map((column) => (
                                <th key={column} className="px-3 py-2 font-semibold">
                                  {column}
                                </th>
                              ))}
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-slate-100 bg-white font-mono text-slate-800">
                            {queryResult.rows.length === 0 ? (
                              <tr>
                                <td
                                  colSpan={Math.max(queryResult.columns.length, 1)}
                                  className="px-3 py-4 text-center font-sans text-slate-400"
                                >
                                  No records found for the specified period
                                </td>
                              </tr>
                            ) : queryResult.rows.map((row, rowIndex) => (
                              <tr key={rowIndex}>
                                {queryResult.columns.map((column) => {
                                  const cellVal = row[column];
                                  let cellText = cellVal == null ? '-' : String(cellVal);
                                  if (cellVal != null && typeof cellVal === 'number' && generatedSemanticPlan?.format === 'currency') {
                                    cellText = '$' + Number(cellVal).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
                                  } else if (cellVal != null && generatedSemanticPlan?.format === 'percentage') {
                                    const num = typeof cellVal === 'number' ? cellVal : parseFloat(String(cellVal));
                                    if (!isNaN(num) && !cellText.endsWith('%')) {
                                      cellText = `${num.toFixed(1)}%`;
                                    }
                                  }
                                  return (
                                    <td key={column} className="whitespace-nowrap px-3 py-2">
                                      {cellText}
                                    </td>
                                  );
                                })}
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}

                    <button
                      type="button"
                      onClick={() => void handleApplyResult()}
                      disabled={applyLoading || !activeRunId || !activeControl?.alias || isPromptDirty}
                      className="mt-3 inline-flex w-full items-center justify-center gap-1.5 rounded-lg bg-emerald-600 px-3 py-2 text-xs font-semibold text-white transition-colors hover:bg-emerald-700 disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-500"
                    >
                      {applyLoading ? (
                        <LoaderCircle className="h-3.5 w-3.5 animate-spin" />
                      ) : (
                        <Play className="h-3.5 w-3.5" />
                      )}
                      {applyLoading ? "Saving..." : "Apply"}
                    </button>
                    {isPromptDirty && (
                      <p className="mt-2 text-[10px] text-amber-700 leading-relaxed">
                        Prompt has changed since this result was generated. Generate again before applying.
                      </p>
                    )}
                    {applySuccess && (
                      <p className="mt-2 text-[10px] text-emerald-700">{applySuccess}</p>
                    )}
                    {applyError && (
                      <p className="mt-2 text-[10px] text-red-600">{applyError}</p>
                    )}
                  </div>
                )}
                {/* 3. AI Execution Blueprint (Transparent Feedback Card) */}
                {generatedSemanticPlan && (
                  <div className="bg-gradient-to-br from-white to-blue-50/40 rounded-xl border border-blue-200/80 p-4 space-y-3.5 shadow-xs">
                    <div className="flex items-center justify-between border-b border-blue-100 pb-2">
                      <div className="flex items-center gap-1.5 font-bold text-xs text-blue-950">
                        <Calculator className="w-4 h-4 text-blue-600" />
                        <span>AI Semantic Intent &amp; Execution Blueprint</span>
                      </div>
                      <span className="text-[10px] bg-blue-100 text-blue-800 font-mono px-1.5 py-0.5 rounded font-medium">
                        Auto-Derived
                      </span>
                    </div>

                    {/* (1) Source Table */}
                    <div className="space-y-1">
                      <div className="flex items-center gap-1 text-[11px] font-bold text-slate-700">
                        <Database className="w-3.5 h-3.5 text-blue-600" />
                        <span>1. Source Data Table:</span>
                      </div>
                      <div className="bg-white p-2 rounded-md border border-slate-200 text-xs font-mono text-blue-900 font-semibold">
                        {generatedSemanticPlan.sourceTableLabel}
                      </div>
                    </div>

                    {/* (2) Filter Conditions */}
                    <div className="space-y-1">
                      <div className="flex items-center gap-1 text-[11px] font-bold text-slate-700">
                        <Filter className="w-3.5 h-3.5 text-amber-600" />
                        <span>2. Filter Criteria:</span>
                      </div>
                      <ul className="bg-white p-2 rounded-md border border-slate-200 space-y-1 text-[11px] font-mono text-slate-700">
                        {generatedSemanticPlan.filterConditions.map((cond, i) => (
                          <li key={i} className="flex items-center gap-1.5">
                            <span className="w-1.5 h-1.5 rounded-full bg-amber-500" />
                            <span>{cond}</span>
                          </li>
                        ))}
                      </ul>
                    </div>

                    {/* (3) Input Fields Mapping */}
                    <div className="space-y-1">
                      <div className="flex items-center gap-1 text-[11px] font-bold text-slate-700">
                        <Layers className="w-3.5 h-3.5 text-indigo-600" />
                        <span>3. Input Field Mappings:</span>
                      </div>
                      <div className="bg-white rounded-md border border-slate-200 divide-y divide-slate-100 text-[11px]">
                        {generatedSemanticPlan.inputFields.map((f, i) => (
                          <div key={i} className="p-2 space-y-0.5">
                            <div className="flex items-center justify-between font-mono">
                              <span className="font-bold text-indigo-700">{f.field}</span>
                              <span className="text-slate-500 text-[10px]">{f.label}</span>
                            </div>
                            <p className="text-[10px] text-slate-400">{f.description}</p>
                          </div>
                        ))}
                      </div>
                    </div>

                    {/* (4) Formula & Processing */}
                    <div className="space-y-1">
                      <div className="flex items-center gap-1 text-[11px] font-bold text-slate-700">
                        <Calculator className="w-3.5 h-3.5 text-emerald-600" />
                        <span>4. Processing &amp; Computation Formula:</span>
                      </div>
                      <div className="bg-slate-900 text-emerald-300 p-2.5 rounded-md font-mono text-[11px] break-all">
                        {generatedSemanticPlan.formula}
                      </div>
                      <p className="text-[11px] text-slate-500 pt-0.5 leading-snug">
                        <strong>Business Semantic:</strong> {generatedSemanticPlan.formulaDescription}
                      </p>
                    </div>

                    {/* Explanation */}
                    <div className="p-2.5 bg-blue-50/80 rounded-md border border-blue-200/70 text-[11px] text-blue-900 leading-relaxed">
                      <span className="font-bold block mb-0.5">💡 Auditability &amp; Traceability Guarantee:</span>
                      {generatedSemanticPlan.explanation}
                    </div>

                  </div>
                )}
              </>
            ) : (
              <div className="p-8 text-center text-slate-400 text-xs">
                Please select a data placeholder.
              </div>
            )}
          </div>
        </aside>
      </div>

  );
};
