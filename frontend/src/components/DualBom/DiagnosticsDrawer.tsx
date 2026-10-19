import React, { useState } from 'react';

const PAGE_SIZE = 10;

interface DiagnosticsDrawerProps {
  diagnostics: string[];
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** Bottom drawer listing analysis diagnostics ten at a time. The page resets whenever the drawer is remounted. */
export const DiagnosticsDrawer: React.FC<DiagnosticsDrawerProps> = ({ diagnostics, open, onOpenChange }) => {
  const [page, setPage] = useState(0);
  return (
    <details
      open={open}
      onToggle={(e) => onOpenChange((e.target as HTMLDetailsElement).open)}
      className="px-4 py-1.5 text-xs bg-slate-50 border-t border-slate-200/80 shrink-0 text-slate-700 transition-all"
    >
      <summary className="cursor-pointer font-medium flex items-center justify-between">
        <span className="flex items-center gap-1.5">
          <span className="w-1.5 h-1.5 rounded-full bg-amber-500" />
          <span>Diagnostics ({diagnostics.length}) · use graph boxes to browse abnormal paths</span>
        </span>
        <span className="text-[10px] text-slate-400">{open ? 'collapse' : 'expand'}</span>
      </summary>
      <div className="pt-2 pb-1">
        <ul className="max-h-32 overflow-auto space-y-1.5 font-mono text-slate-600 bg-white p-2 rounded border border-slate-200/60 mb-2">
          {diagnostics.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE).map((diagnostic, i) => (
            <li key={i} className="flex items-start gap-1.5">
              <span className="text-amber-600 shrink-0">⚠</span>
              <span>{diagnostic}</span>
            </li>
          ))}
        </ul>
        <div className="flex items-center gap-2">
          <button
            disabled={page === 0}
            onClick={() => setPage(page - 1)}
            className="px-2 py-0.5 border border-slate-200 rounded text-[11px] disabled:opacity-40 hover:bg-slate-100"
          >
            Previous
          </button>
          <span className="text-[11px] text-slate-600">
            {page + 1} / {Math.max(1, Math.ceil(diagnostics.length / PAGE_SIZE))}
          </span>
          <button
            disabled={(page + 1) * PAGE_SIZE >= diagnostics.length}
            onClick={() => setPage(page + 1)}
            className="px-2 py-0.5 border border-slate-200 rounded text-[11px] disabled:opacity-40 hover:bg-slate-100"
          >
            Next
          </button>
        </div>
      </div>
    </details>
  );
};
