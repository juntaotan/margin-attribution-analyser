import React from 'react';
import { AlertCircle, Database, LoaderCircle, Play } from 'lucide-react';
import { formatResultCell } from './resultFormatting';
import { OutputFormat, SemanticQueryResult } from './types';
import { PROMPT_CHANGED_MESSAGE } from './usePlaceholderRun';

interface QueryResultCardProps {
  queryResult?: SemanticQueryResult;
  queryError?: string;
  format?: OutputFormat;
  canApply: boolean;
  isPromptDirty: boolean;
  applyLoading: boolean;
  applySuccess?: string;
  applyError?: string;
  onApply: () => void;
}

/** Shows the query result or error for the generated blueprint, with the action to save it into the document. */
export const QueryResultCard: React.FC<QueryResultCardProps> = ({
  queryResult,
  queryError,
  format,
  canApply,
  isPromptDirty,
  applyLoading,
  applySuccess,
  applyError,
  onApply,
}) => (
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

    {queryError && (
      <div className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 p-2.5 text-[11px] text-red-700">
        <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
        <span>{queryError}</span>
      </div>
    )}
    {queryResult && <ResultTable result={queryResult} format={format} />}

    <button
      type="button"
      onClick={onApply}
      disabled={applyLoading || !canApply || isPromptDirty}
      className="mt-3 inline-flex w-full items-center justify-center gap-1.5 rounded-lg bg-emerald-600 px-3 py-2 text-xs font-semibold text-white transition-colors hover:bg-emerald-700 disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-500"
    >
      {applyLoading ? <LoaderCircle className="h-3.5 w-3.5 animate-spin" /> : <Play className="h-3.5 w-3.5" />}
      {applyLoading ? 'Saving...' : 'Apply'}
    </button>
    {isPromptDirty && <p className="mt-2 text-[10px] text-amber-700 leading-relaxed">{PROMPT_CHANGED_MESSAGE}</p>}
    {applySuccess && <p className="mt-2 text-[10px] text-emerald-700">{applySuccess}</p>}
    {applyError && <p className="mt-2 text-[10px] text-red-600">{applyError}</p>}
  </div>
);

const ResultTable: React.FC<{ result: SemanticQueryResult; format?: OutputFormat }> = ({ result, format }) => (
  <div className="overflow-x-auto rounded-lg border border-slate-200">
    <table className="min-w-full divide-y divide-slate-200 text-left text-[11px]">
      <thead className="bg-slate-50 text-slate-500">
        <tr>
          {result.columns.map((column) => (
            <th key={column} className="px-3 py-2 font-semibold">
              {column}
            </th>
          ))}
        </tr>
      </thead>
      <tbody className="divide-y divide-slate-100 bg-white font-mono text-slate-800">
        {result.rows.length === 0 ? (
          <tr>
            <td colSpan={Math.max(result.columns.length, 1)} className="px-3 py-4 text-center font-sans text-slate-400">
              No records found for the specified period
            </td>
          </tr>
        ) : (
          result.rows.map((row, rowIndex) => (
            <tr key={rowIndex}>
              {result.columns.map((column) => (
                <td key={column} className="whitespace-nowrap px-3 py-2">
                  {formatResultCell(row[column], format)}
                </td>
              ))}
            </tr>
          ))
        )}
      </tbody>
    </table>
  </div>
);
