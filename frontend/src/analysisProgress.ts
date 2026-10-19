import type { AnalysisOutcome, PairResult, PeriodSnapshot, WorkflowEvent } from './analysisRun';

/** Workflow stages shown in the progress bar, in execution order. */
export type StepKey = 'loading' | 'graph' | 'branches' | 'production-trees' | 'embeddings' | 'comparison';
/** Display state of one workflow stage. */
export type StepState = 'pending' | 'running' | 'done' | 'skipped' | 'failed';
/** Progress of one workflow stage, with timestamps from `performance.now()`. */
export interface ProgressStep {
  key: StepKey;
  label: string;
  runningLabel: string;
  state: StepState;
  startedAt: number | null;
  finishedAt: number | null;
  /** Counts reported by the backend for this step; never derived locally. */
  summary: string | null;
  /** Skip reason or failure message. */
  note: string | null;
  /** Completed with incomplete evidence, such as unresolved embeddings or rejected pairs. */
  warning: boolean;
}
/** Progress of a whole analysis run, built from the backend's workflow events. */
export interface AnalysisProgress {
  steps: ProgressStep[];
  startedAt: number;
  finishedAt: number | null;
  status: string | null;
  pairs: { total: number | null; executed: number; rejected: number; failed: number };
}

const STEPS: [StepKey, string, string][] = [
  ['loading', 'Load records', 'Loading warehouse records'],
  ['graph', 'Build graphs', 'Building period graphs'],
  ['branches', 'Find branches', 'Discovering abnormal branches'],
  ['production-trees', 'Restore trees', 'Restoring production trees'],
  ['embeddings', 'Embeddings', 'Preparing embeddings'],
  ['comparison', 'Compare pairs', 'Comparing production pairs'],
];
const SKIP_REASONS: Record<string, string> = {
  NO_ABNORMAL_BRANCHES: 'No abnormal branches',
  COMPARISON_INPUT_REQUIRED: 'Comparison input required',
  EXECUTION_UNAVAILABLE: 'Execution unavailable',
  CANCELLED: 'Cancelled',
  INSUFFICIENT_PRODUCTION_INSTANCES: 'No comparable pairs',
};

/**
 * Creates the progress state for a newly submitted run. Loading runs from submission until the
 * first workflow event, because warehouse snapshot loading precedes the workflow on the backend.
 *
 * @param now browser clock reading in milliseconds, used only for display timings
 */
export function initialProgress(now: number): AnalysisProgress {
  return {
    steps: STEPS.map(([key, label, runningLabel], i) => ({
      key,
      label,
      runningLabel,
      state: i === 0 ? 'running' : 'pending',
      startedAt: i === 0 ? now : null,
      finishedAt: null,
      summary: null,
      note: null,
      warning: false,
    })),
    startedAt: now,
    finishedAt: null,
    status: null,
    pairs: { total: null, executed: 0, rejected: 0, failed: 0 },
  };
}

const update = (progress: AnalysisProgress, key: StepKey, change: Partial<ProgressStep>): AnalysisProgress => ({
  ...progress,
  steps: progress.steps.map((step) => (step.key === key ? { ...step, ...change } : step)),
});
const finish = (progress: AnalysisProgress, key: StepKey, now: number, change: Partial<ProgressStep> = {}) =>
  update(progress, key, {
    state: 'done',
    startedAt: progress.steps.find((s) => s.key === key)!.startedAt ?? now,
    finishedAt: now,
    ...change,
  });
const plural = (count: number, noun: string) => `${count} ${noun}${count === 1 ? '' : 's'}`;
function pairSummary(pairs: AnalysisProgress['pairs']) {
  const done = pairs.executed + pairs.rejected + pairs.failed;
  return `${done} / ${pairs.total ?? '?'} · ${pairs.executed} executed · ${pairs.rejected} rejected · ${pairs.failed} failed`;
}

/**
 * Applies one workflow stream event. `stage` events start a step and the stage's own event
 * finishes it; `completed` marks unreached steps skipped with the reported status as reason.
 * Unknown event types only end the loading step.
 *
 * @param progress current state, returned unchanged once the run has finished
 * @param event parsed NDJSON workflow event
 * @param now browser clock reading in milliseconds
 */
