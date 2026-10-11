package dev.margintrace.margin_attribution_backend.algorithm.ProductionTree;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An occurrence tree and its recorded evidence. Amounts are decimal text at the
 * API boundary. Root material amount is a subtotal, never another contribution.
 * CSR edges point from consumed material to its receiving occurrence.
 */
public record ProductionTree(
        String namespace, String productionId, String instanceId, String productId,
        Scope scope, int rootIndex, List<Occurrence> nodes, List<Edge> edges, List<Issue> sourceIssues,
        int[] rowOffsets, int[] colIndices, String outputQuantity, String recordedOutputCost,
        String recordedMaterialAmount, String outputCostDifference,
        Set<String> statuses, boolean directComplete, boolean lineageComplete,
        List<String> selectionReferences, TopDownScope topDownScope, List<IssueDecision> issueDecisions,
        List<ProcessingDecision> processingDecisions, String calculatedTotalCost, String knownTotalCost,
        String laborCost, String overheadCost, boolean costComplete, String totalOutputCostDifference) {
    public enum Scope { DIRECT_CONSUMPTION, FULL_LINEAGE }
    public enum MaterialKind { RAW, INTERMEDIATE, UNKNOWN }
    public enum OutputInterpretation { UNVERIFIED, REPEATED_INSTANCE_TOTAL, RECEIPTS }

    /** Exact source identity; document numbers do not deduplicate issue rows. */
    public record Issue(String lineId, LocalDate date, String documentId, String productionId,
                        String productId, String materialId, BigDecimal quantity, BigDecimal amount,
                        BigDecimal outputQuantity, BigDecimal outputCost) {}

    /**
     * Explicit external evidence for a warehouse row. receivingIssueLineId names
     * an actual intermediate occurrence in the same instance, or null for root.
     * sourceShare is a declared fraction of homogeneous upstream output, not an
     * inferred ratio. Returns are deliberately not netted without a specified rule.
     */
    public record IssueEvidence(String unit, MaterialKind kind, String receivingIssueLineId,
                                String sourceProductionId, BigDecimal sourceShare,
                                String reversalOfIssueLineId, String graphEdgeId) {
        public IssueEvidence(String unit, MaterialKind kind, String receivingIssueLineId,
                             String sourceProductionId, BigDecimal sourceShare, String reversalOfIssueLineId) {
            this(unit, kind, receivingIssueLineId, sourceProductionId, sourceShare, reversalOfIssueLineId, null);
        }
    }
    public record Receipt(String receiptId, BigDecimal quantity, BigDecimal cost) {}
    /** Quantity/cost interpretations are independent and must be confirmed explicitly. */
    public record OutputEvidence(OutputInterpretation quantityInterpretation,
                                 OutputInterpretation costInterpretation, List<Receipt> receipts,
                                 String unit, boolean comparableCostScope, String currency, String costScope) {
        public OutputEvidence(OutputInterpretation quantityInterpretation, OutputInterpretation costInterpretation,
                              List<Receipt> receipts, String unit, boolean comparableCostScope) {
            this(quantityInterpretation, costInterpretation, receipts, unit, comparableCostScope, null, null);
        }
    }
    /** Namespace labels one warehouse dataset; the current schema cannot filter organisations. */
    public record Evidence(String namespace, Scope scope, Set<String> completeProductionIds,
                           Map<String, IssueEvidence> issues, Map<String, OutputEvidence> outputs,
                           String currency, String materialCostScope, String totalCostScope,
                           List<ProcessingSource> processingSources, List<ProcessingAllocation> processingAllocations) {
        public Evidence(String namespace, Scope scope, Set<String> completeProductionIds,
                        Map<String, IssueEvidence> issues, Map<String, OutputEvidence> outputs,
                        String currency, String materialCostScope) {
            this(namespace, scope, completeProductionIds, issues, outputs, currency, materialCostScope,
                    null, List.of(), List.of());
        }
    }
    public enum Direction { MATERIAL_TO_PRODUCT, PRODUCT_TO_MATERIAL }
    public record GraphNode(String nodeId, String productId) {}
    /**
     * Preceding-stage actual CSR. Edge IDs align with colIndices; roots map receiving
     * production IDs to stable node IDs. Coverage dates describe the source graph,
     * not instance selection. completeCoverage is an explicit import declaration.
     */
    public record ActualGraph(String graphId, String namespace, Direction direction, boolean verifiedActual,
                              List<GraphNode> nodes, int[] rowOffsets, int[] colIndices, List<String> edgeIds,
                              Map<String, String> roots, boolean completeCoverage,
                              LocalDate coverageStart, LocalDate coverageEnd) {}
    public record Relationship(String edgeId, String materialNodeId, String receivingNodeId) {}
    /** Paths contain stable node IDs in product-to-material order. */
    public record TopDownScope(String graphId, String rootNodeId, List<GraphNode> permittedNodes,
                               List<Relationship> permittedEdges, List<List<String>> paths,
                               List<String> terminalNodeIds, List<String> bottomUpNodeIds,
                               Set<String> statuses, ActualGraph sourceGraph) {}
    /** Original candidates remain in sourceIssues; each accepted projection has its own decision. */
    public record IssueDecision(String issueLineId, String graphId, String rootInstanceId,
                                String occurrenceId, String edgeId, boolean accepted, String reason,
                                String quantity, String amount) {}
    /** A referenced production-level pool; amounts are never automatically distributed. */
    public record ProcessingSource(String sourceId, String productionId, String currency,
                                   String costScope, BigDecimal labor, BigDecimal overhead) {}
    /**
     * An explicitly justified relationship allocation of a referenced pool. issueLineIds
     * must identify exactly one grouped incoming occurrence, independently of documents.
     * Allocation identity is distinct from source identity so pools can be split safely.
     */
    public record ProcessingAllocation(String allocationId, String sourceId, String productionId,
                                       String receivingIssueLineId, List<String> issueLineIds,
                                       String currency, String costScope, String allocationBasis,
                                       BigDecimal labor, BigDecimal overhead) {}
    public record ProcessingDecision(ProcessingAllocation allocation, ProcessingSource source,
                                     String graphId, String rootInstanceId, String occurrenceId,
                                     boolean accepted, String reason, String projectedLabor, String projectedOverhead) {}
    /**
     * One position/source-share occurrence. amount is its recorded receiving material
     * flow, while totalCost summarises its own inputs and processing costs when complete.
     * knownTotalCost preserves partial evidence; source totals and valuation differences
     * never become additional ancestor material contributions.
     */
    public record Occurrence(int index, String occurrenceId, String materialId,
                             String productionId, String receivingPosition, String unit,
                             MaterialKind kind, String sourceProductionId, String sourceShare,
                             String quantity, String amount, String sourceOutputQuantity,
                             String sourceOutputCost, List<String> issueLineIds,
                             List<String> issueDocumentIds, Set<String> statuses,
                             String totalCost, String knownTotalCost, String materialSubtotal,
                             String laborCost, String overheadCost, String currency, String costScope,
                             boolean costComplete, String sourceTotalCost, String projectedSourceTotalCost,
                             String sourceValuationDifference) {}
    /** Material flow and independently sourced processing costs on a permitted material-to-product edge. */
    public record Edge(int materialIndex, int receivingIndex, String quantity, String amount,
                       List<String> issueLineIds, String sourceProductionId, String sourceShare,
                       String graphEdgeId, String laborCost, String overheadCost,
                       List<String> processingAllocationIds, boolean quantityComplete,
                       boolean materialAmountComplete, boolean processingComplete) {}
}
