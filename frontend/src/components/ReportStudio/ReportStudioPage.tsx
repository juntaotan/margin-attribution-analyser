import React, { useCallback, useEffect, useState } from 'react';
import {
  AlertCircle,
  Calculator,
  Database,
  Edit3,
  Filter,
  Layers,
  LoaderCircle,
  RefreshCw,
  Sparkles,
} from 'lucide-react';
import { DocumentContentControl, PlaceholderToken, SemanticExecutionPlan } from './types';
import { createDefaultDocument } from './defaultTemplate';
import { parseSemanticPrompt, executeSemanticQuery } from './semanticParser';
import { OnlyOfficeEditorPane } from './OnlyOfficeEditorPane';

export const ReportStudioPage: React.FC = () => {
  // Document State
  const [doc, setDoc] = useState(createDefaultDocument);
  const [contentControls, setContentControls] = useState<DocumentContentControl[]>([]);
  const [controlsLoading, setControlsLoading] = useState(true);
  const [controlsError, setControlsError] = useState<string>();
  const [documentVersion, setDocumentVersion] = useState<number>();
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
  const [promptInput, setPromptInput] = useState<string>(activeToken?.prompt || '');

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
      setPromptInput(activeToken.prompt);
    } else {
      setPromptInput('');
    }
  }, [activeTokenId, activeToken?.prompt]);

  // Context for semantic parser
  const parserContext = {
    materialId: doc.materialFocus,
    actualStartDate: doc.periodActual.split(' to ')[0] || '2026-08-01',
    actualEndDate: doc.periodActual.split(' to ')[1] || '2026-08-31',
  };

  // Re-parse when promptInput changes in secondary column
  const currentSemanticPlan: SemanticExecutionPlan | undefined = activeToken
    ? parseSemanticPrompt(promptInput || activeToken.prompt, parserContext)
    : undefined;

  // Handle Secondary Column: Test & Execute Query
  const handleExecuteQuery = () => {
    if (!activeToken || !currentSemanticPlan) return;
    const queryResult = executeSemanticQuery(currentSemanticPlan);
    const updatedToken: PlaceholderToken = {
      ...activeToken,
      prompt: promptInput,
      status: 'executed',
      semanticPlan: currentSemanticPlan,
      resolvedValue: queryResult.value,
      unit: queryResult.unit,
      updatedAt: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    };

    setDoc((currentDocument) => ({
      ...currentDocument,
      tokens: {
        ...currentDocument.tokens,
        [activeTokenId]: updatedToken,
      },
    }));
  };

  // Quick Preset Prompts
  const presetPrompts = [
    {
      label: 'PPV Deviation',
      prompt: 'Query actual purchase order settlement prices vs baseline standard costs to derive PPV deviation rate',
    },
    {
      label: 'Material Overuse',
      prompt: 'Analyze shop-floor material dispatches against work order planned BOM quotas to compute overuse rate',
    },
    {
      label: 'Scrap Losses',
      prompt: 'Aggregate defect and scrap write-offs in production logs to evaluate net scrap financial impact',
    },
    {
      label: 'ECN BOM Quota',
      prompt: 'Compare active ECN design bill of materials against baseline revision to calculate unit quota cost impact',
    },
  ];

  return (
    <div className="flex h-full w-full min-h-0 overflow-hidden bg-slate-100 font-sans">
      <main className="min-w-0 flex-1 bg-slate-100">
        <OnlyOfficeEditorPane />
      </main>

      <aside className="w-96 lg:w-[420px] xl:w-[450px] bg-white border-l border-slate-200 flex flex-col shrink-0 overflow-y-auto custom-scrollbar shadow-lg">
          {/* Header */}
          <div className="p-4 bg-slate-50 border-b border-slate-200 flex items-center justify-between shrink-0">
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
                      onClick={() => setActiveTokenId(control.id)}
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
                        {control.alias}
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
                        {control.tag || `Word ID: ${control.wordId || 'unassigned'}`}
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

                  <textarea
                    rows={3}
                    value={promptInput}
                    onChange={(e) => setPromptInput(e.target.value)}
                    placeholder="Enter natural language instructions (e.g. source table, filter criteria, calculation formula)..."
                    className="w-full p-2.5 bg-white border border-slate-200 rounded-lg text-xs text-slate-800 focus:outline-blue-500 focus:ring-1 focus:ring-blue-500 font-sans leading-relaxed shadow-2xs"
                  />

                  {/* Preset prompt pills */}
                  <div className="pt-1">
                    <span className="text-[10px] text-slate-400 block mb-1">Quick Test Presets:</span>
                    <div className="flex flex-wrap gap-1">
                      {presetPrompts.map((p, idx) => (
                        <button
                          key={idx}
                          type="button"
                          onClick={() => setPromptInput(p.prompt)}
                          className="text-[10px] bg-white hover:bg-blue-50 hover:text-blue-700 text-slate-600 px-2 py-0.5 rounded border border-slate-200 transition-colors"
                        >
                          {p.label}
                        </button>
                      ))}
                    </div>
                  </div>
                </div>

                {/* 3. AI Execution Blueprint (Transparent Feedback Card) */}
                {currentSemanticPlan && (
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
                        {currentSemanticPlan.sourceTableLabel}
                      </div>
                    </div>

                    {/* (2) Filter Conditions */}
                    <div className="space-y-1">
                      <div className="flex items-center gap-1 text-[11px] font-bold text-slate-700">
                        <Filter className="w-3.5 h-3.5 text-amber-600" />
                        <span>2. Filter Criteria:</span>
                      </div>
                      <ul className="bg-white p-2 rounded-md border border-slate-200 space-y-1 text-[11px] font-mono text-slate-700">
                        {currentSemanticPlan.filterConditions.map((cond, i) => (
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
                        {currentSemanticPlan.inputFields.map((f, i) => (
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
                        {currentSemanticPlan.formula}
                      </div>
                      <p className="text-[11px] text-slate-500 pt-0.5 leading-snug">
                        <strong>Business Semantic:</strong> {currentSemanticPlan.formulaDescription}
                      </p>
                    </div>

                    {/* Explanation */}
                    <div className="p-2.5 bg-blue-50/80 rounded-md border border-blue-200/70 text-[11px] text-blue-900 leading-relaxed">
                      <span className="font-bold block mb-0.5">💡 Auditability &amp; Traceability Guarantee:</span>
                      {currentSemanticPlan.explanation}
                    </div>

                    {/* Test & Execute Button */}
                    <div className="pt-2">
                      <button
                        type="button"
                        onClick={handleExecuteQuery}
                        className="w-full py-2 px-3 bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-700 hover:to-indigo-700 text-white rounded-lg text-xs font-semibold shadow-xs flex items-center justify-center gap-1.5 transition-all cursor-pointer"
                      >
                        <Sparkles className="w-3.5 h-3.5 text-blue-200" />
                        <span>⚡ Execute Query</span>
                      </button>
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
