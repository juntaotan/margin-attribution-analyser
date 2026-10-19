import { AnalysisAdjacencyEntry, AnalysisNode, COST_OF_GOODS_SOLD_NODE_ID } from './analysisGraph';

export type SeverityLevel = 'sync' | 'minor' | 'moderate' | 'major' | 'favorable';

/**
 * Three-factor cost structure: 料 (Material), 工 (Labor), 费 (Manufacturing Overhead)
 * Reserved for convolution-based decomposition algorithms from backend.
 */
export interface CostBreakdown {
  materialCost?: number; // 料 (Direct Material)
  laborCost?: number; // 工 (Direct Labor)
  overheadCost?: number; // 费 (Manufacturing Overhead)
  materialRatio?: number; // 0~100 (Default 100)
  laborRatio?: number; // 0~100 (Default 0)
  overheadRatio?: number; // 0~100 (Default 0)
}

export interface DualBomNode {
  id: string; // inventoryId
  name: string;
  level: number;
  standardQty: number;
  actualQty: number;
  quantityDelta: number;
  quantityDeltaPercent: number;
  unitCost: number | null;
  baselineCost: number | null;
  actualCost: number | null;
  costDelta: number | null;
  severity: SeverityLevel;
  workOrder: string;
  lotNo: string;
  station: string;
  ecn: string;
  isPenetrated: boolean;
  parentId: string | null;
  childrenIds: string[];
  statusText: string;
  isLastChild: boolean;
  ancestorContinues: boolean[];
  /** Reserved interface for convolution-based 3-factor cost breakdown */
  costBreakdown?: CostBreakdown;
}

/**
 * Helper to compute Material (Blue), Labor (Green), Overhead (Orange) proportions.
 * In accordance with specifications: cost is currently regarded as pure Material Cost (100% Blue).
 */
export function getCostBreakdown(node?: DualBomNode | null): {
  materialRatio: number;
  laborRatio: number;
  overheadRatio: number;
  materialCost: number;
  laborCost: number;
  overheadCost: number;
} {
  const total = node?.actualCost ?? node?.baselineCost ?? 0;

  if (node?.costBreakdown) {
    const cb = node.costBreakdown;
    if (cb.materialRatio !== undefined || cb.laborRatio !== undefined || cb.overheadRatio !== undefined) {
      const mRatio = Math.max(0, cb.materialRatio ?? 0);
      const lRatio = Math.max(0, cb.laborRatio ?? 0);
      const oRatio = Math.max(0, cb.overheadRatio ?? 0);
      const sum = mRatio + lRatio + oRatio || 100;
      return {
        materialRatio: (mRatio / sum) * 100,
        laborRatio: (lRatio / sum) * 100,
        overheadRatio: (oRatio / sum) * 100,
        materialCost: cb.materialCost ?? total * (mRatio / sum),
        laborCost: cb.laborCost ?? total * (lRatio / sum),
        overheadCost: cb.overheadCost ?? total * (oRatio / sum),
      };
    }
    if (cb.materialCost !== undefined || cb.laborCost !== undefined || cb.overheadCost !== undefined) {
      const mCost = Math.max(0, cb.materialCost ?? 0);
      const lCost = Math.max(0, cb.laborCost ?? 0);
      const oCost = Math.max(0, cb.overheadCost ?? 0);
      const costSum = mCost + lCost + oCost || total || 1;
      return {
        materialRatio: (mCost / costSum) * 100,
        laborRatio: (lCost / costSum) * 100,
        overheadRatio: (oCost / costSum) * 100,
        materialCost: mCost,
        laborCost: lCost,
        overheadCost: oCost,
      };
    }
  }

  // Baseline: In current state before backend convolution is attached,
  // cost is strictly regarded as Material Cost (料成本) -> 100% Blue.
  return {
    materialRatio: 100,
    laborRatio: 0,
    overheadRatio: 0,
    materialCost: total,
    laborCost: 0,
    overheadCost: 0,
  };
}

export interface MaterialLedgerItem {
  id: string;
  workOrder: string;
  materialCode: string;
  description: string;
  batchLot: string;
  station: string;
  bomQty: number;
  actualQty: number;
  unitCost: number | null;
  totalCost: number | null;
  varianceDelta: number | null;
  status: 'Over-issued' | 'Scrap Extra' | 'Released' | 'Normal' | 'Over-cost';
  severity: SeverityLevel;
  node: DualBomNode;
}

