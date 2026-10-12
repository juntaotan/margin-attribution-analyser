import test from 'node:test';
import assert from 'node:assert/strict';
import { performance } from 'node:perf_hooks';
import { BUSINESS_LIMIT, GraphDisplayIndex, INITIAL_QUERY, PAGE_SIZE, SIGNAL_LIMIT, decimalMagnitude, formatMicro, microUnits,
  type DisplayInput, type DisplayResult, type DisplaySnapshot } from './graphDisplay.ts';

type Sale = DisplaySnapshot['records']['sales'][number];
type Issue = DisplaySnapshot['records']['issues'][number];
/** Build a snapshot from material -> receiving-item edges; node order is the given order. */
function snapshot(ids: string[], uses: [string, string][], sales: [string, string | null][] = [], issues: [string, string, string | null][] = [], cost = (id: string): string | null => id === '__COGS__' ? '1000' : '10'): DisplaySnapshot {
  const at = new Map(ids.map((id, i) => [id, i])); const successors: number[] = []; const offset = [0];
  const outgoing = new Map<string, number[]>(); uses.forEach(([from, to]) => outgoing.set(from, [...outgoing.get(from) || [], at.get(to)!]));
  ids.forEach(id => { successors.push(...outgoing.get(id) || []); offset.push(successors.length); });
  return { graph: { nodes: ids.map(inventoryId => ({ inventoryId, quantity: '1', cost: cost(inventoryId) })), offset, successors },
    records: { sales: sales.map(([productId, value], i) => ({ sourceId: `s${i}`, date: '2026-01-01', productId, quantity: '1', cost: value }) as Sale),
      issues: issues.map(([productId, materialId, amount], i) => ({ lineId: `i${i}`, date: '2026-01-01', documentId: 'd', productionId: 'p', productId, materialId, quantity: '1', amount, outputQuantity: null, outputCost: null }) as Issue) } };
}
const branch = (actual: DisplaySnapshot, ids: string[], difference: string) =>
  ({ positions: ids.map(id => actual.graph.nodes.findIndex(node => node.inventoryId === id)), edges: [], endReason: 'THRESHOLD_EXCEEDED', endingCostDifference: difference });
const labels = (result: DisplayResult) => result.items.map(item => item.label);
const item = (result: DisplayResult, key: string) => { const found = result.items.find(entry => entry.key === key); assert.ok(found, `Missing ${key}`); return found; };
const children = (result: DisplayResult, key: string) => result.edges.filter(edge => edge.source === key).map(edge => item(result, edge.target));
const open = (_from: DisplayResult, ...keys: string[]) => ({ ...INITIAL_QUERY, expanded: Object.fromEntries(keys.map(key => [key, 1])) });
/** Structural invariants: unique keys, real edge endpoints, fixed columns and non-overlapping rows. */
function layout(result: DisplayResult) {
  const keys = new Set(result.items.map(entry => entry.key)); assert.equal(keys.size, result.items.length);
  const cells = new Set<string>();
  for (const entry of result.items) {
    assert.ok(entry.column < result.columns);
    const cell = `${entry.column}:${entry.row}`; assert.ok(!cells.has(cell), `Overlap at ${cell}`); cells.add(cell);
  }
  for (const edge of result.edges) {
    const source = item(result, edge.source); const target = item(result, edge.target);
    assert.ok(target.terminal ? target.column >= source.column + 1 : target.column === source.column + 1, `${edge.id} skips a column`);
  }
  assert.ok(result.items.filter(entry => entry.kind === 'node' && entry.inventoryId !== '__COGS__').length <= BUSINESS_LIMIT);
}

