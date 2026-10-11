package dev.margintrace.margin_attribution_backend.algorithm.port;

import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree;
import dev.margintrace.margin_attribution_backend.algorithm.model.PropagationPath;
import java.util.List;
import java.util.Set;

/** Complete-instance and terminal queries share the immutable source snapshot used for discovery. */
@FunctionalInterface
public interface ProductionDataProvider {
    Selection load(PeriodGraphProvider.Snapshot actual, PeriodGraphProvider.Snapshot comparable,
                   List<PropagationPath> branches, ProductionTree.Evidence evidence);
    record Identity(String productionId, Set<String> statuses, List<String> references) {}
    record Selection(List<ProductionTree> trees, List<Identity> identities) {}
}
