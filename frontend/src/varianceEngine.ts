import { AnalysisAdjacencyEntry, AnalysisNode } from './analysisGraph';

export type SeverityLevel = 'sync' | 'minor' | 'moderate' | 'major' | 'favorable';

/**
 * Three-factor cost structure: 料 (Material), 工 (Labor), 费 (Manufacturing Overhead)
 * Reserved for convolution-based decomposition algorithms from backend.
 */
export interface CostBreakdown {
  materialCost?: number;   // 料 (Direct Material)
  laborCost?: number;      // 工 (Direct Labor)
  overheadCost?: number;   // 费 (Manufacturing Overhead)
  materialRatio?: number;  // 0~100 (Default 100)
  laborRatio?: number;     // 0~100 (Default 0)
  overheadRatio?: number;  // 0~100 (Default 0)
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
        materialCost: cb.materialCost ?? (total * (mRatio / sum)),
        laborCost: cb.laborCost ?? (total * (lRatio / sum)),
        overheadCost: cb.overheadCost ?? (total * (oRatio / sum)),
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
  for (const [key, name] of Object.entries(MATERIAL_NAMES)) {
    if (inventoryId.includes(key)) {
      return name;
    }
  }
  // Clean readable title fallback
  return inventoryId
    .replace(/^EBOM-|^PBOM-/, '')
    .replace(/-(ACT|M|BLK|LOT)$/, '')
    .replace(/-/g, ' ')
    .replace(/\b\w/g, (c) => c.toUpperCase()) + ' Component';
};

