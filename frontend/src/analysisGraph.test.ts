import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildAnalysisGraph, csrToAdjacency, formatAmount, nodeIdentity, readAnalysisStream,
  type AnalysisNode, type StreamCsrGraph,
} from './analysisGraph.ts';

test('keeps distinct node values, multiple children, and shared descendants', () => {
  const produced: AnalysisNode = { inventoryId: 'COMP', quantity: 50, cost: null };
  const consumed: AnalysisNode = { inventoryId: 'COMP', quantity: 50, cost: 3500 };
  const other: AnalysisNode = { inventoryId: 'MAT', quantity: 20, cost: 0 };
  const finished: AnalysisNode = { inventoryId: 'FINISHED', quantity: 100, cost: null };

  const graph = buildAnalysisGraph([
    { upstream: produced, downstream: [consumed, other, consumed] },
    { upstream: consumed, downstream: [finished] },
    { upstream: other, downstream: [finished] },
    { upstream: finished, downstream: [] },
  ]);

  assert.notEqual(nodeIdentity(produced), nodeIdentity(consumed));
  assert.equal(graph.nodes.length, 4);
  assert.equal(graph.edges.length, 4);
  assert.deepEqual(
    graph.edges.map(({ source, target }) => [source, target]),
    [
      [nodeIdentity(produced), nodeIdentity(consumed)],
      [nodeIdentity(produced), nodeIdentity(other)],
      [nodeIdentity(consumed), nodeIdentity(finished)],
      [nodeIdentity(other), nodeIdentity(finished)],
    ],
  );
});

test('keeps a standalone node and renders missing cost differently from zero', () => {
  const target: AnalysisNode = { inventoryId: 'ONLY', quantity: 1, cost: null };
  const graph = buildAnalysisGraph([{ upstream: target, downstream: [] }]);

  assert.deepEqual(graph.nodes, [{ id: nodeIdentity(target), node: target }]);
  assert.deepEqual(graph.edges, []);
  assert.equal(formatAmount(null), '—');
  assert.equal(formatAmount(0), '$0.00 NZD');
});

test('different quantities also produce different canvas IDs', () => {
  const first: AnalysisNode = { inventoryId: 'PART', quantity: 1, cost: 5 };
  const second: AnalysisNode = { inventoryId: 'PART', quantity: 2, cost: 5 };

  assert.notEqual(nodeIdentity(first), nodeIdentity(second));
});

test('keeps CSR edge order and parses graph before diff across split network chunks', async () => {
  const graph: StreamCsrGraph = {
    nodes: [
      { id: 'node-m', position: 0, inventoryId: 'M', quantity: 1, cost: 10 },
      { id: 'node-p', position: 1, inventoryId: 'P', quantity: 1, cost: 20 },
    ],
    offset: [0, 2, 2], successors: [1, 1], edgeIds: ['edge-0', 'edge-1'],
  };
  assert.deepEqual(csrToAdjacency(graph)[0].downstream, [graph.nodes[1], graph.nodes[1]]);

  const payload = [
    JSON.stringify({ type: 'graph', analysisId: 'run-1', actualGraph: graph, comparableGraph: graph }),
    JSON.stringify({ type: 'diff', analysisId: 'run-1', paths: [
      { positions: [0, 1], edgeIndexes: [1], nodeIds: ['node-m', 'node-p'],
        edgeIds: ['edge-1'], endReason: 'THRESHOLD_EXCEEDED', endingCostDifference: 15 },
    ] }),
  ].join('\n') + '\n';
  const encoded = new TextEncoder().encode(payload);
  const chunks = [encoded.slice(0, 7), encoded.slice(7, 48), encoded.slice(48)];
  const response = new Response(new ReadableStream({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(chunk));
      controller.close();
    },
  }));
  const events: string[] = [];
  await readAnalysisStream(response, (event) => events.push(event.type));
  assert.deepEqual(events, ['graph', 'diff']);
});
