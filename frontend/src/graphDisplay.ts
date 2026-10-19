import type { AnalysisOutcome, PeriodSnapshot } from './analysisRun';

/** Inventory ID of the Cost of Goods Sold root. */
export const COGS_ID = '__COGS__';
/** Maximum products selected by the coverage rule before the remainder boxes take over. */
export const PRODUCT_LIMIT = 15;
/** Maximum abnormal routes shown directly beneath one displayed node. */
export const SIGNAL_LIMIT = 5;
/** Members revealed by each click on a folded remainder. */
export const PAGE_SIZE = 20;
/** Business-node budget of one projection; overflow remains folded and expandable. */
export const BUSINESS_LIMIT = 150;

type Branch = AnalysisOutcome['branches'][number];
/** The parts of a period snapshot the display index needs. */
export type DisplaySnapshot = Pick<PeriodSnapshot, 'graph' | 'records'>;
/** Both period snapshots and the abnormal branches found in the actual period. */
export interface DisplayInput {
  actual: DisplaySnapshot;
  comparable: DisplaySnapshot;
  branches: Branch[];
}
/** Which period a pane shows. */
export type Period = 'actual' | 'comparable';
/** Recorded values of one display node in one period; `linked` is false when the drawn parent relationship is not recorded there. */
export interface PeriodValue {
  position: number;
  quantity: string;
  cost: string | null;
  linked: boolean;
}
/**
 * A box in the display tree: a recorded node or a fold (bridge, remainder, product group or missing root), with its
 * column and row in the shared layout.
 */
export interface DisplayItem {
  key: string;
  kind: 'node' | 'bridge' | 'rest' | 'group' | 'missing';
  label: string;
  note?: string;
  inventoryId?: string;
  product?: boolean;
  terminal?: boolean;
  abnormal: boolean;
  boundary?: { difference: string; count: number };
  /** Actual minus comparable: sales cost for COGS and products, recorded node cost for other items. */
  delta?: string | null;
  /** Actual minus comparable issue amount consumed by the drawn parent. */
  parentDelta?: string | null;
  shared?: number;
  actual?: PeriodValue;
  comparable?: PeriodValue;
  column: number;
  row: number;
  expandKey?: string;
}
/** A display edge, and whether it is recorded in each period. */
export interface DisplayEdge {
  id: string;
  source: string;
  target: string;
  folded: boolean;
  actual: boolean;
  comparable: boolean;
}
/** `expanded` maps a fold key to its opened page count; a bridge opens with one page. */
export interface DisplayQuery {
  coverage: number;
  expanded: Record<string, number>;
}
/** Coverage, size and warnings of one projection; decimal amounts are exact text. */
export interface DisplayStats {
  coverage: number;
  selectedProducts: number;
  products: number;
  totalAbsolute: string;
  coveredAbsolute: string;
  coveredPerMille: number | null;
  cogsDelta: string | null;
  unavailableProducts: number;
  unlinkedBranches: number;
  totalNodes: number;
  businessNodes: number;
  budgetReached: boolean;
  diagnostics: string[];
}
/** A laid-out projection of the display tree. */
export interface DisplayResult {
  items: DisplayItem[];
  edges: DisplayEdge[];
  columns: number;
  rows: number;
  terminalColumn: boolean;
  stats: DisplayStats;
}
/** Initial projection: 50% coverage of the COGS difference, nothing expanded. */
export const INITIAL_QUERY: DisplayQuery = { coverage: 50, expanded: {} };

/**
 * Convert a recorded decimal, including BigDecimal exponent notation, to signed micro-units
 * without binary floating-point rounding. Values needing more than six decimals are rejected.
 */