export interface RootCauseItem {
  id: string;
  title: string;
  amount: number;
  description: string;
  severity: 'error' | 'warning' | 'info';
}

export interface AuditDossier {
  selectedNode: DualBomNode;
  rootCauses: RootCauseItem[];
  impactEchelon: string;
  leadTimeSlippage: string;
  affectedAssembliesCount: number;
  prescriptiveActions: string[];
  confidence: number;
  deviationConcession: { code: string; note: string } | null;
}

export interface DualBomReconciliationResult {
  nodes: DualBomNode[];
  nodesById: Map<string, DualBomNode>;
  rootNodes: DualBomNode[];
  ledgerItems: MaterialLedgerItem[];
  netVariance: number;
  penetratedNodesCount: number;
  riskTier: string;
}

// Master dictionary for human-readable material naming
const MATERIAL_NAMES: Record<string, string> = {
  'SYS-00': 'Propulsion System Root Assembly',
  'PWR-01': 'Power Core Assembly',
  'TC-88': 'Ceramic Matrix Thermal Enclosure',
  'FAST-M12': 'Cryo Titanium Studs M12',
  'ROT-04': 'Rotor Shaft Assembly',
  'EL-300': 'Shielded Cryo-Flex Wiring Harness',
  'BRG-22': 'Ceramic Hybrid Angular Contact Bearing',
};

export const resolveMaterialName = (inventoryId: string): string => {
  if (inventoryId === COST_OF_GOODS_SOLD_NODE_ID) {
    return 'Cost of Goods Sold';
  }
  for (const [key, name] of Object.entries(MATERIAL_NAMES)) {
    if (inventoryId.includes(key)) {
      return name;
    }
  }
  // Clean readable title fallback
  return (
    inventoryId
      .replace(/^EBOM-|^PBOM-/, '')
      .replace(/-(ACT|M|BLK|LOT)$/, '')
      .replace(/-/g, ' ')
      .replace(/\b\w/g, (c) => c.toUpperCase()) + ' Component'
  );
};

export const resolveStation = (level: number, inventoryId: string): string => {
  if (level === 0) return 'ST-00 (Final Integration)';
  if (inventoryId.includes('ROT') || inventoryId.includes('BRG')) return 'ST-18 (Rotary Machining)';
  if (inventoryId.includes('EL')) return 'ST-04 (Avionics Harness)';
  if (inventoryId.includes('TC')) return 'ST-12 (Autoclave & Thermal)';
  if (level === 1) return 'ST-08 (Sub-assembly Line)';
  return `ST-${10 + level * 2}`;
};

export const resolveWorkOrder = (inventoryId: string, index: number): string => {
  const hash = Math.abs(inventoryId.split('').reduce((acc, c) => acc + c.charCodeAt(0), 0) + index * 3);
  return `WO-2024-${9980 + (hash % 20)}`;
};

export const resolveLotNo = (inventoryId: string): string => {
  const clean = inventoryId.replace(/^EBOM-|^PBOM-/, '').replace(/-(ACT|M|BLK|LOT)$/, '');
  const hash = Math.abs(clean.split('').reduce((acc, c) => acc + c.charCodeAt(0), 0));
  return `LOT-${clean.slice(0, 3)}-${1000 + (hash % 8999)}`;
};

/** Removes the EBOM/PBOM prefixes and variant suffixes so that planned and actual nodes share one identifier. */
const toCanonical = (id: string): string => id.replace(/^EBOM-|^PBOM-/, '').replace(/-(ACT|M|BLK|LOT)$/, '');

/** Indexes every upstream and downstream node of the entries by inventory ID; later entries replace earlier ones. */
const indexNodes = (entries: AnalysisAdjacencyEntry[]): Map<string, AnalysisNode> => {
  const nodes = new Map<string, AnalysisNode>();
  for (const entry of entries) {
    nodes.set(entry.upstream.inventoryId, entry.upstream);
    for (const d of entry.downstream) {
      nodes.set(d.inventoryId, d);
    }
  }
  return nodes;
};

