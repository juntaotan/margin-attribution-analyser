package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.warehouse.model.Production;
import dev.margintrace.margin_attribution_backend.warehouse.model.SalesOrderLine;
import dev.margintrace.margin_attribution_backend.warehouse.repository.ProductionRepository;
import dev.margintrace.margin_attribution_backend.warehouse.repository.SalesOrderLineRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Reads sold targets and the production and consumption records for the requested period. */
@Component
public class ReadDataHandler extends AbstractTraceHandler {
    private final SalesOrderLineRepository salesRepository;
    private final ProductionRepository productionRepository;
    private final AttributionPartitionReader reader;

    public ReadDataHandler(
            SalesOrderLineRepository salesRepository,
            ProductionRepository productionRepository,
            AttributionPartitionReader reader,
            CsrConversionHandler next
    ) {
        super(next);
        this.salesRepository = salesRepository;
        this.productionRepository = productionRepository;
        this.reader = reader;
    }

    @Override
    protected void execute(TraceContext context) {
        context.targets = findSoldTargets(context.startDate, context.endDate);
        if (context.targets.isEmpty()) {
            return;
        }

        List<Production> productions = productionRepository
                .findAllByDateBetweenOrderByDateAsc(context.startDate, context.endDate);
        List<Long> productionIds = new ArrayList<>(productions.size());
        for (Production production : productions) {
            productionIds.add(production.getId());
            Node produced = new Node(production.getProductNo(), production.getCompletedQuantity());
            Node existing = context.producedNodesById.putIfAbsent(produced.inventoryId(), produced);
            if (existing != null && !existing.equals(produced)) {
                throw new IllegalArgumentException(
                        "Product has multiple produced nodes in period: " + produced.inventoryId());
            }
            context.nodes.add(produced);
        }

        for (String target : context.targets) {
            if (!context.producedNodesById.containsKey(target)) {
                throw new IllegalArgumentException("Target not found in period: " + target);
            }
        }

        context.materialUsage = reader.readMaterialUsageForProductionIds(productionIds);
        for (Map.Entry<Node, List<Node>> entry : context.materialUsage.entrySet()) {
            context.nodes.add(entry.getKey());
            context.nodes.addAll(entry.getValue());
        }
    }

    private List<String> findSoldTargets(LocalDate startDate, LocalDate endDate) {
        LinkedHashSet<String> soldIds = new LinkedHashSet<>();
        for (SalesOrderLine sale : salesRepository
                .findAllByDateBetweenOrderByDateAscIdAsc(startDate, endDate)) {
            soldIds.add(sale.getProductNo());
        }
        return List.copyOf(soldIds);
    }
}
