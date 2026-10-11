import { JSONParser } from '@streamparser/json';
import type { StreamCsrGraph } from './analysisGraph';

export interface RecordedNode { inventoryId: string; quantity: string; cost: string | null }
export interface PeriodSnapshot {
  graph: { nodes: RecordedNode[]; offset: number[]; successors: number[] };
  start: string; end: string; diagnostics: string[];
  records: { issues: IssueRecord[]; sales: { sourceId: string; date: string; productId: string; quantity: string; cost: string }[] };
}
export interface IssueRecord {
  lineId: string; date: string; documentId: string; productionId: string; productId: string;
  materialId: string | null; quantity: string | null; amount: string | null;
  outputQuantity: string | null; outputCost: string | null;
}
export interface Occurrence {
  index: number; occurrenceId: string; materialId: string; productionId: string; unit: string | null;
  quantity: string | null; amount: string | null; totalCost: string | null; knownTotalCost: string | null;
  sourceProductionId: string | null; sourceShare: string | null; issueLineIds: string[]; statuses: string[];
}
export interface ProductionTree {
  namespace: string; productionId: string; productId: string; scope: string; rootIndex: number;
  nodes: Occurrence[]; edges: { materialIndex: number; receivingIndex: number; laborCost: string | null; overheadCost: string | null; processingAllocationIds: string[] }[];
  rowOffsets: number[]; colIndices: number[]; sourceIssues: IssueRecord[]; statuses: string[];
  directComplete: boolean; lineageComplete: boolean; costComplete: boolean;
  outputQuantity: string | null; recordedOutputCost: string | null; recordedMaterialAmount: string | null;
  calculatedTotalCost: string | null; laborCost: string | null; overheadCost: string | null;
}
export interface FgwStart {
  correspondence: number[]; costs: { material: number; structure: number; fgw: number };
  status: string; iterations: number; finalGap: number; diagnostic: string;
  marginals: { row: number; column: number; minimum: number; finite: boolean };
  trace: { updates: number; gap: number; costs: { fgw: number } }[];
}
export interface FgwResult {
  rows: number; columns: number; bestStart: number; p: number[]; q: number[]; starts: FgwStart[];
  totalMilliseconds: number; execution: string;
  left: { nodes: { occurrenceId: string; materialId: string }[] };
  right: { nodes: { occurrenceId: string; materialId: string }[] };
}
export interface PairResult {
  namespace: string; productId: string; leftProductionId: string; rightProductionId: string;
  disposition: string; diagnostic: string | null;
  comparison: { statuses: string[]; missingVectors: string[]; embeddingVersions: Record<string, string>; result: FgwResult | null } | null;
}
export interface ExecutionReport {
  stage: string; requested: string; actual: string | null; device: string | null;
  fallbackReason: string | null; status: string;
}
export interface AnalysisOutcome {
  analysisId: string; status: string; actual: PeriodSnapshot; comparable: PeriodSnapshot;
  branches: { positions: number[]; edges: { fromPosition: number; toPosition: number; edgeIndex: number }[]; endReason: string; endingCostDifference: string }[];
  selection: { trees: ProductionTree[]; identities: { productionId: string; statuses: string[] }[] };
  embeddings: { manifests: { occurrenceId: string; statuses: string[]; resolution: string; signature: string | null }[]; generated: number; unresolved: number } | null;
  pairs: PairResult[]; planned: number; executed: number; rejected: number; failed: number;
  execution: ExecutionReport[]; diagnostics: string[];
}
export interface WorkflowEvent { type: string; analysisId?: string; sequence?: number; data?: unknown; message?: string }
export interface AccelerationPreference { useGpu: boolean; isNvidia: boolean | null; deviceId: string | null; allowCpuFallback: boolean }
export const DEFAULT_ACCELERATION: AccelerationPreference = { useGpu: false, isNvidia: null, deviceId: null, allowCpuFallback: false };
export function accelerationPreference(): AccelerationPreference {
  try {
    const value = JSON.parse(localStorage.getItem('margintrace.acceleration') || 'null') as AccelerationPreference | null;
    return value && typeof value.useGpu === 'boolean' && (!value.useGpu || typeof value.isNvidia === 'boolean') ? value : DEFAULT_ACCELERATION;
  } catch { return DEFAULT_ACCELERATION; }
}

/** Preserve exact recorded decimal text in financial evidence. */
export const recorded = (value: string | null | undefined) => value == null ? 'Unavailable' : value;

/** UI adapter only: the original decimal records remain in the outcome/evidence tables. */
export function canvasGraph(snapshot: PeriodSnapshot, analysisId: string, role: string): StreamCsrGraph {
  return { nodes: snapshot.graph.nodes.map((node, position) => ({
    id: `${analysisId}:${role}:node:${position}`, position, inventoryId: node.inventoryId,
    quantity: Number(node.quantity), cost: node.cost == null ? null : Number(node.cost),
  })), offset: snapshot.graph.offset, successors: snapshot.graph.successors,
  edgeIds: snapshot.graph.successors.map((_, i) => `${analysisId}:${role}:edge:${i}`) };
}

/** Row denominators belong to the complete solver matrix and never change when filtering. */
export function correspondenceValue(result: FgwResult, row: number, column: number, mode: 'share' | 'mass'): number | null {
  const mass = result.starts[result.bestStart]?.correspondence[row * result.columns + column];
  if (mass == null || !Number.isFinite(mass)) return null;
  return mode === 'mass' ? mass : result.p[row] > 0 ? mass / result.p[row] : null;
}

/**
 * Parse UTF-8 workflow events incrementally without constructing a complete JSON line.
 * Complete event objects retain the original evidence; the response reader is cancelled
 * on parsing or consumer failure so the backend can stop submitting further work.
 */
export async function readWorkflowStream(response: Response, onEvent: (event: WorkflowEvent) => void): Promise<void> {
  if (!response.body) throw new Error('Analysis response has no stream.');
  const reader = response.body.getReader();
  const parser = new JSONParser({ paths: ['$'], separator: '\n' });
  parser.onValue = ({ value }) => onEvent(value as unknown as WorkflowEvent);
  let finished = false;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      parser.write(value);
    }
    parser.end();
    finished = true;
  } finally {
    if (!finished) await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
