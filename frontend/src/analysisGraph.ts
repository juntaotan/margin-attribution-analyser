/** The backend's recorded node values; null cost means unavailable, not zero. */
export interface AnalysisNode {
  inventoryId: string;
  quantity: number;
  cost: number | null;
}

export interface AnalysisAdjacencyEntry {
  upstream: AnalysisNode;
  downstream: AnalysisNode[];
}

export interface AnalysisResponse {
  analysisId: string;
  results: AnalysisAdjacencyEntry[];
}

export interface AnalysisEdge {
  id: string;
  source: string;
  target: string;
}

/**
 * Creates a stable canvas ID from all fields that make up a backend Node.
 * Inventory ID alone is insufficient: a produced component and its later
 * consumption can share that ID and quantity while carrying different costs.
 */
export const nodeIdentity = (node: AnalysisNode): string =>
  JSON.stringify([node.inventoryId, node.quantity, node.cost]);

/**
 * Converts the JSON-safe adjacency entries into unique canvas nodes and edges.
 * The analyser already limits entries to traced paths; this function only adapts
 * their shape and preserves standalone nodes with no downstream connections.
 */
export const buildAnalysisGraph = (entries: AnalysisAdjacencyEntry[]) => {
  const nodesById = new Map<string, AnalysisNode>();
  const edgesById = new Map<string, AnalysisEdge>();

  // Every entry contributes its upstream node, even when downstream is empty.
  // Registering it before the children keeps source-to-target display order.
  for (const entry of entries) {
    const upstreamId = nodeIdentity(entry.upstream);
    nodesById.set(upstreamId, entry.upstream);

    // Each downstream node creates one direct edge from this upstream node.
    // Maps merge shared nodes and any repeated relation across traced paths.
    for (const child of entry.downstream) {
      const downstreamId = nodeIdentity(child);
      nodesById.set(downstreamId, child);
      const edgeId = JSON.stringify([upstreamId, downstreamId]);
      edgesById.set(edgeId, {
        id: edgeId,
        source: upstreamId,
        target: downstreamId,
      });
    }
  }

  return {
    nodes: Array.from(nodesById, ([id, node]) => ({ id, node })),
    edges: Array.from(edgesById.values()),
  };
};

/** Formats a recorded quantity without implying an unrecorded unit. */
export const formatQuantity = (quantity: number): string =>
  new Intl.NumberFormat('en-NZ', { maximumFractionDigits: 6 }).format(quantity);

/** Formats recorded cost while keeping an unavailable amount distinct from zero. */
export const formatAmount = (cost: number | null): string =>
  cost == null ? '—' : `$${cost.toFixed(2)} NZD`;
