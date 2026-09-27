package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.warehouse.model.InventoryUsage;
import dev.margintrace.margin_attribution_backend.warehouse.model.Production;
import dev.margintrace.margin_attribution_backend.warehouse.repository.CostDetailRepository;
import dev.margintrace.margin_attribution_backend.warehouse.repository.InventoryUsageRepository;
import dev.margintrace.margin_attribution_backend.warehouse.repository.ProductionRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/** Expands sold products through material usage and records graph edges. */
@Component
public class EdgeBuildHandler extends AbstractTraceHandler {
    public static final String COST_OF_GOODS_SOLD_NODE_ID = "__COGS__";

    private final ProductionRepository productionRepository;
    private final InventoryUsageRepository inventoryUsageRepository;
    private final CostDetailRepository costDetailRepository;

    public EdgeBuildHandler(
            ProductionRepository productionRepository,
            InventoryUsageRepository inventoryUsageRepository,
            CostDetailRepository costDetailRepository,
            CsrGraphHandler next
    ) {
        super(next);
        this.productionRepository = productionRepository;
        this.inventoryUsageRepository = inventoryUsageRepository;
        this.costDetailRepository = costDetailRepository;
    }

    @Override
    protected void execute(TraceContext context) {
        addCostOfGoodsSoldRoot(context);
        buildDependencies(context);
    }

    private void addCostOfGoodsSoldRoot(TraceContext context) {
        if (context.targets.isEmpty()) {
            return;
        }

        BigDecimal totalCost = costDetailRepository.sumTotalCostBetween(
                context.startDate, context.endDate);
        if (totalCost == null) {
            totalCost = BigDecimal.ZERO;
        }

        Node costOfGoodsSold = new Node(
                COST_OF_GOODS_SOLD_NODE_ID, BigDecimal.ONE, totalCost);
        context.nodes.put(COST_OF_GOODS_SOLD_NODE_ID, costOfGoodsSold);
        context.edges.put(COST_OF_GOODS_SOLD_NODE_ID, new LinkedHashSet<>());

        for (String soldProductId : context.targets) {
            context.edges.computeIfAbsent(soldProductId, ignored -> new LinkedHashSet<>())
                    .add(COST_OF_GOODS_SOLD_NODE_ID);
        }
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
