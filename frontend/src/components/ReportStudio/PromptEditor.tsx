import React from 'react';
import { Edit3, LoaderCircle, RefreshCw, Sparkles } from 'lucide-react';
import { DurationInsight } from './useDurationInsight';

interface PromptEditorProps {
  tokenId: string;
  durationInsight: DurationInsight;
  effectiveDuration: string;
  promptInput: string;
  blueprintLoading: boolean;
  blueprintError?: string;
  onResetPrompt: () => void;
  onDurationChange: (value: string) => void;
  onPromptChange: (value: string) => void;
  onGenerate: () => void;
}

/**
 * Edits the reporting period and AI prompt for the selected placeholder, and starts blueprint generation.
 * Generation is disabled while the period is being detected or a blueprint is being generated.
 */
export const PromptEditor: React.FC<PromptEditorProps> = ({
  tokenId,
  durationInsight,
  effectiveDuration,
  promptInput,
  blueprintLoading,
  blueprintError,
  onResetPrompt,
  onDurationChange,
  onPromptChange,
  onGenerate,
}) => {
  const busy = durationInsight.status === 'loading' || blueprintLoading;
  return (
    <div className="space-y-2 bg-[#f8fafc] p-3.5 rounded-xl border border-slate-200/80">
      <div className="flex items-center justify-between">
        <span className="text-xs font-bold text-slate-800 flex items-center gap-1.5">
          <Edit3 className="w-3.5 h-3.5 text-blue-600" />
          AI Prompt Instructions:
        </span>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={onResetPrompt}
            title="Reset to preconfigured AI prompt"
            className="text-[10px] text-indigo-600 hover:text-indigo-800 font-medium flex items-center gap-1 cursor-pointer bg-indigo-50 border border-indigo-200 px-1.5 py-0.5 rounded"
          >
            <RefreshCw className="w-2.5 h-2.5" />
            <span>Preconfigured</span>
          </button>
          <span className="text-[10px] text-slate-400 font-mono">{tokenId}</span>
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
          {durationInsight.status === 'loading' && <LoaderCircle className="h-3 w-3 shrink-0 animate-spin" />}
          <span className="font-semibold">Duration:</span>
          <input
            type="text"
            value={effectiveDuration}
            onChange={(event) => onDurationChange(event.target.value)}
            aria-label="Reporting duration"
            className="min-w-0 flex-1 border-0 bg-transparent font-mono text-[11px] text-inherit outline-none placeholder:text-slate-400"
            placeholder="Enter or correct the reporting period"
          />
        </div>
        <textarea
          rows={3}
          value={promptInput}
          onChange={(event) => onPromptChange(event.target.value)}
          placeholder="Enter natural language instructions (e.g. source table, filter criteria, calculation formula)..."
          className="w-full resize-y border-0 bg-white p-2.5 text-xs leading-relaxed text-slate-800 outline-none"
        />
      </div>
      {durationInsight.message && (
        <p className="text-[10px] leading-relaxed text-amber-700">{durationInsight.message}</p>
      )}
      {durationInsight.sentences[0] && (
        <p className="truncate text-[10px] text-slate-400" title={durationInsight.sentences.join('\n')}>
          Context: {durationInsight.sentences[0]}
        </p>
      )}

      <button
        type="button"
        onClick={onGenerate}
        disabled={busy}
        className="inline-flex w-full items-center justify-center gap-1.5 rounded-lg bg-blue-600 px-3 py-2 text-xs font-semibold text-white shadow-xs transition-colors hover:bg-blue-700 disabled:cursor-wait disabled:opacity-60"
      >
        {busy ? <LoaderCircle className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
        {blueprintLoading ? 'Generating & Executing…' : 'Generate Blueprint'}
      </button>
      {blueprintError && <p className="text-[10px] leading-relaxed text-red-600">{blueprintError}</p>}
    </div>
  );
};