export function reduceProgress(progress: AnalysisProgress, event: WorkflowEvent, now: number): AnalysisProgress {
  if (progress.finishedAt != null) return progress;
  if (event.type === 'error') return failProgress(progress, event.message || 'Analysis failed.', now);
  let next = progress.steps[0].state === 'running' ? finish(progress, 'loading', now) : progress;
  switch (event.type) {
    case 'stage': {
      const data = event.data as { stage: StepKey; total?: number };
      if (data.stage === 'comparison' && typeof data.total === 'number') {
        next = { ...next, pairs: { ...next.pairs, total: data.total } };
        return update(next, 'comparison', { state: 'running', startedAt: now, summary: pairSummary(next.pairs) });
      }
      return next.steps.some((s) => s.key === data.stage)
        ? update(next, data.stage, { state: 'running', startedAt: now })
        : next;
    }
    case 'graph': {
      const [actual, comparable] = event.data as PeriodSnapshot[];
      const diagnostics = actual.diagnostics.length + comparable.diagnostics.length;
      return finish(next, 'graph', now, {
        summary: `${actual.graph.nodes.length} / ${comparable.graph.nodes.length} nodes${diagnostics ? ` · ${plural(diagnostics, 'diagnostic')}` : ''}`,
      });
    }
    case 'branches':
      return finish(next, 'branches', now, { summary: plural((event.data as unknown[]).length, 'path') });
    case 'production-trees':
      return finish(next, 'production-trees', now, {
        summary: plural((event.data as AnalysisOutcome['selection']).trees.length, 'tree'),
      });
    case 'embeddings': {
      const data = event.data as { generated: number; unresolved: number } | null;
      return finish(
        next,
        'embeddings',
        now,
        data
          ? { summary: `${data.generated} generated · ${data.unresolved} unresolved`, warning: data.unresolved > 0 }
          : {},
      );
    }
    case 'comparison': {
      const disposition = (event.data as PairResult).disposition;
      const pairs = { ...next.pairs };
      if (disposition === 'EXECUTED') pairs.executed++;
      else if (disposition === 'REJECTED') pairs.rejected++;
      else pairs.failed++;
      next = update({ ...next, pairs }, 'comparison', {
        summary: pairSummary(pairs),
        warning: pairs.rejected + pairs.failed > 0,
      });
      return pairs.total != null && pairs.executed + pairs.rejected + pairs.failed >= pairs.total
        ? finish(next, 'comparison', now)
        : next;
    }
    case 'completed': {
      const status = (event.data as AnalysisOutcome).status;
      const reason = SKIP_REASONS[status] ?? status;
      return {
        ...next,
        status,
        finishedAt: now,
        steps: next.steps.map((step) =>
          step.state === 'running'
            ? {
                ...step,
                state: 'done',
                finishedAt: now,
                ...(step.key === 'comparison' && next.pairs.total === 0 ? { summary: reason, warning: true } : {}),
              }
            : step.state === 'pending'
              ? { ...step, state: 'skipped', note: reason }
              : step,
        ),
      };
    }
    default:
      return next;
  }
}

/**
 * Marks the running step (or the first unfinished one) failed and ends the run. Later steps stay
 * pending because they were never reached. Idempotent once the run has finished.
 */
export function failProgress(progress: AnalysisProgress, message: string, now: number): AnalysisProgress {
  if (progress.finishedAt != null) return progress;
  const failed = progress.steps.find((s) => s.state === 'running') ?? progress.steps.find((s) => s.state === 'pending');
  return {
    ...progress,
    status: 'FAILED',
    finishedAt: now,
    steps: progress.steps.map((step) =>
      step === failed ? { ...step, state: 'failed', finishedAt: now, note: message } : step,
    ),
  };
}

/** One-based position of the active step, for compact labels such as "Running · 4/6". */
export function currentStep(progress: AnalysisProgress): { position: number; count: number; step: ProgressStep } {
  const index = progress.steps.findIndex((s) => s.state === 'running' || s.state === 'failed');
  const position = index >= 0 ? index : progress.steps.filter((s) => s.state === 'done').length - 1;
  const clamped = Math.max(0, position);
  return { position: clamped + 1, count: progress.steps.length, step: progress.steps[clamped] };
}

/** Browser-measured display duration, not a backend timing. */
export function formatDuration(milliseconds: number): string {
  if (milliseconds < 60_000) return `${(milliseconds / 1000).toFixed(1)}s`;
  const seconds = Math.round(milliseconds / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}
