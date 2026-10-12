import assert from 'node:assert/strict';
import { performance } from 'node:perf_hooks';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
/** Snapshot from material -> receiving-item edges, with optional sales and issue records. */
const snapshot = (ids, uses, sales = [], issues = []) => {
  const at = new Map(ids.map((id, i) => [id, i])); const outgoing = new Map(); const graph = { nodes: [], offset: [0], successors: [] };
  uses.forEach(([from, to]) => outgoing.set(from, [...outgoing.get(from) || [], at.get(to)]));
  ids.forEach(id => { graph.nodes.push({ inventoryId: id, quantity: '1', cost: id === '__COGS__' ? '123456.789012' : '10' }); graph.successors.push(...outgoing.get(id) || []); graph.offset.push(graph.successors.length); });
  return { graph, start: '2026-01-01', end: '2026-01-31', diagnostics: [], records: {
    sales: sales.map(([productId, cost], i) => ({ sourceId: `s${i}`, date: '2026-01-01', productId, quantity: '1', cost })),
    issues: issues.map(([productId, materialId, amount], i) => ({ lineId: `i${i}`, date: '2026-01-01', documentId: 'd', productionId: 'p', productId, materialId, quantity: '1', amount, outputQuantity: null, outputCost: null })) } };
};
const ids = ['__COGS__', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K'];
const uses = middle => [['A', '__COGS__'], [middle, 'A'], ['C', middle], ['D', 'C'], ['E', 'D'], ['F', 'E'], ['G', 'C'], ['H', 'G'], ['I', 'H'], ['J', '__COGS__'], ['K', 'J']];
const actualSnapshot = snapshot(ids, uses('B'), [['A', '900'], ['J', '50']], [['C', 'D', '40']]);
const comparableSnapshot = snapshot(ids.map(id => id === 'B' ? 'X' : id), uses('X'), [['A', '300'], ['J', '40']], [['C', 'D', '10']]);
const position = id => ids.indexOf(id);
const outcome = { analysisId: 'tree-fixture', status: 'COMPLETED', actual: actualSnapshot, comparable: comparableSnapshot,
  branches: [{ positions: ['F', 'E', 'D', 'C'].map(position), edges: [], endReason: 'THRESHOLD_EXCEEDED', endingCostDifference: '-500' }],
  selection: { trees: [], identities: [] }, embeddings: null, pairs: [], planned: 0, executed: 0, rejected: 0, failed: 0, execution: [], diagnostics: [] };
const browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || 'chrome' });
try {
  const page = await browser.newPage({ viewport: { width: 1700, height: 1000 } });
  const errors = []; let requests = 0;
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/**', route => {
    if (route.request().url().endsWith('/analysis/run/stream')) {
      requests++; outcome.analysisId = `tree-fixture-${requests}`;
      return route.fulfill({ status: 200, contentType: 'application/x-ndjson', body:
        JSON.stringify({ type: 'graph', analysisId: outcome.analysisId, data: [outcome.actual, outcome.comparable] }) + '\n' +
        JSON.stringify({ type: 'branches', analysisId: outcome.analysisId, data: outcome.branches }) + '\n' +
        JSON.stringify({ type: 'completed', analysisId: outcome.analysisId, data: outcome }) + '\n' });
    }
    return route.fulfill({ status: 404, contentType: 'application/json', body: '{"message":"Excluded from isolated fixture"}' });
  });
  await page.goto(process.env.MARGINTRACE_FRONTEND_URL || 'http://127.0.0.1:5173');
  await page.locator('summary').filter({ hasText: 'Data interpretation' }).click();
  await page.getByLabel('Warehouse currency').fill('NZD'); await page.getByLabel('Warehouse cost scope').fill('Recorded valuation');
  const actual = page.locator('[aria-label^="Actual "][aria-label$=" graph"]');
  const comparable = page.locator('[aria-label^="Comparable "][aria-label$=" graph"]');
  const ready = () => page.getByText('Preparing bounded graph…', { exact: true }).waitFor({ state: 'hidden' });
  const keys = pane => pane.locator('.react-flow__node:not([data-id^="column:"])').evaluateAll(nodes => nodes.map(node => node.dataset.id).sort());
  const transforms = pane => pane.locator('.react-flow__node').evaluateAll(nodes => Object.fromEntries(nodes.map(node => [node.dataset.id, node.style.transform])));
  const activate = async (pane, id) => {
    const node = pane.locator(`[data-id="${id}"]`); await node.waitFor({ state: 'visible' }); await node.focus();
    await page.waitForFunction(expected => document.activeElement?.getAttribute('data-id') === expected, id);
    await page.keyboard.press('Enter'); await ready();
  };
  const expectKeys = async expected => { for (const pane of [actual, comparable]) assert.deepEqual(await keys(pane), [...expected].sort()); };
  const aligned = async () => assert.deepEqual(await transforms(actual), await transforms(comparable));
  const initial = ['cogs', 'cogs/A', 'cogs/A#bridge', 'cogs/A#bridge/C', 'cogs/A#bridge/C/D', 'cogs/A#bridge/C/D/E', 'cogs/A#bridge/C/D/E/F', 'cogs/A#bridge/C#rest', 'cogs#abnormal', 'cogs#normal'];

  await page.getByRole('button', { name: /Run Analysis/i }).click(); await ready();
  await actual.locator('[data-id="cogs/A"]').waitFor();
  await expectKeys(initial); await aligned();
  // Columns: COGS, product, fold, abnormal C, D, E; F aligns in the material-terminal column.
  const x = async id => Number((await actual.locator(`[data-id="${id}"]`).evaluate(node => node.style.transform)).match(/translate\(([-\d.]+)px/)[1]);
  assert.ok(await x('cogs') < await x('cogs/A') && await x('cogs/A') < await x('cogs/A#bridge/C'));
  assert.equal(await x('cogs/A#bridge/C/D'), await x('cogs/A#bridge/C#rest') - (230 - 170) / 2, 'folds are centred in the same column');
  for (const label of ['Cost of Goods Sold', 'Products', 'Material terminals']) await actual.locator('.react-flow__node[data-id^="column:"]').getByText(label, { exact: true }).waitFor();
  await page.getByText('1 of 2 products · cover 600 of 610 NZD (98.3%) · ΔCOGS 610', { exact: true }).waitFor();
  if (process.env.DISPLAY_SCREENSHOT) await page.screenshot({ path: process.env.DISPLAY_SCREENSHOT.replace('.png', '-initial.png') });

  // The bridge expands in place in both panes; each period exposes its own intermediate node.
  await activate(comparable, 'cogs/A#bridge');
  await expectKeys(['cogs', 'cogs/A', 'cogs/A/B', 'cogs/A/X', 'cogs/A/B/C', 'cogs/A/B/C/D', 'cogs/A/B/C/D/E', 'cogs/A/B/C/D/E/F', 'cogs/A/B/C#rest',
    'cogs/A/X/C', 'cogs/A/X/C/D', 'cogs/A/X/C/D/E', 'cogs/A/X/C/D/E/F', 'cogs/A/X/C#rest', 'cogs#abnormal', 'cogs#normal']);
  await aligned();
  await actual.locator('[data-id="cogs/A/X"]').getByText('Not recorded in this period', { exact: true }).waitFor();
  await comparable.locator('[data-id="cogs/A/B"]').getByText('Not recorded in this period', { exact: true }).waitFor();
  await page.getByRole('button', { name: 'Undo expansion', exact: true }).click(); await ready(); await expectKeys(initial);

  // Remainder folds and the Normal box expand in place; selection does not move nodes.
  await activate(actual, 'cogs/A#bridge/C#rest');
  assert.ok((await keys(actual)).includes('cogs/A#bridge/C/G'));
  await activate(actual, 'cogs#normal'); assert.ok((await keys(comparable)).includes('cogs/J'));
  const before = await transforms(actual); await activate(actual, 'cogs/A'); assert.deepEqual(await transforms(actual), before);
  await page.getByRole('button', { name: 'Production evidence: A', exact: true }).waitFor();
  // Panes share one viewport.
  await actual.locator('.react-flow__controls-zoomin').click();
  const viewport = pane => pane.locator('.react-flow__viewport').evaluate(node => node.style.transform);
  await page.waitForFunction(() => { const [a, b] = document.querySelectorAll('.react-flow__viewport'); return a.style.transform === b.style.transform; });
  assert.equal(await viewport(actual), await viewport(comparable));
  await page.getByRole('button', { name: 'Collapse all', exact: true }).click(); await ready(); await expectKeys(initial);
  assert.deepEqual(await page.getByLabel('Initial COGS difference coverage').locator('option').allTextContents(), ['20%', '40%', '50%', '60%', '80%', '100%']);
  assert.equal(await page.getByLabel('Initial COGS difference coverage').inputValue(), '50');
  await page.getByLabel('Initial COGS difference coverage').selectOption('100'); await ready();
  assert.ok((await keys(actual)).includes('cogs/J'));
  await page.getByRole('button', { name: 'Settings & Parameters' }).click(); await page.getByRole('button', { name: /Dual-BOM Analysis/ }).click();
  assert.ok((await keys(actual)).includes('cogs/J')); assert.equal(requests, 1);

  // 25,000 materials under one product: bounded rendering and paged remainder.
  const count = 25_000; const large = ['__COGS__', 'P', ...Array.from({ length: count }, (_, i) => `M${i}`)];
  const largeUses = [['P', '__COGS__'], ...large.slice(2).map(id => [id, 'P'])];
  outcome.actual = snapshot(large, largeUses, [['P', '20']]); outcome.comparable = snapshot(large, largeUses, [['P', '10']]);
  outcome.branches = Array.from({ length: count }, (_, i) => ({ positions: [i + 2, 1], edges: [], endReason: 'THRESHOLD_EXCEEDED', endingCostDifference: `${i + 1}.000001` }));
  const start = performance.now(); await page.getByRole('button', { name: /Run Analysis/i }).click();
  await page.locator('section[data-analysis-id="tree-fixture-2"]').waitFor(); await ready(); await actual.locator('[data-id="cogs/P"]').waitFor();
  const renderMs = performance.now() - start;
  assert.ok((await keys(actual)).length <= 12, 'COGS, product, five anomalies, one remainder and two boxes');
  const fold = actual.locator('[data-id="cogs/P#rest"]'); await fold.getByText(`+${count - 5}`, { exact: true }).waitFor();
  await activate(actual, 'cogs/P#rest'); await fold.getByText(`+${count - 5 - 20}`, { exact: true }).waitFor(); await aligned();
  assert.equal(requests, 2); assert.deepEqual(errors, []);
  console.log(JSON.stringify({ status: 'PASS', initialCompression: 'PASS', columnsAndRowsAligned: 'PASS', inPlaceExpansion: 'PASS', periodIntermediates: 'PASS',
    sharedViewport: 'PASS', coverage: 'PASS', residentNavigation: 'PASS', largeFixtureNodes: count + 2, renderMs: Math.round(renderMs), requests, pageErrors: errors.length }));
} finally { await browser.close(); }
