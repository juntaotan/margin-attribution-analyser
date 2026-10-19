import { useEffect, useState } from 'react';

type Layer = 'lake' | 'raw' | 'warehouse';
type Rows = { columns: string[]; rows: (string | null)[][]; total: number };
type Files = { items: { key: string; size: string; modified: string }[]; hasMore: boolean; bucket: string };

async function read<T>(path: string, signal: AbortSignal): Promise<T> {
  const response = await fetch(`/api/data-browser/${path}`, { signal });
  if (!response.ok) {
    const body = await response.text();
    let message = `Data request failed (${response.status}).`;
    try {
      const problem = JSON.parse(body);
      message = problem.detail || problem.message || message;
    } catch {
      /* Keep the status when a proxy returns HTML. */
    }
    throw new Error(message);
  }
  return response.json();
}

const PAGE_SIZE = 50;
const LAYER_LABELS: Record<Layer, string> = { lake: 'Data Lake', raw: 'Raw Database', warehouse: 'Data Warehouse' };

/**
 * Loads one page of the selected layer: object-store files for the data lake, or table rows for the raw and
 * warehouse databases. Database layers first load their table list and keep the current table when it still
 * exists. Superseded requests are aborted and their errors ignored.
 */
function useDataBrowser() {
  const [layer, setLayer] = useState<Layer>('lake');
  const [tables, setTables] = useState<string[]>([]);
  const [table, setTable] = useState('');
  const [page, setPage] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const [rows, setRows] = useState<Rows | null>(null);
  const [files, setFiles] = useState<Files | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [tablesLoading, setTablesLoading] = useState(false);

  useEffect(() => {
    if (layer === 'lake') return;
    const controller = new AbortController();
    setTablesLoading(true);
    setError('');
    read<string[]>(`tables?layer=${layer}`, controller.signal)
      .then((list) => {
        setTables(list);
        setTable((current) => (list.includes(current) ? current : list[0] || ''));
        setPage(0);
      })
      .catch((reason) => {
        if (!controller.signal.aborted) setError(reason.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setTablesLoading(false);
      });
    return () => controller.abort();
  }, [layer, refresh]);

  useEffect(() => {
    if (layer !== 'lake' && (!table || tablesLoading)) return;
    const controller = new AbortController();
    setLoading(true);
    setError('');
    setRows(null);
    setFiles(null);
    const path =
      layer === 'lake'
        ? `files?page=${page}&size=${PAGE_SIZE}`
        : `rows?layer=${layer}&table=${encodeURIComponent(table)}&page=${page}&size=${PAGE_SIZE}`;
    read<Files | Rows>(path, controller.signal)
      .then((data) => {
        if (layer === 'lake') setFiles(data as Files);
        else setRows(data as Rows);
      })
      .catch((reason) => {
        if (!controller.signal.aborted) setError(reason.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [layer, table, page, refresh, tablesLoading]);

  /** Switches layer and clears everything loaded for the previous one. */
  const selectLayer = (value: Layer) => {
    setLayer(value);
    setTablesLoading(false);
    setLoading(false);
    setTable('');
    setTables([]);
    setPage(0);
    setRows(null);
    setFiles(null);
    setError('');
  };

  const selectTable = (value: string) => {
    setTable(value);
    setPage(0);
  };

  return {
    layer,
    tables,
    table,
    page,
    rows,
    files,
    error,
    tablesLoading,
    busy: loading || tablesLoading,
    hasMore: layer === 'lake' ? files?.hasMore : rows && (page + 1) * PAGE_SIZE < rows.total,
    selectLayer,
    selectTable,
    setPage,
    reload: () => setRefresh((value) => value + 1),
  };
}

/** Browse stored source files and paginated database records from the existing import pipeline. */
export function DataBrowser() {
  const browser = useDataBrowser();
  const { layer, table, page, rows, files, error, busy } = browser;
  const empty = layer === 'lake' ? files?.items.length === 0 : !table || rows?.rows.length === 0;
  return (
    <main className="flex-1 min-w-0 flex flex-col p-6 gap-4 overflow-hidden">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">Data Browser</h1>
          <p className="text-sm text-slate-500 mt-1">
            Explore source files, original imported tables and mapped warehouse records.
          </p>
        </div>
        <button className="border rounded px-3 py-2 text-sm bg-white" onClick={browser.reload} disabled={busy}>
          Refresh
        </button>
      </div>
      <div className="flex gap-2">
        {(['lake', 'raw', 'warehouse'] as Layer[]).map((value) => (
          <button
            key={value}
            className={`px-4 py-2 rounded text-sm ${layer === value ? 'bg-blue-600 text-white' : 'bg-white border'}`}
            onClick={() => browser.selectLayer(value)}
          >
            {LAYER_LABELS[value]}
          </button>
        ))}
      </div>
      {layer !== 'lake' && (
        <label className="text-sm flex gap-3 items-center">
          Table
          <select
            aria-label="Table"
            value={table}
            disabled={browser.tablesLoading}
            className="border rounded p-2 bg-white min-w-64"
            onChange={(event) => browser.selectTable(event.target.value)}
          >
            {browser.tables.length === 0 && <option value="">No tables available</option>}
            {browser.tables.map((name) => (
              <option key={name}>{name}</option>
            ))}
          </select>
          {rows && <span className="text-slate-500">{rows.total.toLocaleString()} records</span>}
        </label>
      )}
      {files && <p className="text-xs text-slate-500">Bucket: {files.bucket}</p>}
      {error && (
        <div role="alert" className="bg-red-50 text-red-700 p-3 rounded">
          {error}
        </div>
      )}
      <div className="flex-1 min-h-0 overflow-auto bg-white border rounded-lg">
        {busy ? (
          <p role="status" className="p-6 text-slate-500">
            Loading data…
          </p>
        ) : layer === 'lake' && files ? (
          <FileTable files={files} />
        ) : rows ? (
          <RowTable rows={rows} />
        ) : null}
        {!busy && !error && empty && (
          <p className="p-6 text-slate-500">
            No data available. Import files from Data Preparation to populate this layer.
          </p>
        )}
      </div>
      <div className="flex justify-between items-center text-sm">
        <span>
          Page {page + 1} · {PAGE_SIZE} records per page
        </span>
        <div className="flex gap-2">
          <button
            className="border bg-white rounded px-3 py-1 disabled:opacity-40"
            disabled={busy || page === 0}
            onClick={() => browser.setPage((value) => value - 1)}
          >
            Previous
          </button>
          <button
            className="border bg-white rounded px-3 py-1 disabled:opacity-40"
            disabled={busy || !browser.hasMore}
            onClick={() => browser.setPage((value) => value + 1)}
          >
            Next
          </button>
        </div>
      </div>
    </main>
  );
}

function FileTable({ files }: { files: Files }) {
  return (
    <table className="text-sm w-full text-left">
      <thead className="bg-slate-50 sticky top-0">
        <tr>
          {['Object key', 'Size (bytes)', 'Last modified', 'Download'].map((label) => (
            <th key={label} className="p-3">
              {label}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {files.items.map((file) => (
          <tr key={file.key} className="border-t">
            <td className="p-3 break-all font-mono text-xs">{file.key}</td>
            <td className="p-3">{file.size}</td>
            <td className="p-3 whitespace-nowrap">{new Date(file.modified).toLocaleString()}</td>
            <td className="p-3">
              <a className="text-blue-600" href={`/api/data-browser/download?key=${encodeURIComponent(file.key)}`}>
                Download
              </a>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** Database rows exactly as returned; SQL NULL is shown distinctly from empty text. */
function RowTable({ rows }: { rows: Rows }) {
  return (
    <table className="text-xs w-full text-left">
      <thead className="bg-slate-50 sticky top-0">
        <tr>
          {rows.columns.map((column) => (
            <th className="p-3 whitespace-nowrap" key={column}>
              {column}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.rows.map((record, index) => (
          <tr className="border-t hover:bg-slate-50" key={index}>
            {record.map((value, col) => (
              <td className="p-3 whitespace-nowrap font-mono" key={col}>
                {value === null ? <span className="text-slate-400">NULL</span> : value}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
