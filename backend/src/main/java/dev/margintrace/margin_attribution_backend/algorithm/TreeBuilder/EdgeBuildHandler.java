package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.warehouse.model.InventoryUsage;
import dev.margintrace.margin_attribution_backend.warehouse.model.Production;
import dev.margintrace.margin_attribution_backend.warehouse.repository.InventoryUsageRepository;
import dev.margintrace.margin_attribution_backend.warehouse.repository.ProductionRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/** Expands sold products through material usage and records graph edges. */
@Component
public class EdgeBuildHandler extends AbstractTraceHandler {
    private final ProductionRepository productionRepository;
    private final InventoryUsageRepository inventoryUsageRepository;

    public EdgeBuildHandler(
            ProductionRepository productionRepository,
            InventoryUsageRepository inventoryUsageRepository,
            CsrGraphHandler next
    ) {
        super(next);
        this.productionRepository = productionRepository;
        this.inventoryUsageRepository = inventoryUsageRepository;
    }

    @Override
    protected void execute(TraceContext context) {
        buildDependencies(context);
    }

    private void buildDependencies(TraceContext context) {
        Queue<String> pending = new ArrayDeque<>(context.targets);
        Set<String> queued = new HashSet<>(context.targets);

        while (!pending.isEmpty()) {
            String productId = pending.remove();
            List<Production> productions = productionRepository
                    .findAllByProductNoAndDateBetweenOrderByDateAscIdAsc(
                            productId, context.startDate, context.endDate);

            if (productions.isEmpty() && context.targets.contains(productId)) {
                throw new IllegalArgumentException("Target not found in period: " + productId);
            }

            Set<String> productionOrders = new HashSet<>();
            for (Production production : productions) {
                Node product = new Node(production.getProductNo(), production.getCompletedQuantity());
                context.nodes.putIfAbsent(productId, product);
                context.edges.computeIfAbsent(productId, ignored -> new LinkedHashSet<>());
                productionOrders.add(production.getProductionOrderNo());
            }

            if (productions.isEmpty()) {
                continue;
            }
            List<InventoryUsage> usages = inventoryUsageRepository
                    .findAllByProductNoAndDateBetweenAndMaterialNoIsNotNullOrderByDateAscIdAsc(
                            productId, context.startDate, context.endDate);
            for (InventoryUsage usage : usages) {
                if (!productionOrders.contains(usage.getOrderNo())) {
                    continue;
                }
                String materialId = usage.getMaterialNo();
                if (materialId.equals(productId)) {
                    // TODO: Define how self-consumption should be represented in the graph.
                    continue;
                }

                Node material = new Node(materialId, usage.getMaterialNum(), usage.getMaterialTotalCost());
                Node consumed = context.consumedNodes.merge(materialId, material,
                        (previous, current) -> new Node(materialId,
                                previous.quantity().add(current.quantity()),
                                previous.cost().add(current.cost())));
                context.nodes.put(materialId, consumed);
                context.edges.computeIfAbsent(materialId, ignored -> new LinkedHashSet<>()).add(productId);
                if (queued.add(materialId)) {
                    pending.add(materialId);
                }
            }
        }
    }
}
