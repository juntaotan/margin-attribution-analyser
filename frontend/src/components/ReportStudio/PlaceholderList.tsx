import React from 'react';
import { AlertCircle, LoaderCircle, RefreshCw } from 'lucide-react';
import { DocumentContentControl, PlaceholderConfig, PlaceholderToken } from './types';

interface PlaceholderListProps {
  contentControls: DocumentContentControl[];
  placeholderTokens: PlaceholderToken[];
  persistedConfigs: Record<string, PlaceholderConfig>;
  activeTokenId: string;
  documentVersion?: number;
  controlsLoading: boolean;
  controlsError?: string;
  onRefresh: () => void;
  onSelect: (controlId: string) => void;
}

/** Lists the content controls found in the saved Word document and lets the user select one. */
export const PlaceholderList: React.FC<PlaceholderListProps> = ({
  contentControls,
  placeholderTokens,
  persistedConfigs,
  activeTokenId,
  documentVersion,
  controlsLoading,
  controlsError,
  onRefresh,
  onSelect,
}) => (
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
        onClick={onRefresh}
        disabled={controlsLoading}
        className="inline-flex shrink-0 items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-1 text-[10px] font-semibold text-slate-600 transition-colors hover:bg-slate-50 disabled:cursor-wait disabled:opacity-60"
        title="Save the document in ONLYOFFICE, then refresh this list"
      >
        {controlsLoading ? <LoaderCircle className="h-3 w-3 animate-spin" /> : <RefreshCw className="h-3 w-3" />}
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
          Create a control and enter its Alias in ONLYOFFICE, save the document, then refresh this list.
        </p>
      </div>
    )}

    <div className="max-h-52 space-y-1.5 overflow-y-auto pr-1 custom-scrollbar">
      {contentControls.map((control) => (
        <PlaceholderListItem
          key={control.id}
          control={control}
          resolvedValue={placeholderTokens.find((item) => item.id === control.id)?.resolvedValue}
          applied={(control.tag ? persistedConfigs[control.tag] : undefined)?.lastRun?.status === 'APPLIED'}
          selected={activeTokenId === control.id}
          onSelect={() => onSelect(control.id)}
        />
      ))}
    </div>
  </div>
);

interface PlaceholderListItemProps {
  control: DocumentContentControl;
  resolvedValue?: string;
  applied: boolean;
  selected: boolean;
  onSelect: () => void;
}

const PlaceholderListItem: React.FC<PlaceholderListItemProps> = ({
  control,
  resolvedValue,
  applied,
  selected,
  onSelect,
}) => (
  <button
    type="button"
    onClick={onSelect}
    className={`w-full rounded-lg border px-3 py-2 text-left transition-all ${
      selected
        ? 'border-sky-500 bg-sky-50/60 shadow-2xs'
        : 'border-slate-200/80 bg-white hover:border-slate-300 hover:bg-slate-50/70'
    }`}
  >
    <span className="flex items-center justify-between gap-2">
      <span className={`truncate text-xs font-semibold ${selected ? 'text-sky-900' : 'text-slate-700'}`}>
        {control.tag || control.alias}
      </span>
      <span className="flex shrink-0 items-center gap-1">
        {applied && (
          <span className="rounded bg-teal-100 px-1.5 py-0.5 text-[9px] font-medium text-teal-800">Applied</span>
        )}
        {control.occurrences > 1 && (
          <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[9px] font-medium text-slate-500">
            ×{control.occurrences}
          </span>
        )}
        {!control.tagged && (
          <span className="rounded bg-amber-100 px-1.5 py-0.5 text-[9px] font-medium text-amber-700">No Tag</span>
        )}
      </span>
    </span>
    <span className="mt-1 flex items-center justify-between gap-2 text-[10px]">
      <span className="truncate font-mono text-slate-400">
        {control.alias || `Word ID: ${control.wordId || 'unassigned'}`}
      </span>
      {resolvedValue && <span className="shrink-0 font-mono text-emerald-700">{resolvedValue}</span>}
    </span>
    {control.preview && <span className="mt-1 block truncate text-[10px] text-slate-400">{control.preview}</span>}
  </button>
);
