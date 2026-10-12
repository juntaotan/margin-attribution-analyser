import React from 'react';
import { CircleCheck, CircleDashed, CircleMinus, CircleX, LoaderCircle, TriangleAlert } from 'lucide-react';
import { currentStep, formatDuration, type AnalysisProgress, type ProgressStep } from '../../analysisProgress';

const ICONS = {
  pending: <CircleDashed size={14} className="text-slate-400" aria-hidden />,
  running: <LoaderCircle size={14} className="animate-spin text-[#466987]" aria-hidden />,
  done: <CircleCheck size={14} className="text-[#466987]" aria-hidden />,
  skipped: <CircleMinus size={14} className="text-slate-400" aria-hidden />,
  failed: <CircleX size={14} className="text-[#A64242]" aria-hidden />,
};
const STATE_TEXT = { pending: 'Pending', running: 'Running', done: 'Done', skipped: 'Skipped', failed: 'Failed' };

const Step: React.FC<{ step: ProgressStep; pairs: AnalysisProgress['pairs'] }> = ({ step, pairs }) => {
  const done = pairs.executed + pairs.rejected + pairs.failed;
  return <li className={`flex items-center gap-1.5 min-w-0 ${step.state === 'pending' || step.state === 'skipped' ? 'text-slate-400' : ''}`}>
    {ICONS[step.state]}<span className="sr-only">{STATE_TEXT[step.state]}:</span>
    <span className={step.state === 'running' ? 'font-medium text-[#466987]' : step.state === 'failed' ? 'text-[#A64242]' : ''}>{step.label}</span>
    {step.warning && <TriangleAlert size={12} className="text-[#866322]" aria-label="Incomplete evidence" />}
    {step.key === 'comparison' && step.state === 'running' && pairs.total != null && pairs.total > 0 &&
      <span role="progressbar" aria-label="Compared pairs" aria-valuemin={0} aria-valuemax={pairs.total} aria-valuenow={done} className="w-20 h-1.5 rounded bg-slate-200 overflow-hidden">
        <span className="block h-full bg-[#466987] transition-[width]" style={{ width: `${(100 * done) / pairs.total}%` }} />
      </span>}
    {step.summary && (step.state === 'running' || step.state === 'done') && <span className="text-slate-500">{step.summary}</span>}
    {step.note && <span className={step.state === 'failed' ? 'text-[#A64242]' : 'text-slate-400'}>{step.note}</span>}
    {step.finishedAt != null && step.startedAt != null && step.state !== 'skipped' && <span className="text-slate-400 tabular-nums" title="Measured in the browser, including transfer time">{formatDuration(step.finishedAt - step.startedAt)}</span>}
  </li>;
};

const StepList: React.FC<{ progress: AnalysisProgress; vertical?: boolean }> = ({ progress, vertical }) =>
  <ol className={vertical ? 'flex flex-col gap-1.5 py-2' : 'flex flex-wrap items-center gap-x-1 gap-y-1'}>
    {progress.steps.map((step, i) => <React.Fragment key={step.key}>
      {!vertical && i > 0 && <li aria-hidden className="w-4 h-px bg-slate-300" />}
      <Step step={step} pairs={progress.pairs} />
    </React.Fragment>)}
  </ol>;

/**
 * Step-by-step status of one analysis run, shown beneath the scope controls.
 *
 * While running, wide screens show every step with live backend counts; narrow screens show the
 * active step with an expandable list. After the run, a one-line summary (`summary`) is shown and
 * the step list stays available in a disclosure. Only step transitions and the final state are
 * announced to assistive technology, not each compared pair. Timings are measured in the browser.
 *
 * @param progress state from `reduceProgress`, or null before the first run
 * @param summary content for the finished-run line, such as final status and pair counts
 * @param idle text shown before any run
 */
export const AnalysisProgressBar: React.FC<{ progress: AnalysisProgress | null; summary: React.ReactNode; idle: string }> = ({ progress, summary, idle }) => {
  if (!progress) return <div className="px-4 py-2 bg-white border-b text-xs shrink-0" role="status" aria-live="polite">{idle}</div>;
  const { position, count, step } = currentStep(progress);
  const running = progress.finishedAt == null;
  const announcement = running ? `Step ${position} of ${count}: ${step.runningLabel}` : progress.status === 'FAILED' ? `Analysis failed at ${step.label}` : `Analysis finished: ${progress.status}`;
  const overall = running ? progress.steps.filter(s => s.state === 'done').length / count : 1;
  return <div className="px-4 py-2 bg-white border-b text-xs shrink-0">
    <span className="sr-only" role="status" aria-live="polite">{announcement}</span>
    {running ? <>
      <div className="hidden lg:block"><StepList progress={progress} /></div>
      <details className="lg:hidden">
        <summary className="cursor-pointer"><span className="inline-flex items-center gap-2 align-middle">
          {ICONS.running}<span>Step {position} of {count} · {step.runningLabel}{step.summary ? ` · ${step.summary}` : ''}</span>
        </span></summary>
        <StepList progress={progress} vertical />
      </details>
      <div className="lg:hidden mt-1.5 h-1 rounded bg-slate-200 overflow-hidden"><div className="h-full bg-[#466987] transition-[width]" style={{ width: `${100 * overall}%` }} /></div>
    </> : <details>
      <summary className="cursor-pointer"><span className="inline-flex flex-wrap items-center gap-x-4 gap-y-1 align-middle">
        {progress.status === 'FAILED' ? ICONS.failed : progress.steps.some(s => s.warning) ? <TriangleAlert size={14} className="text-[#866322]" aria-hidden /> : ICONS.done}
        {summary}
        <span className="text-slate-400 tabular-nums" title="Measured in the browser, including transfer time">{formatDuration(progress.finishedAt! - progress.startedAt)} total</span>
      </span></summary>
      <div className="pt-2"><div className="hidden lg:block"><StepList progress={progress} /></div><div className="lg:hidden"><StepList progress={progress} vertical /></div></div>
    </details>}
  </div>;
};