export function microUnits(value: string): bigint {
  const match = /^([+-]?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(value.trim());
  if (!match) throw new Error(`Unrecognised decimal amount: ${value}`);
  const digits = match[2] + (match[3] || '');
  const scale = (match[3] || '').length - Number(match[4] || 0) - 6;
  if (scale <= 0) return (match[1] === '-' ? -1n : 1n) * BigInt(digits) * 10n ** BigInt(-scale);
  const divisor = 10n ** BigInt(scale);
  if (BigInt(digits) % divisor !== 0n) throw new Error(`Amount exceeds six decimal places: ${value}`);
  return (match[1] === '-' ? -1n : 1n) * (BigInt(digits) / divisor);
}
/** Absolute exact micro-units of a recorded decimal amount. */
export function decimalMagnitude(value: string): bigint {
  const micro = microUnits(value);
  return micro < 0n ? -micro : micro;
}
/** Format signed micro-units as a plain decimal string without trailing fractional zeros. */
export function formatMicro(value: bigint): string {
  const sign = value < 0n ? '-' : '';
  const magnitude = value < 0n ? -value : value;
  const fraction = (magnitude % 1_000_000n).toString().padStart(6, '0').replace(/0+$/, '');
  return `${sign}${magnitude / 1_000_000n}${fraction ? `.${fraction}` : ''}`;
}
const abs = (value: bigint) => (value < 0n ? -value : value);
/** Sum recorded amounts by key; one unavailable amount makes that key unavailable rather than zero. */
function sumBy<T>(rows: T[], key: (row: T) => string | null, amount: (row: T) => string | null, diagnostics: string[]) {
  const sums = new Map<string, bigint | null>();
  for (const row of rows) {
    const id = key(row);
    if (id == null) continue;
    const value = amount(row);
    let micro: bigint | null = null;
    if (value != null)
      try {
        micro = microUnits(value);
      } catch (error) {
        diagnostics.push((error as Error).message);
      }
    const previous = sums.get(id);
    sums.set(id, previous === null || micro === null ? null : (previous ?? 0n) + micro);
  }
  return sums;
}
const difference = (actual: Map<string, bigint | null>, comparable: Map<string, bigint | null>, id: string) => {
  const left = actual.get(id);
  const right = comparable.get(id);
  return left === null || right === null ? null : (left ?? 0n) - (right ?? 0n);
};
const byMagnitude = (a: bigint | null | undefined, b: bigint | null | undefined) =>
  a == null ? (b == null ? 0 : 1) : b == null ? -1 : abs(a) === abs(b) ? 0 : abs(a) > abs(b) ? -1 : 1;

interface PeriodIndex {
  snapshot: DisplaySnapshot;
  position: Int32Array;
  edges: Set<number>;
}
interface Tree {
  item: DisplayItem;
  children: Tree[];
  folded: boolean;
}

const formatOptional = (value: bigint | null | undefined) =>
  value === undefined ? undefined : value === null ? null : formatMicro(value);

/** Display key of a child, nested under its parent's key. */
const childKey = (parentKey: string, id: string) => `${parentKey}/${encodeURIComponent(id)}`;

const fold = (
  key: string,
  kind: DisplayItem['kind'],
  label: string,
  note: string,
  abnormal: boolean,
  expandKey?: string,
): Tree => ({
  item: { key, kind, label, note, abnormal, expandKey, column: 0, row: 0 },
  children: [],
  folded: true,
});

/** Shared per-query state: the requested expansion and the remaining business-node budget. */
interface Projection {
  query: DisplayQuery;
  budget: number;
  businessNodes: number;
  budgetReached: boolean;
}

/** Renders a child into `into`; returns false, without rendering, when the business-node budget is spent. */
type ChildRenderer = (
  member: number,
  memberKey: string,
  memberParent: number | undefined,
  memberOpen: boolean,
  into: Tree[],
) => boolean;

interface ProductRank {
  product: number;
  delta: bigint | null;
}

/** Selects the shortest prefix of ranked products whose |ΔCOGS| reaches the requested coverage share. */
function selectCoverage(ranked: ProductRank[], coverage: number) {
  const total = ranked.reduce((sum, row) => sum + (row.delta == null ? 0n : abs(row.delta)), 0n);
  const selected: number[] = [];
  let covered = 0n;
  for (const row of ranked) {
    if (
      total === 0n ||
      covered * 100n >= total * BigInt(coverage) ||
      row.delta == null ||
      row.delta === 0n ||
      selected.length >= PRODUCT_LIMIT
    )
      break;
    selected.push(row.product);
    covered += abs(row.delta);
  }
  return { selected, total, covered };
}

/**
 * Lays out a display tree. Columns follow display depth, and real material terminals align in the last column.
 * Rows follow the ranked child order, and each parent sits midway between its first and last child.
 */
function layoutTree(root: Tree) {
  const items: DisplayItem[] = [];
  const edges: DisplayEdge[] = [];
  let rows = 0;
  let depth = 0;
  const place = (tree: Tree, column: number): number => {
    tree.item.column = column;
    depth = Math.max(depth, column);
    items.push(tree.item);
    if (!tree.children.length) return (tree.item.row = rows++);
    const placed = tree.children.map((next) => {
      const node = next.item.kind === 'node' && tree.item.kind === 'node';
      edges.push({
        id: `${tree.item.key}->${next.item.key}`,
        source: tree.item.key,
        target: next.item.key,
        folded: tree.folded || next.folded,
        actual: !node || !!next.item.actual?.linked,
        comparable: !node || !!next.item.comparable?.linked,
      });
      return place(next, column + 1);
    });
    return (tree.item.row = (placed[0] + placed[placed.length - 1]) / 2);
  };
  place(root, 0);
  const terminalColumn = items.some((item) => item.terminal);
  if (terminalColumn)
    items.forEach((item) => {
      if (item.terminal) item.column = depth;
    });
  return { items, edges, rows, columns: depth + 1, terminalColumn };
}

/**
 * Worker-owned index of both period graphs merged by inventory ID. Projections are display trees
 * reading Cost of Goods Sold -> products -> materials from left to right, so both panes share
 * one column/row layout. Recorded values stay per period; nothing is inferred across periods.
 */
export class GraphDisplayIndex {
  private ids: string[] = [];
  private index = new Map<string, number>();
  private children: number[][] = [];
  private parents: number[][] = [];
  private periods: Record<Period, PeriodIndex>;
  private abnormal: Uint8Array;
  private boundaries = new Map<number, { difference: string; count: number }>();
  /** Largest supplied abnormal amount on the node or below it; -1 means no evidence. */
  private score: bigint[];
  private productDelta = new Map<number, bigint | null>();
  private issueDelta: (parent: number, child: number) => bigint | null;
  private nodeDelta: (bigint | null)[];
  private cogs?: number;
  private cogsDelta: bigint | null;
  private unlinked = 0;
  private diagnostics: string[] = [];

  constructor(input: DisplayInput) {
    for (const period of [input.actual, input.comparable])
      period.graph.nodes.forEach((node) => this.addNode(node.inventoryId));
    const size = this.ids.length;
    const union = new Set<number>();
    this.periods = {
      actual: this.indexPeriod(input.actual, union),
      comparable: this.indexPeriod(input.comparable, union),
    };
    this.cogs = this.index.get(COGS_ID);

    this.abnormal = new Uint8Array(size);
    this.score = Array(size).fill(-1n);
    this.recordBranches(input);
    this.propagateScores();
    this.unlinked = this.countUnlinkedBranches();
    this.cogsDelta = this.recordSalesDeltas(input);
    this.issueDelta = this.issueDeltas(input);
    this.nodeDelta = this.ids.map((_, at) => this.recordedNodeDelta(at));
  }

  private addNode(id: string) {
    if (this.index.has(id)) return;
    this.index.set(id, this.ids.length);
    this.ids.push(id);
    this.children.push([]);
    this.parents.push([]);
  }

  /** Indexes one period's positions and edges, adding edges not yet seen in `union` to the merged graph. */
  private indexPeriod(snapshot: DisplaySnapshot, union: Set<number>): PeriodIndex {
    const size = this.ids.length;
    const { nodes, offset, successors } = snapshot.graph;
    const position = new Int32Array(size).fill(-1);
    const edges = new Set<number>();
    nodes.forEach((node, at) => {
      position[this.index.get(node.inventoryId)!] = at;
    });
    for (let from = 0; from < nodes.length; from++)
      for (let edge = offset[from]; edge < offset[from + 1]; edge++) {
        // CSR edges run material -> receiving item; display edges run receiving item -> material.
        const parent = this.index.get(nodes[successors[edge]].inventoryId)!;
        const child = this.index.get(nodes[from].inventoryId)!;
        const key = parent * size + child;
        edges.add(key);
        if (!union.has(key)) {
          union.add(key);
          this.children[parent].push(child);
          this.parents[child].push(parent);
        }
      }
    return { snapshot, position, edges };
  }

  /** Marks abnormal branch members, records each member's largest amount, and counts branches per boundary. */
  private recordBranches(input: DisplayInput) {
    const actualNodes = input.actual.graph.nodes;
    for (const branch of input.branches) {
      let amount: bigint;
      try {
        amount = decimalMagnitude(branch.endingCostDifference);
      } catch (error) {
        this.diagnostics.push((error as Error).message);
        continue;
      }
      const members = branch.positions
        .flatMap((position) => (actualNodes[position] ? [this.index.get(actualNodes[position].inventoryId)!] : []))
        .filter((member) => member !== this.cogs);
      for (const member of members) {
        this.abnormal[member] = 1;
        if (amount > this.score[member]) this.score[member] = amount;
      }
      const boundary = members.at(-1);
      if (boundary == null) continue;
      const evidence = this.boundaries.get(boundary);
      if (evidence) evidence.count++;
      else this.boundaries.set(boundary, { difference: branch.endingCostDifference, count: 1 });
    }
  }

  /** Propagates the maximum evidence, never a sum, from materials towards COGS in topological order. */
  private propagateScores() {
    const pending = this.children.map((list) => list.length);
    const queue = pending.flatMap((count, at) => (count === 0 ? [at] : []));
    for (let head = 0; head < queue.length; head++)
      for (const parent of this.parents[queue[head]]) {
        if (this.score[queue[head]] > this.score[parent]) this.score[parent] = this.score[queue[head]];
        if (--pending[parent] === 0) queue.push(parent);
      }
    if (queue.length !== this.ids.length)
      this.diagnostics.push('The merged period graphs contain a cycle; affected evidence ordering is partial.');
  }

  /** Counts abnormal branches whose boundary cannot be reached from COGS; all of them when there is no COGS. */
  private countUnlinkedBranches() {
    if (this.cogs == null) return [...this.boundaries.values()].reduce((sum, evidence) => sum + evidence.count, 0);
    const reached = new Uint8Array(this.ids.length);
    const walk = [this.cogs];
    reached[this.cogs] = 1;
    for (let head = 0; head < walk.length; head++)
      for (const child of this.children[walk[head]])
        if (!reached[child]) {
          reached[child] = 1;
          walk.push(child);
        }
    return [...this.boundaries.entries()]
      .filter(([boundary]) => !reached[boundary])
      .reduce((sum, [, evidence]) => sum + evidence.count, 0);
  }

  /** Records each product's sales-cost difference and returns the total COGS difference. */
  private recordSalesDeltas(input: DisplayInput): bigint | null {
    const sales = (snapshot: DisplaySnapshot) =>
      sumBy(
        snapshot.records.sales,
        (sale) => sale.productId,
        (sale) => sale.cost,
        this.diagnostics,
      );
    const actualSales = sales(input.actual);
    const comparableSales = sales(input.comparable);
    for (const product of this.products())
      this.productDelta.set(product, difference(actualSales, comparableSales, this.ids[product]));
    const total = (sums: Map<string, bigint | null>) =>
      [...sums.values()].reduce<bigint | null>((sum, value) => (sum == null || value == null ? null : sum + value), 0n);
    const actualTotal = total(actualSales);
    const comparableTotal = total(comparableSales);
    return actualTotal == null || comparableTotal == null ? null : actualTotal - comparableTotal;
  }

  /** Returns a lookup of the issued-amount difference for each receiving item and material pair. */
  private issueDeltas(input: DisplayInput): (parent: number, child: number) => bigint | null {
    const issues = (snapshot: DisplaySnapshot) =>
      sumBy(
        snapshot.records.issues,
        (issue) => (issue.materialId == null ? null : `${issue.productId}\u0000${issue.materialId}`),
        (issue) => issue.amount,
        this.diagnostics,
      );
    const actualIssues = issues(input.actual);
    const comparableIssues = issues(input.comparable);
    return (parent, child) => difference(actualIssues, comparableIssues, `${this.ids[parent]}\u0000${this.ids[child]}`);
  }

  /** Recorded node-cost difference between periods. */
  private recordedNodeDelta(at: number): bigint | null {
    const values = (['actual', 'comparable'] as const).map((period) => {
      // Like backend threshold preparation, a node absent from one period has no comparable difference.
      const position = this.periods[period].position[at];
      const cost = position < 0 ? null : this.periods[period].snapshot.graph.nodes[position].cost;
      if (cost == null) return null;
      try {
        return microUnits(cost);
      } catch (error) {
        this.diagnostics.push((error as Error).message);
        return null;
      }
    });
    return values[0] == null || values[1] == null ? null : values[0] - values[1];
  }

  private products() {
    return this.cogs == null ? [] : this.children[this.cogs];
  }
  private value(period: Period, node: number, parent?: number): PeriodValue | undefined {
    const { position, snapshot, edges } = this.periods[period];
    const at = position[node];
    if (at < 0) return undefined;
    const recorded = snapshot.graph.nodes[at];
    return {
      position: at,
      quantity: recorded.quantity,
      cost: recorded.cost,
      linked: parent == null || edges.has(parent * this.ids.length + node),
    };
  }
  private ordered(nodes: number[], parent?: number) {
    const delta = (node: number) => (parent == null ? this.nodeDelta[node] : this.issueDelta(parent, node));
    return nodes
      .map((node) => ({ node, delta: delta(node) }))
      .sort((a, b) =>
        this.score[a.node] !== this.score[b.node]
          ? this.score[a.node] > this.score[b.node]
            ? -1
            : 1
          : byMagnitude(a.delta, b.delta) || this.ids[a.node].localeCompare(this.ids[b.node]),
      )
      .map((entry) => entry.node);
  }

  /** Project the bounded display tree for one coverage/expansion state. */
  query(query: DisplayQuery): DisplayResult {
    if (!(query.coverage > 0 && query.coverage <= 100)) throw new Error('Coverage must be between 1 and 100 per cent.');
    const projection: Projection = { query, budget: BUSINESS_LIMIT, businessNodes: 0, budgetReached: false };

    // Rank products by exact |ΔCOGS| and select the shortest prefix reaching the requested share.
    const ranked = this.products()
      .map((product) => ({ product, delta: this.productDelta.get(product) ?? null }))
      .sort((a, b) => byMagnitude(a.delta, b.delta) || this.ids[a.product].localeCompare(this.ids[b.product]));
    const { selected, total, covered } = selectCoverage(ranked, query.coverage);

    const root = this.rootTree();
    const path = new Set(this.cogs == null ? [] : [this.cogs]);
    for (const product of selected)
      if (this.take(projection))
        root.children.push(
          this.render(projection, product, childKey('cogs', this.ids[product]), path, this.cogs, false),
        );
    this.addProductGroups(projection, root, ranked, path);

    const diagnostics = [...new Set(this.diagnostics)];
    return {
      ...layoutTree(root),
      stats: {
        coverage: query.coverage,
        selectedProducts: selected.length,
        products: ranked.length,
        totalAbsolute: formatMicro(total),
        coveredAbsolute: formatMicro(covered),
        coveredPerMille: total === 0n ? null : Number((covered * 1000n) / total),
        cogsDelta: formatOptional(this.cogsDelta) ?? null,
        unavailableProducts: ranked.filter((row) => row.delta == null).length,
        unlinkedBranches: this.unlinked,
        totalNodes: this.ids.length,
        businessNodes: projection.businessNodes,
        budgetReached: projection.budgetReached,
        diagnostics: diagnostics.slice(0, 20),
      },
    };
  }

  /** Reserves one business node from the display budget; returns false once the budget is spent. */
  private take(projection: Projection) {
    if (projection.budget <= 0) {
      projection.budgetReached = true;
      return false;
    }
    projection.budget--;
    projection.businessNodes++;
    return true;
  }

  private nodeItem(node: number, key: string, parent?: number): DisplayItem {
    return {
      key,
      kind: 'node',
      inventoryId: this.ids[node],
      label: node === this.cogs ? 'Cost of Goods Sold' : this.ids[node],
      abnormal: this.abnormal[node] === 1,
      boundary: this.boundaries.get(node),
      terminal: this.children[node].length === 0 && node !== this.cogs,
      delta: formatOptional(
        parent === this.cogs && parent != null ? this.productDelta.get(node) : this.nodeDelta[node],
      ),
      parentDelta: parent == null || parent === this.cogs ? undefined : formatOptional(this.issueDelta(parent, node)),
      shared: this.parents[node].length > 1 ? this.parents[node].length : undefined,
      product: parent != null && parent === this.cogs,
      actual: this.value('actual', node, parent),
      comparable: this.value('comparable', node, parent),
      column: 0,
      row: 0,
    };
  }

  private rootTree(): Tree {
    if (this.cogs != null) {
      return {
        item: { ...this.nodeItem(this.cogs, 'cogs'), delta: formatOptional(this.cogsDelta) },
        children: [],
        folded: false,
      };
    }
    return {
      item: {
        key: 'cogs',
        kind: 'missing',
        label: 'Cost of Goods Sold unavailable',
        note: 'No COGS root recorded in either period',
        abnormal: false,
        column: 0,
        row: 0,
      },
      children: [],
      folded: false,
    };
  }

  /** Render one recorded node: abnormal children stay explicit, normal spans to deeper anomalies become a bridge, the rest folds. */
  private render(
    projection: Projection,
    node: number,
    key: string,
    path: Set<number>,
    parent: number | undefined,
    open: boolean,
  ): Tree {
    const tree: Tree = { item: this.nodeItem(node, key, parent), children: [], folded: false };
    const kids = this.ordered(
      this.children[node].filter((child) => !path.has(child)),
      node,
    );
    const inner = new Set(path).add(node);
    const direct = kids.filter((child) => this.abnormal[child] || (open && this.score[child] >= 0));
    const carriers = open ? [] : kids.filter((child) => !this.abnormal[child] && this.score[child] >= 0);
    const rest = kids.filter((child) => this.score[child] < 0);
    const renderChild: ChildRenderer = (member, memberKey, memberParent, memberOpen, into) => {
      if (!this.take(projection)) return false;
      into.push(this.render(projection, member, memberKey, inner, memberParent, memberOpen));
      return true;
    };
    for (const member of direct.slice(SIGNAL_LIMIT)) rest.push(member);
    for (const member of direct.slice(0, SIGNAL_LIMIT))
      if (!renderChild(member, childKey(key, this.ids[member]), node, open && !this.abnormal[member], tree.children))
        rest.push(member);
    if (carriers.length) this.renderCarriers(projection, tree, node, key, inner, carriers, rest, renderChild);
    if (rest.length) this.renderRest(projection, tree, node, key, rest, renderChild);
    return tree;
  }

  /**
   * Shows normal children that lead to deeper anomalies. When their bridge is expanded they are rendered directly
   * (overflow joins `rest`); otherwise a bridge fold stands in for them and leads to the nearest abnormal anchors.
   */
  private renderCarriers(
    projection: Projection,
    tree: Tree,
    node: number,
    key: string,
    inner: Set<number>,
    carriers: number[],
    rest: number[],
    renderChild: ChildRenderer,
  ) {
    const bridgeKey = `${key}#bridge`;
    if (projection.query.expanded[bridgeKey]) {
      for (const member of carriers.slice(0, SIGNAL_LIMIT))
        if (!renderChild(member, childKey(key, this.ids[member]), node, true, tree.children)) rest.push(member);
      rest.push(...carriers.slice(SIGNAL_LIMIT));
      return;
    }
    // Hidden normal nodes lead to the nearest abnormal anchors; the gap never claims a direct relationship.
    const { hidden, anchors } = this.nearestAnchors(carriers, inner);
    const ranked = this.ordered(anchors);
    const bridge = fold(
      bridgeKey,
      'bridge',
      '…',
      `${hidden} normal ${hidden === 1 ? 'node' : 'nodes'} hidden${ranked.length > SIGNAL_LIMIT ? ` · ${ranked.length - SIGNAL_LIMIT} further abnormal nodes inside` : ''} · click to expand`,
      false,
      bridgeKey,
    );
    tree.children.push(bridge);
    for (const anchor of ranked.slice(0, SIGNAL_LIMIT))
      renderChild(anchor, childKey(bridgeKey, this.ids[anchor]), undefined, false, bridge.children);
    if (!bridge.children.length)
      bridge.item.note = `${hidden} hidden nodes lead to abnormal evidence · click to expand`;
  }

  /** Walks normal evidence carriers down to the first abnormal nodes, outside the current path. */
  private nearestAnchors(carriers: number[], inner: Set<number>) {
    const seen = new Set(carriers);
    const walk = [...carriers];
    const anchors: number[] = [];
    for (let head = 0; head < walk.length; head++)
      for (const next of this.children[walk[head]]) {
        if (seen.has(next) || inner.has(next) || this.score[next] < 0) continue;
        seen.add(next);
        if (this.abnormal[next]) anchors.push(next);
        else walk.push(next);
      }
    return { hidden: walk.length, anchors };
  }

  /** Renders the pages of remaining children the user has opened, and folds the others into one summary item. */
  private renderRest(
    projection: Projection,
    tree: Tree,
    node: number,
    key: string,
    rest: number[],
    renderChild: ChildRenderer,
  ) {
    const restKey = `${key}#rest`;
    const shown = (projection.query.expanded[restKey] || 0) * PAGE_SIZE;
    let hidden = 0;
    let abnormal = false;
    let sum: bigint | null = 0n;
    this.ordered(rest, node).forEach((member, at) => {
      if (at < shown && renderChild(member, childKey(key, this.ids[member]), node, false, tree.children)) return;
      hidden++;
      abnormal ||= this.score[member] >= 0;
      const delta = this.issueDelta(node, member);
      sum = sum == null || delta == null ? null : sum + delta;
    });
    if (hidden)
      tree.children.push(
        fold(
          restKey,
          'rest',
          `+${hidden}`,
          `${hidden} ${shown ? 'more ' : ''}${hidden === 1 ? 'item' : 'items'} · Δ ${sum == null ? 'unavailable' : formatMicro(sum)}${abnormal ? ' · contains abnormal evidence' : ''}`,
          abnormal,
          restKey,
        ),
      );
  }

  /** Appends the unselected products in two pageable groups, abnormal and normal. */
  private addProductGroups(projection: Projection, root: Tree, ranked: ProductRank[], path: Set<number>) {
    const chosen = new Set(root.children.map((tree) => this.index.get(tree.item.inventoryId!)!));
    for (const group of ['abnormal', 'normal'] as const) {
      const members = ranked.filter(
        (row) => !chosen.has(row.product) && this.score[row.product] >= 0 === (group === 'abnormal'),
      );
      const groupKey = `cogs#${group}`;
      const shown = (projection.query.expanded[groupKey] || 0) * PAGE_SIZE;
      let opened = 0;
      for (const row of members.slice(0, shown))
        if (this.take(projection)) {
          root.children.push(
            this.render(projection, row.product, childKey('cogs', this.ids[row.product]), path, this.cogs, false),
          );
          opened++;
        }
      const hidden = members.length - opened;
      const budgetLeft = projection.budget > 0;
      root.children.push(
        fold(
          groupKey,
          'group',
          `${group === 'abnormal' ? 'Abnormal' : 'Normal'} products (${members.length})`,
          !members.length
            ? 'No other products in this group'
            : !hidden
              ? 'All members shown'
              : `${opened ? `${opened} shown · ` : ''}${hidden} remaining · ${budgetLeft ? `click to show ${Math.min(PAGE_SIZE, hidden)} more` : 'display budget reached; collapse other folds first'}`,
          group === 'abnormal' && members.length > 0,
          hidden && budgetLeft ? groupKey : undefined,
        ),
      );
    }
  }
}
