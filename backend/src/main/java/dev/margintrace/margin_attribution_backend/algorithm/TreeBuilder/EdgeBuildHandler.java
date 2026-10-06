package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.warehouse.model.InventoryUsage;
import dev.margintrace.margin_attribution_backend.warehouse.repository.SalesDetailRepository;
import dev.margintrace.margin_attribution_backend.warehouse.repository.InventoryUsageRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Queue;
import java.util.Set;

/** Expands sold products through material usage and records graph edges. */
@Component
public class EdgeBuildHandler extends AbstractTraceHandler {
    public static final String COST_OF_GOODS_SOLD_NODE_ID = "__COGS__";

    private final InventoryUsageRepository inventoryUsageRepository;
    private final SalesDetailRepository salesDetailRepository;

    public EdgeBuildHandler(
            InventoryUsageRepository inventoryUsageRepository,
            SalesDetailRepository salesDetailRepository,
            CsrGraphHandler next
    ) {
        super(next);
        this.inventoryUsageRepository = inventoryUsageRepository;
        this.salesDetailRepository = salesDetailRepository;
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

        BigDecimal totalCost = salesDetailRepository.sumTotalCostBetween(
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
            List<InventoryUsage> usages = inventoryUsageRepository
                    .findAllByProductNoAndDateBetweenOrderByDateAscIdAsc(
                            productId, context.startDate, context.endDate);
            Map<String, BigDecimal> outputByOrder = new HashMap<>();
            for (InventoryUsage usage : usages) {
                if (usage.getProductNum() != null) {
                    outputByOrder.putIfAbsent(usage.getOrderNo(), usage.getProductNum());
                }
            }
            if (!outputByOrder.isEmpty() && !context.consumedNodes.containsKey(productId)) {
                BigDecimal output = outputByOrder.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                context.nodes.put(productId, new Node(productId, output));
            } else {
                context.nodes.putIfAbsent(productId, new Node(productId, BigDecimal.ONE));
            }
            context.edges.computeIfAbsent(productId, ignored -> new LinkedHashSet<>());
            for (InventoryUsage usage : usages) {
                if (usage.getMaterialNo() == null) {
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
