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
        List<String> selectionReferences) {
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
                                String reversalOfIssueLineId) {}
    public record Receipt(String receiptId, BigDecimal quantity, BigDecimal cost) {}
    /** Quantity/cost interpretations are independent and must be confirmed explicitly. */
    public record OutputEvidence(OutputInterpretation quantityInterpretation,
                                 OutputInterpretation costInterpretation, List<Receipt> receipts,
                                 String unit, boolean comparableCostScope) {}
    /** Namespace labels one warehouse dataset; the current schema cannot filter organisations. */
    public record Evidence(String namespace, Scope scope, Set<String> completeProductionIds,
                           Map<String, IssueEvidence> issues, Map<String, OutputEvidence> outputs,
                           String currency, String materialCostScope) {}
    public record Occurrence(int index, String occurrenceId, String materialId,
                             String productionId, String receivingPosition, String unit,
                             MaterialKind kind, String sourceProductionId, String sourceShare,
                             String quantity, String amount, String sourceOutputQuantity,
                             String sourceOutputCost, List<String> issueLineIds,
                             List<String> issueDocumentIds, Set<String> statuses) {}
    public record Edge(int materialIndex, int receivingIndex, String quantity, String amount,
                       List<String> issueLineIds, String sourceProductionId, String sourceShare) {}
}
