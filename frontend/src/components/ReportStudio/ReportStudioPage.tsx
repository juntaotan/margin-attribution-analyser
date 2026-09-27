import React, { useState } from 'react';
import { Edit3, Sparkles, Database, Calculator, Filter, Layers } from 'lucide-react';
import { PlaceholderToken, SemanticExecutionPlan } from './types';
import { createDefaultDocument } from './defaultTemplate';
import { parseSemanticPrompt, executeSemanticQuery } from './semanticParser';
import { OnlyOfficeEditorPane } from './OnlyOfficeEditorPane';

export const ReportStudioPage: React.FC = () => {
  // Document State
  const [doc, setDoc] = useState(createDefaultDocument);
  // Currently selected token for inspection in secondary column
  const [activeTokenId, setActiveTokenId] = useState<string>('token_ppv');
  // Secondary column prompt input buffer
  const activeToken = doc.tokens[activeTokenId];
  const [promptInput, setPromptInput] = useState<string>(activeToken?.prompt || '');

  // When activeTokenId changes, sync the prompt buffer
  React.useEffect(() => {
    if (activeToken) {
      setPromptInput(activeToken.prompt);
    }
  }, [activeTokenId]);

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

    setDoc({
      ...doc,
      tokens: {
        ...doc.tokens,
        [activeTokenId]: updatedToken,
      },
    });
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
            {/* 1. Placeholder Selector Bar */}
            <div className="space-y-1.5">
              <label className="text-[11px] font-bold text-slate-600 uppercase tracking-wider block">
                Document Data Placeholders
              </label>
              <div className="flex flex-wrap gap-1.5">
                {Object.values(doc.tokens).map((tok) => (
                  <button
                    key={tok.id}
                    type="button"
                    onClick={() => setActiveTokenId(tok.id)}
                    className={`px-2.5 py-1 rounded-md text-xs font-mono transition-all flex items-center gap-1 ${
                      activeTokenId === tok.id
                        ? 'bg-blue-600 text-white shadow-2xs font-semibold'
                        : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
                    }`}
                  >
                    <span>{`{{${tok.label}}}`}</span>
                    {tok.resolvedValue && (
                      <span className="text-[10px] opacity-80">({tok.resolvedValue})</span>
                    )}
                  </button>
                ))}
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
