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
  const table = page.getByRole('treegrid', { name: 'Dual-BOM comparison table' });
  const ready = () => page.getByText('Preparing bounded table…', { exact: true }).waitFor({ state: 'hidden' });
  const rows = () => table.locator('tbody tr').evaluateAll(list => list.map(row => row.dataset.key));
  const row = key => table.locator(`tbody tr[data-key="${key}"]`);
  const cells = async key => (await row(key).locator('td').allInnerTexts()).map(text => text.trim().replace(/\s+/g, ' '));
  const press = async (key, button) => { await row(key).focus(); await page.keyboard.press(button); await ready(); };
  const initial = ['cogs', 'cogs/A', 'cogs#abnormal', 'cogs#normal'];
  const openedA = ['cogs', 'cogs/A', 'cogs/A#bridge', 'cogs/A#bridge/C', 'cogs/A#bridge/C/D', 'cogs/A#bridge/C/D/E', 'cogs/A#bridge/C/D/E/F', 'cogs/A#bridge/C#rest', 'cogs#abnormal', 'cogs#normal'];

  await page.getByRole('button', { name: /Run Analysis/i }).click(); await ready(); await row('cogs/A').waitFor();
  // Default view: every product row is closed; column groups read comparable, actual, difference.
  assert.deepEqual(await rows(), initial);
  assert.equal(await row('cogs/A').getAttribute('aria-expanded'), 'false');
  assert.deepEqual((await table.locator('thead tr').first().locator('th').allInnerTexts()).map(text => text.trim()),
    ['Item', 'Comparable 2026-01-01 to 2026-01-31', 'Actual 2026-01-01 to 2026-01-31', 'Difference (actual − comparable)']);
  assert.deepEqual(await cells('cogs/A'), ['A', '1', '300', '300', '1', '900', '900', '600', '600']);
  await page.getByText('1 of 2 products · cover 600 of 610 NZD (98.3%) · ΔCOGS 610', { exact: true }).waitFor();
  if (process.env.DISPLAY_SCREENSHOT) await page.screenshot({ path: process.env.DISPLAY_SCREENSHOT.replace('.png', '-initial.png') });

  // Opening a product shows its compressed children in tree order, indented by level.
  await press('cogs/A', 'ArrowRight');
  assert.deepEqual(await rows(), openedA);
  assert.equal(await row('cogs/A#bridge/C/D/E/F').getAttribute('aria-level'), '7');
  assert.match((await cells('cogs/A#bridge/C/D'))[7], /^0 in parent 30$/);
  if (process.env.DISPLAY_SCREENSHOT) await page.screenshot({ path: process.env.DISPLAY_SCREENSHOT.replace('.png', '-opened.png') });
  // The bridge expands in place; each period's intermediate node has its own row, unrecorded in the other period.
  await press('cogs/A#bridge', 'Enter');
  assert.deepEqual((await rows()).slice(0, 4), ['cogs', 'cogs/A', 'cogs/A/B', 'cogs/A/B/C']);
  assert.ok((await rows()).includes('cogs/A/X'));
  assert.deepEqual((await cells('cogs/A/B')).slice(1, 3), ['Not recorded in this period', '1']);
  assert.deepEqual((await cells('cogs/A/X')).slice(1, 5), ['1', '10', '10', 'Not recorded in this period']);
  await page.getByRole('button', { name: 'Undo expansion', exact: true }).click(); await ready(); assert.deepEqual(await rows(), openedA);
  await press('cogs/A#bridge/C#rest', 'Enter'); assert.ok((await rows()).includes('cogs/A#bridge/C/G'));
  await press('cogs/A', 'ArrowLeft'); assert.deepEqual(await rows(), initial);

  // Clicking a product opens its aligned tree graphs, rooted at the product.
  await row('cogs/A').click();
  const dialog = page.getByRole('dialog', { name: 'Product tree · A' }); await dialog.waitFor();
  const actual = dialog.locator('[aria-label^="Actual "][aria-label$=" graph"]');
  const comparable = dialog.locator('[aria-label^="Comparable "][aria-label$=" graph"]');
  const graphReady = () => dialog.getByText('Preparing product tree…', { exact: true }).waitFor({ state: 'hidden' });
  const keys = pane => pane.locator('.react-flow__node:not([data-id^="column:"])').evaluateAll(nodes => nodes.map(node => node.dataset.id).sort());
  const transforms = pane => pane.locator('.react-flow__node').evaluateAll(nodes => Object.fromEntries(nodes.map(node => [node.dataset.id, node.style.transform])));
  await graphReady(); await actual.locator('[data-id="cogs/A"]').waitFor();
  const tree = openedA.filter(key => key.startsWith('cogs/A')).sort();
  for (const pane of [actual, comparable]) assert.deepEqual(await keys(pane), tree);
  assert.deepEqual(await transforms(actual), await transforms(comparable));
  for (const label of ['Products', 'Material terminals']) await actual.locator('.react-flow__node[data-id^="column:"]').getByText(label, { exact: true }).waitFor();
  const bridge = actual.locator('[data-id="cogs/A#bridge"]'); await bridge.focus(); await page.keyboard.press('Enter'); await graphReady();
  assert.ok((await keys(comparable)).includes('cogs/A/X') && (await keys(actual)).includes('cogs/A/B'));
  assert.deepEqual(await transforms(actual), await transforms(comparable));
  await dialog.getByRole('button', { name: 'Production evidence: A', exact: true }).waitFor();
  // Let the 180 ms modal entrance animation finish before capturing.
  if (process.env.DISPLAY_SCREENSHOT) { await page.waitForTimeout(400); await page.screenshot({ path: process.env.DISPLAY_SCREENSHOT.replace('.png', '-product.png') }); }
  await page.keyboard.press('Escape'); await dialog.waitFor({ state: 'detached' });
  assert.deepEqual(await rows(), initial, 'the table keeps its state while the tree is shown');

  assert.deepEqual(await page.getByLabel('Initial COGS difference coverage').locator('option').allTextContents(), ['20%', '40%', '50%', '60%', '80%', '100%']);
  assert.equal(await page.getByLabel('Initial COGS difference coverage').inputValue(), '50');
  await page.getByLabel('Initial COGS difference coverage').selectOption('100'); await ready();
  assert.ok((await rows()).includes('cogs/J'));
  await page.getByRole('button', { name: 'Settings & Parameters' }).click(); await page.getByRole('button', { name: /Dual-BOM Analysis/ }).click();
  assert.ok((await rows()).includes('cogs/J')); assert.equal(requests, 1);

  // 25,000 materials under one product: bounded rendering and paged remainder.
  const count = 25_000; const large = ['__COGS__', 'P', ...Array.from({ length: count }, (_, i) => `M${i}`)];
  const largeUses = [['P', '__COGS__'], ...large.slice(2).map(id => [id, 'P'])];
  outcome.actual = snapshot(large, largeUses, [['P', '20']]); outcome.comparable = snapshot(large, largeUses, [['P', '10']]);
  outcome.branches = Array.from({ length: count }, (_, i) => ({ positions: [i + 2, 1], edges: [], endReason: 'THRESHOLD_EXCEEDED', endingCostDifference: `${i + 1}.000001` }));
  const start = performance.now(); await page.getByRole('button', { name: /Run Analysis/i }).click();
  await page.locator('section[data-analysis-id="tree-fixture-2"]').waitFor(); await ready(); await row('cogs/P').waitFor();
  const renderMs = performance.now() - start;
  await press('cogs/P', 'ArrowRight');
  assert.ok((await rows()).length <= 10, 'COGS, product, five anomalies, one remainder and two boxes');
  await row('cogs/P#rest').getByText(`+${count - 5}`, { exact: true }).waitFor();
  await press('cogs/P#rest', 'Enter'); await row('cogs/P#rest').getByText(`+${count - 5 - 20}`, { exact: true }).waitFor();
  assert.equal(requests, 2); assert.deepEqual(errors, []);
  console.log(JSON.stringify({ status: 'PASS', productsClosedByDefault: 'PASS', columnGroups: 'PASS', totalAndUnitDifference: 'PASS', inPlaceExpansion: 'PASS',
    periodIntermediates: 'PASS', productTreeGraph: 'PASS', coverage: 'PASS', residentNavigation: 'PASS', largeFixtureNodes: count + 2, renderMs: Math.round(renderMs), requests, pageErrors: errors.length }));
} finally { await browser.close(); }
