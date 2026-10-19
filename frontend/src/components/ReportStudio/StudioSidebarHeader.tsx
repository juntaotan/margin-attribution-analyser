import React from 'react';
import { Sparkles } from 'lucide-react';
import { PlaceholderRun } from './types';

interface RunBadge {
  label: string;
  className: string;
}

function runBadge(runStatus: PlaceholderRun['status'] | undefined, executed: boolean): RunBadge {
  if (runStatus === 'APPLIED') return { label: 'Applied to Document', className: 'bg-teal-100 text-teal-800' };
  if (runStatus === 'SUCCEEDED' || executed) {
    return { label: 'Ready / Executed', className: 'bg-emerald-100 text-emerald-800' };
  }
  if (runStatus === 'FAILED') return { label: 'Execution Failed', className: 'bg-rose-100 text-rose-800' };
  return { label: 'Pending Execution', className: 'bg-amber-100 text-amber-800' };
}

interface StudioSidebarHeaderProps {
  /** Whether a content control is selected; the run badge is shown only then. */
  hasSelection: boolean;
  /** Status of the selected control's latest saved run, if any. */
  runStatus?: PlaceholderRun['status'];
  /** Whether the selected control's token has been executed in this session. */
  executed: boolean;
}

/** Title bar of the Report Studio sidebar, with a badge for the selected placeholder's run status. */
export const StudioSidebarHeader: React.FC<StudioSidebarHeaderProps> = ({ hasSelection, runStatus, executed }) => {
  const badge = runBadge(runStatus, executed);
  return (
    <div className="p-4 border-b border-slate-200/80 flex items-center justify-between shrink-0 bg-white">
      <div className="flex items-center gap-2">
        <div className="w-7 h-7 rounded-lg bg-slate-100 text-slate-800 border border-slate-200/80 flex items-center justify-center font-bold">
          <Sparkles className="w-4 h-4 text-sky-600" />
        </div>
        <div>
          <h3 className="text-xs font-bold text-slate-900">AI Semantic Sensing &amp; Query Studio</h3>
          <p className="text-[10px] text-slate-500 font-mono">Prompt-to-Query Variable Inspector</p>
        </div>
      </div>
      {hasSelection && (
        <span className={`text-[10px] font-mono px-2 py-0.5 rounded-full font-semibold ${badge.className}`}>
          {badge.label}
        </span>
      )}
    </div>
  );
};
