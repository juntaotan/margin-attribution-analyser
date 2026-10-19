import React, { useState } from 'react';
import type { ImportLogEntry } from './useFileImport';

type DetailTab = 'log' | 'file';

/** Formats a byte count with a binary unit, and the exact byte count for sizes of 1 KB or more. */
const formatFileSize = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`;
  const unit = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), 3);
  return `${(bytes / 1024 ** unit).toFixed(2)} ${['B', 'KB', 'MB', 'GB'][unit]} (${bytes.toLocaleString()} bytes)`;
};

/** Tabbed panel showing the import activity log and information about the selected file. */
export const ImportDetailsPanel: React.FC<{ selectedFile: File; importLog: ImportLogEntry[] }> = ({
  selectedFile,
  importLog,
}) => {
  const [detailTab, setDetailTab] = useState<DetailTab>('log');
  return (
    <aside className="min-w-0 rounded-xl border border-slate-200 bg-slate-50/50 overflow-hidden">
      <DetailTabs detailTab={detailTab} onChange={setDetailTab} />
      <div
        role="tabpanel"
        id="import-panel-log"
        aria-labelledby="import-tab-log"
        hidden={detailTab !== 'log'}
        tabIndex={0}
        className="p-4"
      >
        <div role="log" aria-label="File import activity" className="max-h-64 overflow-y-auto space-y-3 text-xs">
          {importLog.length === 0 ? (
            <p className="text-slate-400">Select a file to view import activity.</p>
          ) : (
            importLog.map((entry, index) => (
              <div key={index} className="flex gap-3 items-start">
                <time className="shrink-0 font-mono text-slate-400">{entry.time}</time>
                <span className="text-slate-600 break-all">{entry.message}</span>
              </div>
            ))
          )}
        </div>
      </div>
      <div
        role="tabpanel"
        id="import-panel-file"
        aria-labelledby="import-tab-file"
        hidden={detailTab !== 'file'}
        tabIndex={0}
        className="p-4"
      >
        <dl className="text-xs divide-y divide-slate-200">
          <div className="py-3 first:pt-0 space-y-1.5">
            <dt className="text-slate-500">File Name</dt>
            <dd className="font-medium text-slate-800 break-all">{selectedFile.name}</dd>
          </div>
          <div className="py-3 space-y-1.5">
            <dt className="text-slate-500">File Size</dt>
            <dd className="font-mono text-slate-800">{formatFileSize(selectedFile.size)}</dd>
          </div>
          <div className="py-3 space-y-1.5">
            <dt className="text-slate-500">File Location</dt>
            <dd className="text-slate-800 break-all">
              {selectedFile.webkitRelativePath || 'Local device — full path unavailable in browser'}
            </dd>
          </div>
        </dl>
      </div>
    </aside>
  );
};

/** Tab list with arrow, Home and End key navigation, following the WAI-ARIA tabs pattern. */
const DetailTabs: React.FC<{ detailTab: DetailTab; onChange: (tab: DetailTab) => void }> = ({
  detailTab,
  onChange,
}) => (
  <div role="tablist" aria-label="Import details" className="flex border-b border-slate-200 bg-slate-100/70">
    {(['log', 'file'] as const).map((tab) => (
      <button
        key={tab}
        type="button"
        role="tab"
        id={`import-tab-${tab}`}
        aria-selected={detailTab === tab}
        aria-controls={`import-panel-${tab}`}
        tabIndex={detailTab === tab ? 0 : -1}
        onClick={() => onChange(tab)}
        onKeyDown={(event) => {
          if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
            event.preventDefault();
            const next = event.key === 'Home' ? 'log' : event.key === 'End' ? 'file' : tab === 'log' ? 'file' : 'log';
            onChange(next);
            document.getElementById(`import-tab-${next}`)?.focus();
          }
        }}
        className={`px-4 py-3 text-xs font-semibold border-b-2 transition-colors ${detailTab === tab ? 'border-blue-600 text-blue-700 bg-white' : 'border-transparent text-slate-500 hover:text-slate-700'}`}
      >
        {tab === 'log' ? 'Import Log' : 'File Information'}
      </button>
    ))}
  </div>
);
