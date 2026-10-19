package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree.Issue;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.warehouse.repository.InventoryUsageRepository;
import dev.margintrace.margin_attribution_backend.warehouse.repository.SalesDetailRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.BiFunction;
import org.springframework.stereotype.Component;

/** Expands sold products through material usage and records graph edges. */
@Component
public class EdgeBuildHandler extends AbstractTraceHandler {
    public static final String COST_OF_GOODS_SOLD_NODE_ID = "__COGS__";

    private final IssueLoader issues;

    @FunctionalInterface
    public interface IssueLoader {
        List<Issue> load(String product, LocalDate start, LocalDate end);
    }

    private final BiFunction<LocalDate, LocalDate, BigDecimal> salesCost;

    @org.springframework.beans.factory.annotation.Autowired
    public EdgeBuildHandler(
            InventoryUsageRepository inventoryUsageRepository,
            SalesDetailRepository salesDetailRepository,
            CsrGraphHandler next) {
        this(
                (product, start, end) ->
                        inventoryUsageRepository
                                .findAllByProductNoAndDateBetweenOrderByDateAscIdAsc(product, start, end)
                                .stream()
                                .map(r -> new Issue(
                                        r.getId() + "@" + r.getDate(),
                                        r.getDate(),
                                        r.getMovementNo(),
                                        r.getOrderNo(),
                                        r.getProductNo(),
                                        r.getMaterialNo(),
                                        r.getMaterialNum(),
                                        r.getMaterialTotalCost(),
                                        r.getProductNum(),
                                        r.getProductTotalCost()))
                                .toList(),
                salesDetailRepository::sumTotalCostBetween,
                next);
    }

    /** Original-record loader for reusable, database-free construction. */
    public EdgeBuildHandler(
            IssueLoader issues, BiFunction<LocalDate, LocalDate, BigDecimal> salesCost, CsrGraphHandler next) {
        super(next);
        this.issues = issues;
        this.salesCost = salesCost;
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

        BigDecimal totalCost = salesCost.apply(context.startDate, context.endDate);
        if (totalCost == null) {
            totalCost = BigDecimal.ZERO;
        }

        Node costOfGoodsSold = new Node(COST_OF_GOODS_SOLD_NODE_ID, BigDecimal.ONE, totalCost);
        context.nodes.put(COST_OF_GOODS_SOLD_NODE_ID, costOfGoodsSold);
        context.edges.put(COST_OF_GOODS_SOLD_NODE_ID, new LinkedHashSet<>());

        for (String soldProductId : context.targets) {
            context.edges
                    .computeIfAbsent(soldProductId, ignored -> new LinkedHashSet<>())
                    .add(COST_OF_GOODS_SOLD_NODE_ID);
        }
    }

    private void buildDependencies(TraceContext context) {
        Queue<String> pending = new ArrayDeque<>(context.targets);
        Set<String> queued = new HashSet<>(context.targets);

        while (!pending.isEmpty()) {
            String productId = pending.remove();
            List<Issue> usages = issues.load(productId, context.startDate, context.endDate);
            Map<String, BigDecimal> outputByOrder = new HashMap<>();
            for (Issue usage : usages) {
                if (usage.outputQuantity() != null) {
                    outputByOrder.putIfAbsent(usage.productionId(), usage.outputQuantity());
                }
            }
            if (!outputByOrder.isEmpty() && !context.consumedNodes.containsKey(productId)) {
                BigDecimal output = outputByOrder.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                context.nodes.put(productId, new Node(productId, output));
            } else {
                context.nodes.putIfAbsent(productId, new Node(productId, BigDecimal.ONE));
            }
            context.edges.computeIfAbsent(productId, ignored -> new LinkedHashSet<>());
            for (Issue usage : usages) {
                if (usage.materialId() == null) {
                    continue;
                }
                String materialId = usage.materialId();
                if (materialId.equals(productId)) {
                    // TODO: Define how self-consumption should be represented in the graph.
                    continue;
                }

                Node material = new Node(materialId, usage.quantity(), usage.amount());
                Node consumed = context.consumedNodes.merge(
                        materialId,
                        material,
                        (previous, current) -> new Node(
                                materialId,
                                previous.quantity().add(current.quantity()),
                                previous.cost() == null || current.cost() == null
                                        ? null
                                        : previous.cost().add(current.cost())));
                context.nodes.put(materialId, consumed);
                context.edges
                        .computeIfAbsent(materialId, ignored -> new LinkedHashSet<>())
                        .add(productId);
                if (queued.add(materialId)) {
                    pending.add(materialId);
                }
            }
        }
    }
}