// COGS -> A -> B -> C -> D -> E -> F and C -> G -> H -> I, plus normal product J -> K.
function chain(): DisplayInput {
  const ids = ['__COGS__', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K'];
  const uses: [string, string][] = [['A', '__COGS__'], ['B', 'A'], ['C', 'B'], ['D', 'C'], ['E', 'D'], ['F', 'E'], ['G', 'C'], ['H', 'G'], ['I', 'H'], ['J', '__COGS__'], ['K', 'J']];
  const actual = snapshot(ids, uses, [['A', '900'], ['J', '50']], [['C', 'D', '40'], ['C', 'G', '5']]);
  const comparable = snapshot(ids, uses, [['A', '300'], ['J', '40']], [['C', 'D', '10'], ['C', 'G', '5']]);
  return { actual, comparable, branches: [branch(actual, ['F', 'E', 'D', 'C'], '-500')] };
}

test('exact decimal parsing, exponent notation, rejection of excess precision and formatting', () => {
  assert.equal(microUnits('-9007199254740993.123456'), -9007199254740993123456n);
  assert.equal(microUnits('1E+3'), 1_000_000_000n); assert.equal(microUnits('1.5e-5'), 15n); assert.equal(microUnits('12.50'), 12_500_000n);
  for (const invalid of ['1.0000001', '1e-7', '1.5e-6', 'NaN', '', '1,0']) assert.throws(() => microUnits(invalid));
  assert.equal(decimalMagnitude('-2.5'), 2_500_000n);
  assert.equal(formatMicro(-1_500_000n), '-1.5'); assert.equal(formatMicro(3n), '0.000003'); assert.equal(formatMicro(0n), '0');
});

test('coverage selects the shortest |ΔCOGS| prefix, with stable ties, unavailable costs and two remainder boxes', () => {
  const ids = ['__COGS__', 'P1', 'P2', 'P3', 'P4', 'P5', 'M'];
  const uses: [string, string][] = [['P1', '__COGS__'], ['P2', '__COGS__'], ['P3', '__COGS__'], ['P4', '__COGS__'], ['P5', '__COGS__'], ['M', 'P3']];
  const actual = snapshot(ids, uses, [['P1', '100'], ['P2', '700'], ['P3', '400'], ['P4', '0'], ['P5', null]]);
  const comparable = snapshot(ids, uses, [['P1', '700'], ['P2', '400'], ['P3', '300'], ['P4', '0'], ['P5', '1']]);
  // |Δ|: P1 600, P2 300, P3 100, P4 0; P5 unavailable. Total 1000.
  const index = new GraphDisplayIndex({ actual, comparable, branches: [branch(actual, ['M', 'P3'], '20')] });
  const products = (coverage: number) => children(index.query({ coverage, expanded: {} }), 'cogs').filter(entry => entry.kind === 'node').map(entry => entry.label);
  assert.deepEqual(products(50), ['P1']); assert.deepEqual(products(60), ['P1']); assert.deepEqual(products(61), ['P1', 'P2']);
  assert.deepEqual(products(80), ['P1', 'P2']); assert.deepEqual(products(100), ['P1', 'P2', 'P3']);
  const result = index.query(INITIAL_QUERY); layout(result);
  assert.equal(item(result, 'cogs/P1').delta, '-600'); assert.equal(item(result, 'cogs').delta, null);
  assert.equal(result.stats.coveredPerMille, 600); assert.equal(result.stats.unavailableProducts, 1); assert.equal(result.stats.totalAbsolute, '1000');
  const groups = children(result, 'cogs').filter(entry => entry.kind === 'group');
  assert.deepEqual(groups.map(entry => entry.label), ['Abnormal products (1)', 'Normal products (3)']);
  assert.ok(groups[0].abnormal && !groups[1].abnormal);
  assert.deepEqual(result.items.filter(entry => entry.kind === 'group').length, 2);
  // Opening a box reveals its members in the product column, ordered by |Δ| with unavailable last.
  const normal = index.query(open(result, 'cogs#normal'));
  assert.deepEqual(children(normal, 'cogs').filter(entry => entry.kind === 'node').map(entry => entry.label), ['P1', 'P2', 'P4', 'P5']);
  assert.equal(item(normal, 'cogs/P5').delta, null); assert.equal(item(normal, 'cogs#normal').expandKey, undefined);
  const ties = snapshot(['__COGS__', 'B', 'A'], [['B', '__COGS__'], ['A', '__COGS__']], [['A', '5'], ['B', '-5']]);
  assert.deepEqual(children(new GraphDisplayIndex({ actual: ties, comparable: snapshot(['__COGS__'], []), branches: [] }).query({ coverage: 100, expanded: {} }), 'cogs')
    .filter(entry => entry.kind === 'node').map(entry => entry.label), ['A', 'B']);
});

test('initial view: COGS -> product -> … -> complete abnormal branch, with other children folded', () => {
  const index = new GraphDisplayIndex(chain()); const result = index.query(INITIAL_QUERY); layout(result);
  assert.deepEqual(children(result, 'cogs').map(entry => entry.label), ['A', 'Abnormal products (0)', 'Normal products (1)']);
  const [bridge] = children(result, 'cogs/A');
  assert.equal(bridge.kind, 'bridge'); assert.match(bridge.note!, /1 normal node hidden/);
  assert.ok(!labels(result).includes('B'));
  const c = children(result, 'cogs/A#bridge')[0]; assert.equal(c.label, 'C'); assert.ok(c.abnormal); assert.deepEqual(c.boundary, { difference: '-500', count: 1 });
  assert.equal(c.parentDelta, undefined, 'a folded span never claims a direct relationship');
  const below = children(result, c.key);
  assert.deepEqual(below.map(entry => entry.label), ['D', '+1']);
  assert.equal(item(result, `${c.key}/D`).parentDelta, '30');
  assert.deepEqual(children(result, `${c.key}/D`).map(entry => entry.label), ['E']);
  const f = children(result, `${c.key}/D/E`)[0]; assert.equal(f.label, 'F'); assert.ok(f.terminal); assert.equal(f.column, result.columns - 1);
  assert.match(below[1].note!, /1 item · Δ 0$/);
  for (const hidden of ['B', 'G', 'H', 'I', 'J', 'K']) assert.ok(!labels(result).includes(hidden), hidden);
});

test('folds expand in place: bridge spans fully, remainders by page, other folds unchanged', () => {
  const index = new GraphDisplayIndex(chain()); const base = index.query(INITIAL_QUERY);
  const bridged = index.query(open(base, 'cogs/A#bridge')); layout(bridged);
  assert.deepEqual(children(bridged, 'cogs/A').map(entry => entry.label), ['B']);
  assert.deepEqual(children(bridged, 'cogs/A/B').map(entry => entry.label), ['C']);
  assert.deepEqual(children(bridged, 'cogs/A/B/C').map(entry => entry.label), ['D', '+1'], 'C remainder stays folded');
  const rest = index.query(open(base, 'cogs/A#bridge/C#rest'));
  assert.deepEqual(children(rest, 'cogs/A#bridge/C').map(entry => entry.label), ['D', 'G']);
  assert.deepEqual(children(rest, 'cogs/A#bridge/C/G').map(entry => entry.label), ['+1']);
  assert.ok(labels(rest).includes('…'), 'the A span remains folded');
});

test('abnormal nodes inside folded children: … -> abnormal -> … -> abnormal -> …', () => {
  const input = chain(); input.branches.push(branch(input.actual, ['H'], '30'));
  const result = new GraphDisplayIndex(input).query(INITIAL_QUERY); layout(result);
  const c = 'cogs/A#bridge/C';
  assert.deepEqual(children(result, c).map(entry => `${entry.kind}:${entry.label}`), ['node:D', 'bridge:…']);
  assert.deepEqual(children(result, `${c}#bridge`).map(entry => entry.label), ['H']);
  assert.deepEqual(children(result, `${c}#bridge/H`).map(entry => entry.label), ['+1']);
});

test('both periods share one layout; each pane keeps its own recorded nodes and relationships', () => {
  const input = chain();
  const ids = ['__COGS__', 'A', 'X', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K'];
  // Shuffled positions and A-X-C instead of A-B-C in the comparable period.
  input.comparable = snapshot([...ids].reverse(), [['A', '__COGS__'], ['X', 'A'], ['C', 'X'], ['D', 'C'], ['E', 'D'], ['F', 'E'], ['G', 'C'], ['H', 'G'], ['I', 'H'], ['J', '__COGS__'], ['K', 'J']],
    [['A', '300'], ['J', '40']], [], id => id === 'D' ? null : '10');
  const index = new GraphDisplayIndex(input); const result = index.query(open(index.query(INITIAL_QUERY), 'cogs/A#bridge')); layout(result);
  const middle = children(result, 'cogs/A');
  assert.deepEqual(middle.map(entry => entry.label).sort(), ['B', 'X']);
  const b = item(result, 'cogs/A/B'); const x = item(result, 'cogs/A/X');
  assert.ok(b.actual && !b.comparable && x.comparable && !x.actual && b.row !== x.row && b.column === x.column);
  assert.equal(x.comparable!.position, 9);
  const d = item(result, 'cogs/A/B/C/D');
  assert.equal(d.comparable!.cost, null); assert.equal(d.delta, null);
  assert.ok(d.comparable!.linked && d.actual!.linked);
  const edge = result.edges.find(entry => entry.target === 'cogs/A/B')!; assert.ok(edge.actual && !edge.comparable);
});

test('wide and deep graphs stay within budget, paginate remainders and keep anomaly ordering', () => {
  const count = 1000; const ids = ['__COGS__', 'P', ...Array.from({ length: count }, (_, i) => `M${String(i).padStart(4, '0')}`)];
  const uses: [string, string][] = [['P', '__COGS__'], ...ids.slice(2).map(id => [id, 'P'] as [string, string])];
  const actual = snapshot(ids, uses, [['P', '10']]); const comparable = snapshot(ids, uses, [['P', '1']]);
  const branches = ids.slice(2, 12).map((id, i) => branch(actual, [id], String(100 + i)));
  const index = new GraphDisplayIndex({ actual, comparable, branches }); const result = index.query(INITIAL_QUERY); layout(result);
  const shown = children(result, 'cogs/P');
  assert.deepEqual(shown.slice(0, SIGNAL_LIMIT).map(entry => entry.label), ['M0009', 'M0008', 'M0007', 'M0006', 'M0005']);
  assert.equal(shown.at(-1)!.label, `+${count - SIGNAL_LIMIT}`); assert.ok(shown.at(-1)!.abnormal);
  const page = index.query({ ...INITIAL_QUERY, expanded: { 'cogs/P#rest': 2 } }); layout(page);
  assert.equal(children(page, 'cogs/P').at(-1)!.label, `+${count - SIGNAL_LIMIT - 2 * PAGE_SIZE}`);
  const all = index.query({ ...INITIAL_QUERY, expanded: { 'cogs/P#rest': 100 } }); layout(all);
  assert.ok(all.stats.budgetReached); assert.equal(all.stats.businessNodes, BUSINESS_LIMIT);
  // A 2,000-node normal chain under an anomaly folds into one bridge with the deepest anomaly visible.
  const deep = ['__COGS__', 'Q', ...Array.from({ length: 2000 }, (_, i) => `D${i}`)];
  const chainUses: [string, string][] = deep.slice(1).map((id, i) => [id, deep[i]] as [string, string]);
  const deepActual = snapshot(deep, chainUses, [['Q', '5']]);
  const deepResult = new GraphDisplayIndex({ actual: deepActual, comparable: snapshot(['__COGS__'], []), branches: [branch(deepActual, ['D1999'], '7')] }).query(INITIAL_QUERY);
  layout(deepResult); assert.deepEqual(labels(deepResult).slice(0, 4), ['Cost of Goods Sold', 'Q', '…', 'D1999']);
  assert.match(item(deepResult, 'cogs/Q#bridge').note!, /1999 normal nodes hidden/);
});

test('missing COGS, unlinked anomalies, shared materials and cycles are explicit', () => {
  const actual = snapshot(['P', 'M', 'Z'], [['M', 'P']]);
  const missing = new GraphDisplayIndex({ actual, comparable: snapshot([], []), branches: [branch(actual, ['Z'], '3')] }).query(INITIAL_QUERY);
  assert.equal(missing.items[0].kind, 'missing'); assert.equal(missing.stats.unlinkedBranches, 1);
  const shared = snapshot(['__COGS__', 'P1', 'P2', 'S'], [['P1', '__COGS__'], ['P2', '__COGS__'], ['S', 'P1'], ['S', 'P2']], [['P1', '5'], ['P2', '4']]);
  const sharedResult = new GraphDisplayIndex({ actual: shared, comparable: snapshot(['__COGS__'], []), branches: [branch(shared, ['S'], '9')] }).query({ coverage: 100, expanded: {} });
  layout(sharedResult);
  assert.equal(item(sharedResult, 'cogs/P1/S').shared, 2); assert.equal(item(sharedResult, 'cogs/P2/S').shared, 2);
  const left = snapshot(['__COGS__', 'P', 'A', 'B'], [['P', '__COGS__'], ['A', 'P'], ['B', 'A']], [['P', '2']]);
  const right = snapshot(['__COGS__', 'P', 'A', 'B'], [['P', '__COGS__'], ['A', 'P'], ['A', 'B']], [['P', '1']]);
  const cyclic = new GraphDisplayIndex({ actual: left, comparable: right, branches: [] }).query(INITIAL_QUERY);
  assert.ok(cyclic.stats.diagnostics.some(text => text.includes('cycle')));
  layout(new GraphDisplayIndex({ actual: left, comparable: right, branches: [] }).query({ ...INITIAL_QUERY, expanded: { 'cogs/P#rest': 1, 'cogs/P/A#rest': 1, 'cogs/P/A/B#rest': 1 } }));
});

test('large CSR input yields a bounded projection', () => {
  const products = 2000; const perProduct = 50; const ids = ['__COGS__'];
  const uses: [string, string][] = []; const sales: [string, string][] = []; const previous: [string, string][] = [];
  for (let p = 0; p < products; p++) {
    ids.push(`P${p}`); uses.push([`P${p}`, '__COGS__']); sales.push([`P${p}`, String(1000 + p)]); previous.push([`P${p}`, '1000']);
    for (let m = 0; m < perProduct; m++) { ids.push(`P${p}M${m}`); uses.push([`P${p}M${m}`, `P${p}`]); }
  }
  let started = performance.now();
  const actual = snapshot(ids, uses, sales); const comparable = snapshot(ids, uses, previous);
  const branches = Array.from({ length: 5000 }, (_, i) => branch(actual, [`P${i % products}M${i % perProduct}`], String(i)));
  const built = performance.now() - started; started = performance.now();
  const index = new GraphDisplayIndex({ actual, comparable, branches }); const indexed = performance.now() - started; started = performance.now();
  const result = index.query(INITIAL_QUERY); const projected = performance.now() - started; layout(result);
  assert.ok(result.items.length < 200, `${result.items.length} display items`);
  console.log(`large fixture: ${ids.length} nodes, fixture ${built.toFixed(1)} ms, index ${indexed.toFixed(1)} ms, projection ${projected.toFixed(1)} ms, ${result.items.length} items, ${result.stats.selectedProducts} products`);
});