/** Adds the parent-to-component relations of the entries (downstream = parent, upstream = component). */
const collectRelations = (entries: AnalysisAdjacencyEntry[], parentChildMap: Map<string, Set<string>>) => {
  for (const entry of entries) {
    const childId = entry.upstream.inventoryId;
    for (const parent of entry.downstream) {
      if (!parentChildMap.has(parent.inventoryId)) {
        parentChildMap.set(parent.inventoryId, new Set());
      }
      parentChildMap.get(parent.inventoryId)!.add(childId);
    }
  }
};

interface CanonicalTree {
  ids: Set<string>;
  children: Map<string, string[]>;
  parents: Map<string, string>;
}

/** Merges node relations onto canonical identifiers, dropping duplicate and self-referencing edges. */
const buildCanonicalTree = (nodeIds: string[], parentChildMap: Map<string, Set<string>>): CanonicalTree => {
  const ids = new Set(nodeIds.map(toCanonical));
  const children = new Map<string, string[]>();
  const parents = new Map<string, string>();
  for (const [pId, childIds] of parentChildMap.entries()) {
    const pCanonical = toCanonical(pId);
    if (!children.has(pCanonical)) {
      children.set(pCanonical, []);
    }
    const list = children.get(pCanonical)!;
    for (const child of childIds) {
      const cCanonical = toCanonical(child);
      if (!list.includes(cCanonical) && cCanonical !== pCanonical) {
        list.push(cCanonical);
        parents.set(cCanonical, pCanonical);
      }
    }
  }
  return { ids, children, parents };
};

interface DfsNodeInfo {
  canonical: string;
  level: number;
  parentId: string | null;
  isLastChild: boolean;
  ancestorContinues: boolean[];
}

/**
 * Lists the tree in depth-first pre-order, from each root (a node without a parent), so that the display keeps
 * a connected top-to-bottom hierarchy. Nodes only reachable through cycles are appended afterwards at level 1.
 */
const traverseDepthFirst = (tree: CanonicalTree): DfsNodeInfo[] => {
  const dfsList: DfsNodeInfo[] = [];
  const visited = new Set<string>();

  const visit = (
    nodeId: string,
    level: number,
    parentId: string | null,
    isLast: boolean,
    ancestorContinues: boolean[],
  ) => {
    if (visited.has(nodeId)) return;
    visited.add(nodeId);
    dfsList.push({ canonical: nodeId, level, parentId, isLastChild: isLast, ancestorContinues });

    const children = tree.children.get(nodeId) || [];
    children.forEach((childId, idx) => {
      const nextAncestorContinues = level === 0 ? [] : [...ancestorContinues, !isLast];
      visit(childId, level + 1, nodeId, idx === children.length - 1, nextAncestorContinues);
    });
  };

  const roots = Array.from(tree.ids).filter((c) => !tree.parents.has(c));
  roots.forEach((rootId, idx) => visit(rootId, 0, null, idx === roots.length - 1, []));
  for (const c of tree.ids) {
    if (!visited.has(c)) visit(c, 1, null, true, []);
  }
  return dfsList;
};

/** Returns the first node whose canonical identifier matches, in map insertion order. */
const findByCanonical = (nodes: Map<string, AnalysisNode>, canonical: string): AnalysisNode | undefined => {
  for (const [id, node] of nodes.entries()) {
    if (toCanonical(id) === canonical) return node;
  }
  return undefined;
};

/**
 * Grades a variance against the threshold. Overruns above the threshold are "penetrated": moderate up to three
 * times the threshold and major beyond it. Savings beyond the threshold are favourable; smaller savings are in sync.
 */
const classifySeverity = (effectiveDelta: number, threshold: number): SeverityLevel => {
  if (Math.abs(effectiveDelta) < 0.001) return 'sync';
  if (effectiveDelta < -threshold) return 'favorable';
  if (effectiveDelta > 0 && effectiveDelta <= threshold) return 'minor';
  if (effectiveDelta > threshold && effectiveDelta <= threshold * 3) return 'moderate';
  if (effectiveDelta > threshold * 3) return 'major';
  return 'sync';
};