export const resolveStation = (level: number, inventoryId: string): string => {
  if (level === 0) return 'ST-00 (Final Integration)';
  if (inventoryId.includes('ROT') || inventoryId.includes('BRG')) return 'ST-18 (Rotary Machining)';
  if (inventoryId.includes('EL')) return 'ST-04 (Avionics Harness)';
  if (inventoryId.includes('TC')) return 'ST-12 (Autoclave & Thermal)';
  if (level === 1) return 'ST-08 (Sub-assembly Line)';
  return `ST-${10 + (level * 2)}`;
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

export const reconcileDualBom = (
  actualEntries: AnalysisAdjacencyEntry[],
  bomEntries: AnalysisAdjacencyEntry[],
  threshold = 250.0
): DualBomReconciliationResult => {
  const actualNodeMap = new Map<string, AnalysisNode>();
  const bomNodeMap = new Map<string, AnalysisNode>();

  // Map graph relations (downstream = parent, upstream = component)
  const parentChildMap = new Map<string, Set<string>>(); // parent -> children
  const childParentMap = new Map<string, string>(); // child -> parent

  const collectRelations = (entries: AnalysisAdjacencyEntry[]) => {
    for (const entry of entries) {
      const childId = entry.upstream.inventoryId;
      for (const parent of entry.downstream) {
        const parentId = parent.inventoryId;
        if (!parentChildMap.has(parentId)) {
          parentChildMap.set(parentId, new Set());
        }
        parentChildMap.get(parentId)!.add(childId);
        childParentMap.set(childId, parentId);
      }
    }
  };

  for (const entry of actualEntries) {
    actualNodeMap.set(entry.upstream.inventoryId, entry.upstream);
    for (const d of entry.downstream) {
      actualNodeMap.set(d.inventoryId, d);
    }
  }

  for (const entry of bomEntries) {
    bomNodeMap.set(entry.upstream.inventoryId, entry.upstream);
    for (const d of entry.downstream) {
      bomNodeMap.set(d.inventoryId, d);
    }
  }

  collectRelations(actualEntries);
  collectRelations(bomEntries);

  // Collect all unique canonical node identifiers
  const allCanonicalIds = new Set<string>();
  const canonicalMap = new Map<string, string>(); // specific id -> canonical id

  const toCanonical = (id: string): string => {
    return id.replace(/^EBOM-|^PBOM-/, '').replace(/-(ACT|M|BLK|LOT)$/, '');
  };

  for (const id of [...actualNodeMap.keys(), ...bomNodeMap.keys()]) {
    const canonical = toCanonical(id);
    allCanonicalIds.add(canonical);
    canonicalMap.set(id, canonical);
  }

  // Map canonical relationships: parent -> children, child -> parent
  const canonicalChildrenMap = new Map<string, string[]>();
  const canonicalParentMap = new Map<string, string>();

  for (const [pId, children] of parentChildMap.entries()) {
    const pCanonical = toCanonical(pId);
    if (!canonicalChildrenMap.has(pCanonical)) {
      canonicalChildrenMap.set(pCanonical, []);
    }
    const list = canonicalChildrenMap.get(pCanonical)!;
    for (const child of children) {
      const cCanonical = toCanonical(child);
      if (!list.includes(cCanonical) && cCanonical !== pCanonical) {
        list.push(cCanonical);
        canonicalParentMap.set(cCanonical, pCanonical);
      }
    }
  }

  // Identify root canonical nodes (no parent)
  const canonicalRoots = Array.from(allCanonicalIds).filter((c) => !canonicalParentMap.has(c));

  // Depth-First Pre-Order Traversal to preserve top-to-bottom connected tree hierarchy
  interface DfsNodeInfo {
    canonical: string;
    level: number;
    parentId: string | null;
    isLastChild: boolean;
    ancestorContinues: boolean[];
  }

  const dfsList: DfsNodeInfo[] = [];
  const visited = new Set<string>();

  const dfsTraverse = (
    nodeId: string,
    level: number,
    parentId: string | null,
    isLast: boolean,
    ancestorContinues: boolean[]
  ) => {
    if (visited.has(nodeId)) return;
    visited.add(nodeId);

    dfsList.push({
      canonical: nodeId,
      level,
      parentId,
      isLastChild: isLast,
      ancestorContinues,
    });

    const children = canonicalChildrenMap.get(nodeId) || [];
    children.forEach((childId, idx) => {
      const isLastChildOfThis = idx === children.length - 1;
      const nextAncestorContinues = level === 0 ? [] : [...ancestorContinues, !isLast];
      dfsTraverse(childId, level + 1, nodeId, isLastChildOfThis, nextAncestorContinues);
    });
  };

  canonicalRoots.forEach((rootId, idx) => {
    dfsTraverse(rootId, 0, null, idx === canonicalRoots.length - 1, []);
  });

  // Any remaining orphan nodes
  for (const c of allCanonicalIds) {
    if (!visited.has(c)) {
      dfsTraverse(c, 1, null, true, []);
    }
  }

  // Build reconciled nodes following the DFS tree order
  const dualNodes: DualBomNode[] = [];
  const nodesById = new Map<string, DualBomNode>();
  let totalCostVariance = 0;
  let penetratedCount = 0;

  dfsList.forEach((item, index) => {
    const canonical = item.canonical;

    // Match actual node variant
    let actualNode: AnalysisNode | undefined;
    for (const [id, node] of actualNodeMap.entries()) {
      if (toCanonical(id) === canonical) {
        actualNode = node;
        break;
      }
    }

    // Match bom node variant
    let bomNode: AnalysisNode | undefined;
    for (const [id, node] of bomNodeMap.entries()) {
      if (toCanonical(id) === canonical) {
        bomNode = node;
        break;
      }
    }

    const actualQty = actualNode?.quantity ?? (bomNode?.quantity ?? 1);
    const standardQty = bomNode?.quantity ?? actualQty;
    const actualCost = actualNode?.cost ?? null;
    const unitCost = actualCost !== null && actualQty > 0 ? actualCost / actualQty : null;
    const baselineCost = bomNode?.cost ?? (unitCost !== null ? standardQty * unitCost : null);

    const qtyDelta = actualQty - standardQty;
    const qtyDeltaPercent = standardQty > 0 ? (qtyDelta / standardQty) * 100 : 0;
    const costDelta = actualCost !== null && baselineCost !== null ? actualCost - baselineCost : null;

    // Determine severity
    let severity: SeverityLevel = 'sync';
    let isPenetrated = false;

    const effectiveDelta = costDelta !== null ? costDelta : qtyDelta * 50;

    if (Math.abs(effectiveDelta) < 0.001) {
      severity = 'sync';
    } else if (effectiveDelta < -threshold) {
      severity = 'favorable';
    } else if (effectiveDelta > 0 && effectiveDelta <= threshold) {
      severity = 'minor';
    } else if (effectiveDelta > threshold && effectiveDelta <= threshold * 3) {
      severity = 'moderate';
      isPenetrated = true;
      penetratedCount++;
    } else if (effectiveDelta > threshold * 3) {
      severity = 'major';
      isPenetrated = true;
      penetratedCount++;
    } else {
      severity = 'sync';
    }

    if (costDelta !== null) {
      totalCostVariance += costDelta;
    }

    const inventoryId = actualNode?.inventoryId || bomNode?.inventoryId || `BOM-${canonical}`;
    const name = resolveMaterialName(canonical);
    const station = resolveStation(item.level, canonical);
    const workOrder = resolveWorkOrder(canonical, index);
    const lotNo = resolveLotNo(canonical);
    const ecn = `ECN-2024-R${(index % 6) + 1}`;

    let statusText = '[Normal]';
    if (severity === 'major') statusText = '[Over-issued]';
    else if (severity === 'moderate') statusText = '[Scrap Extra]';
    else if (severity === 'favorable') statusText = '[Released]';
    else if (severity === 'minor') statusText = '[Over-cost]';

    const dualNode: DualBomNode = {
      id: inventoryId,
      name,
      level: item.level,
      standardQty,
      actualQty,
      quantityDelta: qtyDelta,
      quantityDeltaPercent: qtyDeltaPercent,
      unitCost,
      baselineCost,
      actualCost,
      costDelta,
      severity,
      workOrder,
      lotNo,
      station,
      ecn,
      isPenetrated,
      parentId: item.parentId,
      childrenIds: canonicalChildrenMap.get(canonical) || [],
      statusText,
      isLastChild: item.isLastChild,
      ancestorContinues: item.ancestorContinues,
    };

    dualNodes.push(dualNode);
    nodesById.set(inventoryId, dualNode);
  });

  // Material ledger table items
  const ledgerItems: MaterialLedgerItem[] = dualNodes.map((n) => {
    let status: MaterialLedgerItem['status'] = 'Normal';
    if (n.severity === 'major') status = 'Over-issued';
    else if (n.severity === 'moderate') status = 'Scrap Extra';
    else if (n.severity === 'favorable') status = 'Released';
    else if (n.severity === 'minor') status = 'Over-cost';

    return {
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
      status,
      severity: n.severity,
      node: n,
    };
  });

  const rootNodes = dualNodes.filter((n) => n.level === 0);

  // Overall Risk Assessment
  let riskTier = 'Nominal In-Spec';
  if (penetratedCount >= 3 || totalCostVariance > 1000) {
    riskTier = 'Tier-1 High Risk';
  } else if (penetratedCount > 0 || totalCostVariance > 300) {
    riskTier = 'Tier-2 Moderate Risk';
  }

  return {
    nodes: dualNodes,
    nodesById,
    rootNodes,
    ledgerItems,
    netVariance: totalCostVariance,
    penetratedNodesCount: penetratedCount,
    riskTier,
  };
};

export const buildAuditDossier = (node: DualBomNode, allNodes: DualBomNode[]): AuditDossier => {
  const rootCauses: RootCauseItem[] = [];
  const delta = node.costDelta ?? (node.quantityDelta * (node.unitCost ?? 100));

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
    impactEchelon: node.severity === 'major' ? 'Tier-1 High Risk' : node.severity === 'moderate' ? 'Tier-2 Moderate' : 'Nominal',
    leadTimeSlippage: node.severity === 'major' ? '+3.5 shifts' : node.severity === 'moderate' ? '+1.0 shifts' : 'On Schedule',
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
