package dev.margintrace.margin_attribution_backend.analysis.dto;

import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree;
import java.util.List;
import java.util.Set;

/** Inspectable trees plus rejected identities and the exact selection/dataset scope. */
public record ProductionTreeResponse(
        List<ProductionTree> trees,
        List<IdentityStatus> identities,
        String identifierMapping,
        String datasetScope,
        ProductionTreeRequest selection) {
    public record IdentityStatus(String productionId, Set<String> statuses) {}
}
