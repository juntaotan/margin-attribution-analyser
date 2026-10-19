import React from 'react';

/** How the warehouse values are to be interpreted when comparing the two periods. */
export interface DataInterpretation {
  currency: string;
  costScope: string;
  /** The imported records cover both periods and complete the selected batches. */
  complete: boolean;
  /** Output values repeated on issue rows are batch totals. */
  repeated: boolean;
}

interface DataInterpretationControlsProps {
  value: DataInterpretation;
  onChange: (change: Partial<DataInterpretation>) => void;
}

/**
 * Collapsible inputs for the currency, cost scope and data-completeness declarations. Complete comparisons need
 * both declarations confirmed, which the summary line points out.
 */
export const DataInterpretationControls: React.FC<DataInterpretationControlsProps> = ({ value, onChange }) => (
  <details className="bg-white border-b px-4 py-2 text-xs shrink-0 group transition-all">
    <summary className="cursor-pointer font-medium text-slate-700 flex items-center justify-between hover:text-slate-900 select-none">
      <span>
        Data interpretation {(!value.complete || !value.repeated) && '· confirmation required for complete comparisons'}
      </span>
      <span className="text-[10px] text-slate-400 group-open:rotate-180 transition-transform">▼</span>
    </summary>
    <div className="flex flex-wrap gap-4 py-3 pt-2 text-slate-700">
      <label>
        Currency{' '}
        <input
          aria-label="Warehouse currency"
          className="border border-slate-300 rounded px-2 py-1 ml-2 w-20 text-xs font-mono"
          placeholder="NZD"
          value={value.currency}
          onChange={(e) => onChange({ currency: e.target.value })}
        />
      </label>
      <label>
        Cost scope{' '}
        <input
          aria-label="Warehouse cost scope"
          className="border border-slate-300 rounded px-2 py-1 ml-2 text-xs"
          placeholder="Recorded valuation"
          value={value.costScope}
          onChange={(e) => onChange({ costScope: e.target.value })}
        />
      </label>
      <label className="flex items-center gap-2">
        <input
          type="checkbox"
          checked={value.complete}
          onChange={(e) => onChange({ complete: e.target.checked })}
          className="rounded text-period-actual focus:ring-period-actual"
        />{' '}
        Imported records cover both periods and complete selected batches
      </label>
      <label className="flex items-center gap-2">
        <input
          type="checkbox"
          checked={value.repeated}
          onChange={(e) => onChange({ repeated: e.target.checked })}
          className="rounded text-period-actual focus:ring-period-actual"
        />{' '}
        Output values repeated on issue rows are batch totals
      </label>
    </div>
  </details>
);
