/**
 * Browser regression for resident analysis, using isolated streamed fixtures and no warehouse access.
 * Start Vite first, then run with Node and Playwright available. MARGIN_FRONTEND_URL defaults to
 * http://127.0.0.1:5179; MARGIN_PLAYWRIGHT_PATH and MARGIN_BROWSER_PATH can select installed runtimes.
 */
import assert from 'node:assert/strict';
const { chromium } = await import(process.env.MARGIN_PLAYWRIGHT_PATH || 'playwright');
const browser = await chromium.launch({ headless: true, ...(process.env.MARGIN_BROWSER_PATH ? { executablePath: process.env.MARGIN_BROWSER_PATH } : {}) });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => { errors.push(error.message); console.error(error.message); });
  await page.route('**/api/**', route => route.fulfill({ status: 503, contentType: 'application/json', body: '{"message":"Isolated browser fixture"}' }));
  await page.route('**/imports**', route => route.fulfill({ status: 503, contentType: 'application/json', body: '{"message":"Isolated browser fixture"}' }));
  await page.addInitScript(() => {
    const originalFetch = window.fetch;
    window.analysisFixture = { requests: 0, aborts: 0, push: null, finish: null };
    window.fetch = async (url, options) => {
      if (url !== '/api/v1/analysis/run/stream') return originalFetch(url, options);
      const fixture = window.analysisFixture;
      fixture.requests++;
      const stream = new ReadableStream({ start(controller) {
        fixture.push = event => controller.enqueue(new TextEncoder().encode(JSON.stringify(event) + '\n'));
        fixture.finish = () => controller.close();
        options.signal.addEventListener('abort', () => { fixture.aborts++; controller.error(new DOMException('Aborted', 'AbortError')); });
      } });
      return new Response(stream, { headers: { 'Content-Type': 'application/x-ndjson' } });
    };
  });
  const root = process.env.MARGIN_FRONTEND_URL || 'http://127.0.0.1:5179';
  await page.goto(`${root}/settings`);
  assert.equal(await page.locator('input[type="date"]').count(), 0, 'Analysis mounts only after first visit');
  const navigate = name => page.getByRole('button', { name: name.startsWith('Dual-BOM Analysis') ? /^Dual-BOM Analysis/ : name, exact: true }).click();
  await navigate('Dual-BOM Analysis');
  await page.locator('summary').filter({ hasText: 'Data interpretation' }).click();
  await page.getByLabel('Warehouse currency').fill('NZD');
  await page.getByLabel('Warehouse cost scope').fill('manufacturing');
  await page.getByRole('button', { name: 'Run Analysis', exact: true }).click();
  await page.waitForFunction(() => window.analysisFixture.requests === 1);
  await navigate('Data Browser');
  assert.equal(await page.locator('input[type="date"]').count(), 4, 'Analysis remains mounted');
  assert.equal(await page.locator('input[type="date"]').first().isVisible(), false);
  assert.equal(await page.evaluate(() => window.analysisFixture.aborts), 0);
  const snapshot = {
    start: '2026-01-01', end: '2026-01-31', diagnostics: [],
    graph: { nodes: [{ inventoryId: 'A', quantity: '1', cost: '10' }], offset: [0, 0], successors: [] },
    records: { issues: [{ lineId: 'fixture', productionId: 'P1', productId: 'A', materialId: 'R', date: '2026-01-10', quantity: '1', amount: '10' }], sales: [] },
  };
  const outcome = { analysisId: 'resident-fixture', status: 'COMPLETED', actual: snapshot, comparable: snapshot,
    branches: [], selection: { trees: [], identities: [] }, embeddings: null, pairs: [], planned: 0, executed: 0, rejected: 0, failed: 0, execution: [], diagnostics: [] };
  await page.evaluate(({ snapshot, outcome }) => {
    window.analysisFixture.push({ type: 'graph', analysisId: outcome.analysisId, data: [snapshot, snapshot] });
    window.analysisFixture.push({ type: 'completed', analysisId: outcome.analysisId, data: outcome });
    window.analysisFixture.finish();
  }, { snapshot, outcome });
  await page.getByRole('button', { name: /^Dual-BOM Analysis/ }).getByRole('status').filter({ hasText: 'Completed' }).waitFor();
  assert.equal(await page.locator('.react-flow').count(), 0, 'Graph creation waits until page is visible');
  await navigate('Dual-BOM Analysis Completed');
  await page.locator('.react-flow__node').first().waitFor();
  assert.equal(await page.getByLabel('Warehouse currency').inputValue(), 'NZD');
  const pane = page.locator('.react-flow').first();
  await pane.getByRole('button', { name: 'Zoom In', exact: true }).click();
  await page.waitForTimeout(300);
  const viewport = await pane.locator('.react-flow__viewport').getAttribute('style');
  await page.locator('.react-flow__node').last().click();
  await page.getByRole('dialog').waitFor();
  await page.getByRole('tab', { name: 'Calculation details' }).click();
  // Programmatic history navigation also exercises the modal's hidden-page cleanup.
  await page.evaluate(() => { history.pushState({}, '', '/data-browser'); dispatchEvent(new PopStateEvent('popstate')); });
  await page.waitForFunction(() => document.body.style.overflow !== 'hidden');
  assert.equal(await page.getByRole('dialog').count(), 0);
  await page.keyboard.press('Escape');
  await page.goBack();
  await page.getByRole('dialog').waitFor();
  assert.equal(await page.getByRole('tab', { name: 'Calculation details' }).getAttribute('aria-selected'), 'true');
  await page.getByRole('button', { name: 'Close product comparison' }).click();
  assert.equal(await pane.locator('.react-flow__viewport').getAttribute('style'), viewport, 'Viewport survives navigation');
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: /Export/ }).click();
  const download = await downloadPromise;
  assert.equal(download.suggestedFilename(), 'analysis-resident-fixture.json');
  for (const destination of ['Settings & Parameters', 'Data Preparation']) {
    await navigate(destination);
    await page.goBack();
    assert.equal(await page.getByLabel('Warehouse currency').inputValue(), 'NZD');
  }
  assert.equal(await page.evaluate(() => window.analysisFixture.requests), 1, 'Navigation does not submit again');
  await page.getByRole('button', { name: 'Run Analysis', exact: true }).click();
  await page.waitForFunction(() => window.analysisFixture.requests === 2);
  await navigate('Data Browser');
  await page.evaluate(() => {
    window.analysisFixture.push({ type: 'error', message: 'Fixture analysis failure' });
    window.analysisFixture.finish();
  });
  await page.getByRole('button', { name: /^Dual-BOM Analysis/ }).getByRole('status').filter({ hasText: 'Failed' }).waitFor();
  await navigate('Dual-BOM Analysis Failed');
  assert.equal(await page.getByRole('alert').textContent(), 'Fixture analysis failure');
  await page.goBack();
  await page.goForward();
  assert.equal(await page.getByRole('alert').textContent(), 'Fixture analysis failure');
  assert.equal(await page.evaluate(() => window.analysisFixture.aborts), 1, 'Only starting a new run aborts the preceding controller');
  assert.deepEqual(errors, []);
  console.log('PASS: lazy mounting, live stream retention, hidden completion/failure, inputs, modal cleanup/restoration, viewport, export, back/forward and no duplicate submissions');
} finally {
  await browser.close();
}
