import { useEffect, useState } from 'react';
import type { AnalysisOutcome } from '../../analysisRun';
import type { DisplayQuery, DisplayResult } from '../../graphDisplay';

type WorkerReply = { sequence: number; result?: DisplayResult; error?: string };

/** Sends projection queries to the display worker; the returned function cancels delivery of the reply. */
export interface DisplayClient {
  request: (query: DisplayQuery, reply: (reply: WorkerReply) => void) => () => void;
}

/**
 * Starts one display worker holding both period graphs of `outcome`, shared by every projection of that outcome.
 * Replies are routed to their request by sequence. A worker failure answers all pending requests with an error.
 * The worker is terminated when the outcome changes or the component unmounts.
 */
export function useDisplayClient(outcome: Pick<AnalysisOutcome, 'actual' | 'comparable' | 'branches'>) {
  const [client, setClient] = useState<DisplayClient | null>(null);
  useEffect(() => {
    const worker = new Worker(new URL('../../graphDisplay.worker.ts', import.meta.url), { type: 'module' });
    const pending = new Map<number, (reply: WorkerReply) => void>();
    let sequence = 0;
    worker.onmessage = (event: MessageEvent<WorkerReply>) => {
      const reply = pending.get(event.data.sequence);
      pending.delete(event.data.sequence);
      reply?.(event.data);
    };
    worker.onerror = () => {
      pending.forEach((reply, id) => reply({ sequence: id, error: 'Graph display worker failed.' }));
      pending.clear();
    };
    worker.postMessage({ input: { actual: outcome.actual, comparable: outcome.comparable, branches: outcome.branches } });
    setClient({
      request: (query, reply) => {
        const id = ++sequence;
        pending.set(id, reply);
        worker.postMessage({ sequence: id, query });
        return () => pending.delete(id);
      },
    });
    return () => {
      pending.clear();
      worker.terminate();
    };
  }, [outcome]);
  return client;
}

/**
 * Holds one projection query and its latest result. Only the reply to the current query is applied, and `busy` is
 * set until it arrives. Expansions and collapses are kept in an undo history; changing the coverage or collapsing
 * all clears it.
 */
export function useGraphProjection(client: DisplayClient | null, initial: DisplayQuery) {
  const [query, updateQuery] = useState<DisplayQuery>(initial);
  const [history, setHistory] = useState<DisplayQuery[]>([]);
  const [result, setResult] = useState<DisplayResult | null>(null);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (!client) return;
    setBusy(true);
    return client.request(query, (reply) => {
      setBusy(false);
      setResult(reply.result ?? null);
      setError(reply.error ?? null);
    });
  }, [client, query]);
  const setQuery = (next: DisplayQuery) => {
    setBusy(true);
    updateQuery(next);
  };
  const change = (next: DisplayQuery) => {
    setHistory((previous) => [...previous, query]);
    setQuery(next);
  };

  return {
    query,
    result,
    busy,
    error,
    canUndo: history.length > 0,
    expand: (expandKey: string) =>
      change({ ...query, expanded: { ...query.expanded, [expandKey]: (query.expanded[expandKey] || 0) + 1 } }),
    /** Closes an open row and drops every expansion beneath it. */
    collapse: (itemKey: string) =>
      change({
        ...query,
        expanded: Object.fromEntries(
          Object.entries(query.expanded).filter(
            ([key]) => !key.startsWith(`${itemKey}#`) && !key.startsWith(`${itemKey}/`),
          ),
        ),
      }),
    undo: () => {
      setQuery(history[history.length - 1]);
      setHistory(history.slice(0, -1));
    },
    collapseAll: () => {
      setHistory([]);
      setQuery({ ...query, expanded: {} });
    },
    setCoverage: (coverage: number) => {
      setHistory([]);
      setQuery({ ...query, coverage, expanded: {} });
    },
  };
}
