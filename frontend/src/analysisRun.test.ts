import test from 'node:test';
import assert from 'node:assert/strict';
import { correspondenceValue, recorded, readWorkflowStream, type FgwResult } from './analysisRun.ts';

const result = {
  rows: 2,
  columns: 3,
  bestStart: 0,
  p: [0.5, 0.5],
  q: [0.25, 0.5, 0.25],
  starts: [{ correspondence: [0.25, 0.25, 0, 1e-12, 0.25, 0.25 - 1e-12] }],
} as unknown as FgwResult;
test('heatmap uses row-major indices and original marginal denominators', () => {
  assert.equal(correspondenceValue(result, 0, 1, 'share'), 0.5);
  assert.equal(correspondenceValue(result, 1, 1, 'mass'), 0.25);
  assert.equal(correspondenceValue(result, 0, 2, 'share'), 0);
  assert.equal(correspondenceValue(result, 1, 0, 'share'), 2e-12);
  // Filtering to row 1 / column 0 does not renormalise the visible subset.
  assert.equal(correspondenceValue(result, [1][0], [0][0], 'share'), 2e-12);
  assert.equal(correspondenceValue(result, 2, 0, 'share'), null);
});
test('missing matrix values and financial evidence remain distinct from zero', () => {
  assert.equal(correspondenceValue({ ...result, starts: [] }, 0, 0, 'share'), null);
  assert.equal(recorded(null), 'Unavailable');
  assert.equal(recorded('0.000000'), '0.000000');
  assert.equal(recorded('9007199254740993.123456'), '9007199254740993.123456');
});
test('workflow stream handles split JSON and final unterminated events', async () => {
  const encoder = new TextEncoder();
  const stream = new ReadableStream({
    start(controller) {
      for (const chunk of ['{"type":"gr', 'aph","sequence":1}\n{"type":"completed","sequence":2}'])
        controller.enqueue(encoder.encode(chunk));
      controller.close();
    },
  });
  const events: string[] = [];
  await readWorkflowStream(new Response(stream), (event) => events.push(event.type));
  assert.deepEqual(events, ['graph', 'completed']);
});

test('large events are parsed from chunks without whole-line JSON.parse', async () => {
  const encoder = new TextEncoder();
  const rows = Array.from({ length: 20_000 }, (_, i) => ({
    issueLineId: `issue-${i}`,
    reason: '记录 evidence',
    amount: '123.4500',
  }));
  const originalParse = JSON.parse;
  const wire = JSON.stringify({ type: 'production-trees', data: rows }) + '\n{"type":"completed"}\n';
  let offset = 0;
  const stream = new ReadableStream({
    pull(controller) {
      if (offset === wire.length) {
        controller.close();
        return;
      }
      const next = Math.min(offset + 1024, wire.length);
      controller.enqueue(encoder.encode(wire.slice(offset, next)));
      offset = next;
    },
  });
  const events: unknown[] = [];
  JSON.parse = ((text: string) => {
    assert.ok(text.length < 1024, 'Do not parse a complete event string');
    return originalParse(text);
  }) as typeof JSON.parse;
  try {
    await readWorkflowStream(new Response(stream), (event) => events.push(event));
  } finally {
    JSON.parse = originalParse;
  }
  assert.deepEqual(events, [{ type: 'production-trees', data: rows }, { type: 'completed' }]);
});

test('workflow stream preserves UTF-8 across byte boundaries', async () => {
  const bytes = new TextEncoder().encode('{"type":"error","message":"Evidence: £ and 记录"}\n');
  let offset = 0;
  const events: unknown[] = [];
  await readWorkflowStream(
    new Response(
      new ReadableStream({
        pull(controller) {
          if (offset === bytes.length) controller.close();
          else controller.enqueue(bytes.slice(offset, ++offset));
        },
      }),
    ),
    (event) => events.push(event),
  );
  assert.deepEqual(events, [{ type: 'error', message: 'Evidence: £ and 记录' }]);
});

test('truncated workflow events fail and cancel the response reader', async () => {
  await assert.rejects(readWorkflowStream(new Response('{"type":"completed","data":['), () => {}));
  let cancelled = false;
  await assert.rejects(
    readWorkflowStream(
      new Response(
        new ReadableStream({
          start(controller) {
            controller.enqueue(new TextEncoder().encode('{invalid}'));
          },
          cancel() {
            cancelled = true;
          },
        }),
      ),
      () => {},
    ),
  );
  assert.equal(cancelled, true);
});
