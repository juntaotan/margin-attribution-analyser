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
  DocumentContentControl,
  PlaceholderToken,
  SemanticExecutionPlan,
  SemanticQueryResult,
} from './types';
import { createDefaultDocument } from './defaultTemplate';
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
  // Currently selected content control for inspection in secondary column
  const [activeTokenId, setActiveTokenId] = useState<string>('');
  const placeholderTokens: PlaceholderToken[] = contentControls.map((control) => {
    const existingToken = doc.tokens[control.id];
    return existingToken
      ? { ...existingToken, id: control.id, label: control.alias }
      : {
          id: control.id,
          label: control.alias,
          prompt: '',
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

  // When activeTokenId changes, sync the prompt buffer
  useEffect(() => {
    if (activeToken) {
      setPromptInput(userPromptWithoutDuration(activeToken.prompt));
      const storedDuration = durationFromPrompt(activeToken.prompt);
      if (storedDuration) {
        setDurationOverrides((current) =>
          current[activeToken.id]
            ? current
            : { ...current, [activeToken.id]: storedDuration }
        );
      }
    } else {
      setPromptInput('');
    }
  }, [activeTokenId, activeToken?.prompt]);

  useEffect(() => {
    const control = contentControls.find((item) => item.id === activeTokenId);
    const requestId = ++durationRequestId.current;
    if (!control) {
      setDurationInsight({
        status: 'idle',
        duration: 'Select a tagged placeholder',
        sentences: [],
      });
      return;
    }
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
  ]);

  const effectiveDuration =
    durationOverrides[activeTokenId] ?? durationInsight.duration;
  const packagedPrompt =
    "Duration: " + effectiveDuration + (promptInput.trim() ? "\n" + promptInput : "");

  const handleGenerateBlueprint = async () => {
    if (!activeToken) return;
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
        "/api/report-studio/onlyoffice/content-controls/blueprint",
        {
          method: "POST",
          headers: { "Content-Type": "application/json", Accept: "application/json" },
          body: JSON.stringify({ prompt: packagedPrompt }),
        }
      );
      const payload = (await response.json()) as SemanticExecutionPlan & { detail?: string };
      if (!response.ok) {
        throw new Error(payload.detail ?? `Blueprint generation failed (${response.status})`);
      }
      if (blueprintRequestId.current !== requestId) return;

      const analyzedToken: PlaceholderToken = {
        ...activeToken,
        prompt: packagedPrompt,
        status: "analyzed",
        semanticPlan: payload,
        updatedAt: new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }),
      };
      setDoc((currentDocument) => ({
        ...currentDocument,
        tokens: {
          ...currentDocument.tokens,
          [activeTokenId]: analyzedToken,
        },
      }));
      setGeneratedSemanticPlan(payload);
      setQueryLoading(true);

      try {
        const queryResponse = await fetch(
          "/api/report-studio/onlyoffice/content-controls/blueprint/execute",
          {
            method: "POST",
            headers: { "Content-Type": "application/json", Accept: "application/json" },
            body: JSON.stringify(payload),
          }
        );
        const queryPayload = (await queryResponse.json()) as SemanticQueryResult & {
          detail?: string;
        };
        if (!queryResponse.ok) {
          throw new Error(
            queryPayload.detail ?? `Query execution failed (${queryResponse.status})`
          );
        }
        if (blueprintRequestId.current !== requestId) return;

        setQueryResult(queryPayload);
        const firstValue = queryPayload.rows[0]?.[queryPayload.columns[0]];
        setDoc((currentDocument) => ({
          ...currentDocument,
          tokens: {
            ...currentDocument.tokens,
            [activeTokenId]: {
              ...analyzedToken,
              status: "executed",
              resolvedValue: firstValue == null ? "" : String(firstValue),
              unit: payload.format === "percentage" ? "%" : undefined,
            },
          },
        }));
      } catch (requestError) {
        if (blueprintRequestId.current !== requestId) return;
        setQueryError(
          requestError instanceof Error ? requestError.message : "Unable to execute query"
        );
      } finally {
        if (blueprintRequestId.current === requestId) setQueryLoading(false);
      }
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
    if (!queryResult || !generatedSemanticPlan || !activeControl?.alias) return;

    const firstValue = queryResult.rows[0]?.[queryResult.columns[0]];
    if (firstValue == null) {
      setApplyError("There is no result value to save");
      return;
    }

    let value = String(firstValue);
    if (generatedSemanticPlan.format === "percentage" && !value.trim().endsWith("%")) {
      value += "%";
    }

    const confirmed = window.confirm(
      'Save "' + value + '" to the content control with Alias "' +
        activeControl.alias + '"? The document will reload.'
    );
    if (!confirmed) return;

    setApplyLoading(true);
    setApplyError(undefined);
    setApplySuccess(undefined);

    try {
      const response = await fetch(
        "/api/report-studio/onlyoffice/content-controls/apply",
        {
          method: "POST",
          headers: { "Content-Type": "application/json", Accept: "application/json" },
          body: JSON.stringify({ alias: activeControl.alias, value }),
        }
      );
      const payload = (await response.json()) as {
        documentVersion?: number;
        updatedControls?: number;
        detail?: string;
      };
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
            {activeToken && (
              <span
                className={`text-[10px] font-mono px-2 py-0.5 rounded-full font-semibold ${
                  activeToken.status === 'executed'
                    ? 'bg-emerald-100 text-emerald-800'
                    : 'bg-amber-100 text-amber-800'
                }`}
              >
                {activeToken.status === 'executed' ? 'Ready / Executed' : 'Pending Execution'}
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
                  return (
                    <button
                      key={control.id}
                      type="button"
                      onClick={() => {
                        setDurationOverrides((current) => {
                          if (!(control.id in current)) return current;
                          const next = { ...current };
                          delete next[control.id];
                          return next;
                        });
                        setGeneratedSemanticPlan(undefined);
                        setQueryResult(undefined);
                        setQueryError(undefined);
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
                    <span className="text-[10px] text-slate-400 font-mono">
                      {activeToken.id}
                    </span>
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
                    {blueprintLoading
                      ? queryLoading
                        ? "Executing Query…"
                        : "Generating Blueprint…"
                      : "Generate Blueprint"}
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
                                  No data returned
                                </td>
                              </tr>
                            ) : queryResult.rows.map((row, rowIndex) => (
                              <tr key={rowIndex}>
                                {queryResult.columns.map((column) => (
                                  <td key={column} className="whitespace-nowrap px-3 py-2">
                                    {row[column] == null ? '-' : String(row[column])}
                                  </td>
                                ))}
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}

                    <button
                      type="button"
                      onClick={() => void handleApplyResult()}
                      disabled={applyLoading || !queryResult || !activeControl?.alias}
                      className="mt-3 inline-flex w-full items-center justify-center gap-1.5 rounded-lg bg-emerald-600 px-3 py-2 text-xs font-semibold text-white transition-colors hover:bg-emerald-700 disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-500"
                    >
                      {applyLoading ? (
                        <LoaderCircle className="h-3.5 w-3.5 animate-spin" />
                      ) : (
                        <Play className="h-3.5 w-3.5" />
                      )}
                      {applyLoading ? "Saving..." : "Apply"}
                    </button>
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
