import test from 'node:test';
import assert from 'node:assert/strict';
import { currentStep, failProgress, formatDuration, initialProgress, reduceProgress, type AnalysisProgress } from './analysisProgress.ts';
import type { WorkflowEvent } from './analysisRun.ts';

const snapshot = (nodes: number, diagnostics: string[] = []) => ({ graph: { nodes: Array(nodes).fill({}), offset: [], successors: [] }, diagnostics });
const stage = (name: string, total?: number): WorkflowEvent => ({ type: 'stage', data: total == null ? { stage: name } : { stage: name, total } });
const pair = (disposition: string): WorkflowEvent => ({ type: 'comparison', data: { disposition } });
const completed = (status: string): WorkflowEvent => ({ type: 'completed', data: { status } });
function run(events: WorkflowEvent[]): AnalysisProgress {
  return events.reduce((progress, event, i) => reduceProgress(progress, event, (i + 1) * 100), initialProgress(0));
}
const states = (progress: AnalysisProgress) => progress.steps.map(s => s.state);
const discovery: WorkflowEvent[] = [
  stage('graph'), { type: 'graph', data: [snapshot(8, ['ACTUAL_ONLY:X']), snapshot(7)] },
  stage('branches'), { type: 'branches', data: [{}, {}] },
];
const trees: WorkflowEvent[] = [stage('production-trees'), { type: 'production-trees', data: { trees: [{}, {}, {}] } }];

test('loading runs from submission until the first workflow event', () => {
  const start = initialProgress(0);
  assert.deepEqual(states(start), ['running', 'pending', 'pending', 'pending', 'pending', 'pending']);
  assert.equal(currentStep(start).step.runningLabel, 'Loading warehouse records');
  const next = reduceProgress(start, stage('graph'), 400);
  assert.deepEqual(states(next).slice(0, 2), ['done', 'running']);
  assert.equal(next.steps[0].finishedAt, 400);
  assert.equal(currentStep(next).position, 2);
});

test('full run reports backend counts, live pair progress and completion', () => {
  const beforePairs = run([...discovery, ...trees, stage('embeddings'), { type: 'embeddings', data: { generated: 14, unresolved: 0 } }, stage('comparison', 3), pair('EXECUTED')]);
  assert.deepEqual(states(beforePairs), ['done', 'done', 'done', 'done', 'done', 'running']);
  assert.deepEqual(beforePairs.steps.slice(1, 5).map(s => s.summary), ['8 / 7 nodes · 1 diagnostic', '2 paths', '3 trees', '14 generated · 0 unresolved']);
  assert.equal(beforePairs.steps[5].summary, '1 / 3 · 1 executed · 0 rejected · 0 failed');
  assert.equal(currentStep(beforePairs).position, 6);
  const done = [pair('REJECTED'), pair('NUMERICAL_FAILURE'), completed('NUMERICAL_FAILURE')].reduce((p, e) => reduceProgress(p, e, 5000), beforePairs);
  assert.deepEqual(states(done), Array(6).fill('done'));
  assert.deepEqual(done.pairs, { total: 3, executed: 1, rejected: 1, failed: 1 });
  assert.equal(done.steps[5].warning, true);
  assert.equal(done.status, 'NUMERICAL_FAILURE');
  assert.equal(done.finishedAt, 5000);
});

test('no abnormal branches skips the remaining steps with the reported reason', () => {
  const progress = run([...discovery.slice(0, 3), { type: 'branches', data: [] }, completed('NO_ABNORMAL_BRANCHES')]);
  assert.deepEqual(states(progress), ['done', 'done', 'done', 'skipped', 'skipped', 'skipped']);
  assert.equal(progress.steps[2].summary, '0 paths');
  assert.deepEqual(progress.steps.slice(3).map(s => s.note), Array(3).fill('No abnormal branches'));
});

test('missing comparison input skips trees, embeddings and pairs', () => {
  const progress = run([...discovery, completed('COMPARISON_INPUT_REQUIRED')]);
  assert.deepEqual(states(progress), ['done', 'done', 'done', 'skipped', 'skipped', 'skipped']);
  assert.equal(progress.steps[3].note, 'Comparison input required');
});

test('zero planned pairs completes the comparison step with a warning', () => {
  const progress = run([...discovery, ...trees, stage('embeddings'), { type: 'embeddings', data: { generated: 2, unresolved: 1 } }, stage('comparison', 0), completed('INSUFFICIENT_PRODUCTION_INSTANCES')]);
  assert.deepEqual(states(progress), Array(6).fill('done'));
  assert.equal(progress.steps[4].warning, true);
  assert.equal(progress.steps[5].summary, 'No comparable pairs');
  assert.equal(progress.steps[5].warning, true);
});

test('an error event fails the running step and leaves later steps pending', () => {
  const progress = run([...discovery, ...trees, stage('embeddings'), { type: 'embeddings', data: { generated: 2, unresolved: 0 } },
    stage('comparison', 2), pair('EXECUTED'), { type: 'error', message: 'Solver stopped' }]);
  assert.deepEqual(states(progress), ['done', 'done', 'done', 'done', 'done', 'failed']);
  assert.equal(progress.steps[5].note, 'Solver stopped');
  assert.equal(progress.status, 'FAILED');
  assert.equal(currentStep(progress).position, 6);
  assert.equal(failProgress(progress, 'Analysis stream ended before a completed result.', 9999), progress, 'already finished');
});

test('an error before any workflow event fails loading', () => {
  const progress = reduceProgress(initialProgress(0), { type: 'error', message: 'Database unavailable' }, 50);
  assert.deepEqual(states(progress), ['failed', 'pending', 'pending', 'pending', 'pending', 'pending']);
  assert.equal(progress.steps[0].note, 'Database unavailable');
});

test('a stream ending without completion fails the unfinished step', () => {
  const progress = failProgress(run(discovery.slice(0, 2)), 'Analysis stream ended before a completed result.', 900);
  assert.deepEqual(states(progress), ['done', 'done', 'failed', 'pending', 'pending', 'pending']);
});

test('unknown events only end loading, and events after completion are ignored', () => {
  const progress = reduceProgress(initialProgress(0), { type: 'heartbeat' }, 10);
  assert.deepEqual(states(progress), ['done', 'pending', 'pending', 'pending', 'pending', 'pending']);
  const finished = run([...discovery, completed('COMPARISON_INPUT_REQUIRED')]);
  assert.equal(reduceProgress(finished, pair('EXECUTED'), 99999), finished);
});

test('display durations', () => {
  assert.equal(formatDuration(420), '0.4s');
  assert.equal(formatDuration(59_940), '59.9s');
  assert.equal(formatDuration(125_000), '2:05');
});
