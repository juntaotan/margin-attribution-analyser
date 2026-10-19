import { OutputFormat, SemanticQueryResult } from './types';

/** Returns the first column of the first row, which is the value a placeholder resolves to. */
export function firstResultValue(result: SemanticQueryResult | undefined): unknown {
  return result?.rows?.[0]?.[result.columns[0]];
}

/**
 * Formats a query value for display: currency numbers with two decimal places, percentages with one decimal place,
 * and anything else as plain text. Values that cannot be parsed as percentages are shown unchanged.
 */
export function formatResultValue(value: unknown, format: OutputFormat | undefined): string {
  if (format === 'currency' && typeof value === 'number') {
    return '$' + value.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
  if (format === 'percentage') {
    const num = typeof value === 'number' ? value : parseFloat(String(value));
    return !isNaN(num) ? `${num.toFixed(1)}%` : String(value);
  }
  return String(value);
}

/** Formats a result-table cell. Missing values show as `-`, and existing percentage text is kept as written. */
export function formatResultCell(value: unknown, format: OutputFormat | undefined): string {
  if (value == null) return '-';
  if (format === 'percentage' && String(value).endsWith('%')) return String(value);
  return formatResultValue(value, format);
}

/** Formats a timestamp as a short local time, for example `14:05`. */
export function shortTime(date: Date): string {
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}
