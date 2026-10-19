import React, { useEffect, useRef, useState } from 'react';
import { readWorkflowStream, type AnalysisOutcome, type PeriodSnapshot, type WorkflowEvent } from '../../analysisRun';
import { failProgress, initialProgress, reduceProgress, type AnalysisProgress } from '../../analysisProgress';

/** The part of an outcome needed to draw the graphs; available before the comparison completes. */
export type AnalysisDisplay = Pick<AnalysisOutcome, 'analysisId' | 'actual' | 'comparable' | 'branches'>;

interface StreamUpdates {
  setProgress: React.Dispatch<React.SetStateAction<AnalysisProgress | null>>;
  setDisplay: React.Dispatch<React.SetStateAction<AnalysisDisplay | null>>;
  setOutcome: (outcome: AnalysisOutcome) => void;
}

async function startAnalysisStream(body: unknown, signal: AbortSignal): Promise<Response> {
  const response = await fetch('/api/v1/analysis/run/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/x-ndjson' },
    signal,
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    const errorBody = await response.json().catch(() => ({}));
    throw new Error(errorBody.message || `Analysis failed (${response.status})`);
  }
  return response;
}

/**
 * Applies workflow events as they arrive: progress on every event, the graphs once both the period snapshots and
 * the abnormal branches are known, and the outcome on completion. Events from a superseded run are ignored.
 *
 * @throws Error when the stream reports an error or ends without a completed result.
 */
async function consumeStream(response: Response, isCurrent: () => boolean, updates: StreamUpdates) {
  let snapshots: PeriodSnapshot[] | null = null;
  let completed = false;
  let streamedError: string | null = null;
  await readWorkflowStream(response, (event: WorkflowEvent) => {
    if (!isCurrent()) return;
    updates.setProgress((previous) => previous && reduceProgress(previous, event, performance.now()));
    if (event.type === 'graph') snapshots = event.data as PeriodSnapshot[];
    else if (event.type === 'branches' && snapshots)
      updates.setDisplay({
        analysisId: event.analysisId!,
        actual: snapshots[0],
        comparable: snapshots[1],
        branches: event.data as AnalysisOutcome['branches'],
      });
    else if (event.type === 'completed') {
      completed = true;
      const value = event.data as AnalysisOutcome;
      updates.setOutcome(value);
      updates.setDisplay((previous) => previous ?? value);
    } else if (event.type === 'error') streamedError = event.message || 'Analysis failed.';
  });
  if (streamedError) throw new Error(streamedError);
  if (!completed) throw new Error('Analysis stream ended before a completed result.');
}

/**
 * Runs the streamed analysis workflow and holds its progress, graphs, outcome and error.
 *
 * Starting a run aborts the previous one, and only the latest run may update state. The request is also aborted
 * when the component unmounts. Failures of the latest run are reported in `error` and in the progress display.
 */
export function useAnalysisRun() {
  const [isAnalyzing, setIsAnalyzing] = useState(false);
  const [progress, setProgress] = useState<AnalysisProgress | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<AnalysisOutcome | null>(null);
  const [display, setDisplay] = useState<AnalysisDisplay | null>(null);
  const request = useRef<AbortController | null>(null);
  const version = useRef(0);

  useEffect(
    () => () => {
      version.current++;
      request.current?.abort();
    },
    [],
  );

  const run = async (body: unknown) => {
    const controller = new AbortController();
    request.current?.abort();
    request.current = controller;
    const current = ++version.current;
    setIsAnalyzing(true);
    setProgress(initialProgress(performance.now()));
    setError(null);
    setOutcome(null);
    setDisplay(null);
    try {
      const response = await startAnalysisStream(body, controller.signal);
      await consumeStream(response, () => current === version.current, { setProgress, setDisplay, setOutcome });
    } catch (err) {
      if (current === version.current && !controller.signal.aborted) {
        const message = err instanceof Error ? err.message : 'Analysis failed.';
        setError(message);
        setProgress((previous) => previous && failProgress(previous, message, performance.now()));
      }
    } finally {
      if (current === version.current) setIsAnalyzing(false);
    }
  };

  return { isAnalyzing, progress, error, setError, outcome, display, run };
}
