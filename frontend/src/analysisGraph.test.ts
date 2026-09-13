import assert from 'node:assert/strict';
import test from 'node:test';

import { buildAnalysisGraph, formatAmount, nodeIdentity, type AnalysisNode } from './analysisGraph.ts';

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