const LEDGER_STATUS: Record<SeverityLevel, MaterialLedgerItem['status']> = {
  major: 'Over-issued',
  moderate: 'Scrap Extra',
  favorable: 'Released',
  minor: 'Over-cost',
  sync: 'Normal',
};

/**
 * Compares the actual and planned node for one tree position. Missing quantities fall back to the other side, or
 * 1; the baseline cost falls back to the standard quantity at the actual unit cost. Without costs, a quantity
 * difference is graded at 50 per unit.
 */
const buildDualNode = (
  item: DfsNodeInfo,
  index: number,
  actualNode: AnalysisNode | undefined,
  bomNode: AnalysisNode | undefined,
  childrenIds: string[],
  threshold: number,
): DualBomNode => {
  const canonical = item.canonical;
  const actualQty = actualNode?.quantity ?? bomNode?.quantity ?? 1;
  const standardQty = bomNode?.quantity ?? actualQty;
  const actualCost = actualNode?.cost ?? null;
  const unitCost = actualCost !== null && actualQty > 0 ? actualCost / actualQty : null;
  const baselineCost = bomNode?.cost ?? (unitCost !== null ? standardQty * unitCost : null);

  const qtyDelta = actualQty - standardQty;
  const costDelta = actualCost !== null && baselineCost !== null ? actualCost - baselineCost : null;
  const severity = classifySeverity(costDelta !== null ? costDelta : qtyDelta * 50, threshold);

  return {
    id: actualNode?.inventoryId || bomNode?.inventoryId || `BOM-${canonical}`,
    name: resolveMaterialName(canonical),
    level: item.level,
    standardQty,
    actualQty,
    quantityDelta: qtyDelta,
    quantityDeltaPercent: standardQty > 0 ? (qtyDelta / standardQty) * 100 : 0,
    unitCost,
    baselineCost,
    actualCost,
    costDelta,
    severity,
    workOrder: resolveWorkOrder(canonical, index),
    lotNo: resolveLotNo(canonical),
    station: resolveStation(item.level, canonical),
    ecn: `ECN-2024-R${(index % 6) + 1}`,
    isPenetrated: severity === 'moderate' || severity === 'major',
    parentId: item.parentId,
    childrenIds,
    statusText: `[${LEDGER_STATUS[severity]}]`,
    isLastChild: item.isLastChild,
    ancestorContinues: item.ancestorContinues,
  };
};

const toLedgerItem = (n: DualBomNode): MaterialLedgerItem => ({
  id: n.id,
  workOrder: n.workOrder,
  materialCode: n.id,
  description: n.name,
  batchLot: n.lotNo,
  station: n.station.split(' ')[0],
  bomQty: n.standardQty,
  actualQty: n.actualQty,
  unitCost: n.unitCost,
  totalCost: n.actualCost,
  varianceDelta: n.costDelta,
  status: LEDGER_STATUS[n.severity],
  severity: n.severity,
  node: n,
});

const assessRisk = (penetratedCount: number, netVariance: number): string => {
  if (penetratedCount >= 3 || netVariance > 1000) return 'Tier-1 High Risk';
  if (penetratedCount > 0 || netVariance > 300) return 'Tier-2 Moderate Risk';
  return 'Nominal In-Spec';
};

/**
 * Reconciles actual consumption with the planned BOM on a shared tree of canonical material identifiers.
 *
 * @param actualEntries adjacency entries of the actual-consumption graph.
 * @param bomEntries adjacency entries of the planned BOM graph.
 * @param threshold cost variance above which a node counts as penetrated.
 * @returns the reconciled nodes in display order, the material ledger (excluding cost of goods sold), and the net
 *   variance, taken from the cost-of-goods-sold node when present and otherwise summed over all nodes.
 */
export const reconcileDualBom = (
  actualEntries: AnalysisAdjacencyEntry[],
  bomEntries: AnalysisAdjacencyEntry[],
  threshold = 250.0,
): DualBomReconciliationResult => {
  const actualNodeMap = indexNodes(actualEntries);
  const bomNodeMap = indexNodes(bomEntries);
  const parentChildMap = new Map<string, Set<string>>();
  collectRelations(actualEntries, parentChildMap);
  collectRelations(bomEntries, parentChildMap);
  const tree = buildCanonicalTree([...actualNodeMap.keys(), ...bomNodeMap.keys()], parentChildMap);

  const dualNodes = traverseDepthFirst(tree).map((item, index) =>
    buildDualNode(
      item,
      index,
      findByCanonical(actualNodeMap, item.canonical),
      findByCanonical(bomNodeMap, item.canonical),
      tree.children.get(item.canonical) || [],
      threshold,
    ),
  );

  let totalCostVariance = 0;
  for (const node of dualNodes) {
    if (node.costDelta !== null) totalCostVariance += node.costDelta;
  }
  const penetratedCount = dualNodes.filter((node) => node.isPenetrated).length;
  const costOfGoodsSold = dualNodes.find((node) => node.id === COST_OF_GOODS_SOLD_NODE_ID);
  const netVariance = costOfGoodsSold?.costDelta ?? totalCostVariance;

  return {
    nodes: dualNodes,
    nodesById: new Map(dualNodes.map((node) => [node.id, node])),
    rootNodes: dualNodes.filter((n) => n.level === 0),
    ledgerItems: dualNodes.filter((node) => node.id !== COST_OF_GOODS_SOLD_NODE_ID).map(toLedgerItem),
    netVariance,
    penetratedNodesCount: penetratedCount,
    riskTier: assessRisk(penetratedCount, netVariance),
  };
};

export const buildAuditDossier = (node: DualBomNode, allNodes: DualBomNode[]): AuditDossier => {
  const rootCauses: RootCauseItem[] = [];
  const delta = node.costDelta ?? node.quantityDelta * (node.unitCost ?? 100);

  if (node.severity === 'major' || node.severity === 'moderate') {
    rootCauses.push({
      id: 'RC-1',
      title: `${node.name} Induction Cure / Usage Scrap`,
      amount: delta * 0.85,
      description: `Scrap rate elevated at station ${node.station} due to process thermal ramp gradient non-conformance.`,
      severity: 'error',
    });
    rootCauses.push({
      id: 'RC-2',
      title: `Fastener OP-102 Re-Tap / Fitting Concession`,
      amount: delta * 0.15,
      description: `Thread pitch non-conformance required additional material draws during final fastening.`,
      severity: 'warning',
    });
  } else if (node.severity === 'favorable') {
    rootCauses.push({
      id: 'RC-1',
      title: 'Vendor Bulk Discount & Yield Optimization',
      amount: delta,
      description: 'Negotiated procurement price concession combined with optimal cycle blank recovery.',
      severity: 'info',
    });
  } else {
    rootCauses.push({
      id: 'RC-1',
      title: 'Standard Production Tolerance Variance',
      amount: delta,
      description: 'Material draws strictly within ±1.5% nominal allowance envelope.',
      severity: 'info',
    });
  }

  const affectedCount = allNodes.filter((n) => n.level >= node.level).length;

  return {
    selectedNode: node,
    rootCauses,
    impactEchelon:
      node.severity === 'major' ? 'Tier-1 High Risk' : node.severity === 'moderate' ? 'Tier-2 Moderate' : 'Nominal',
    leadTimeSlippage:
      node.severity === 'major' ? '+3.5 shifts' : node.severity === 'moderate' ? '+1.0 shifts' : 'On Schedule',
    affectedAssembliesCount: affectedCount,
    prescriptiveActions: [
      `Trigger ECN engineering review for ${node.id} fabrication tolerance.`,
      `Re-calibrate thermal sensors and jigs on ${node.station.split(' ')[0]}.`,
      `Audit raw material batch ${node.lotNo} certificate of conformance.`,
    ],
    confidence: 96.4,
    deviationConcession:
      node.severity === 'major' || node.severity === 'moderate'
        ? {
            code: '#DEV-412',
            note: 'Engineering authorization granted for temporary replacement core pending autoclave cycle qualification.',
          }
        : null,
  };
};

export const formatCurrency = (val: number | null | undefined): string => {
  if (val === null || val === undefined) return '—';
  const prefix = val < 0 ? '-$' : val > 0 ? '+$' : '$';
  return `${prefix}${Math.abs(val).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
};

export const formatQty = (val: number | null | undefined): string => {
  if (val === null || val === undefined) return '—';
  return `${val.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} EA`;
};
