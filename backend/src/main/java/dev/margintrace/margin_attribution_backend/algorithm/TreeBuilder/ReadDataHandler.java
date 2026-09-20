package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import dev.margintrace.margin_attribution_backend.warehouse.model.SalesOrderLine;
import dev.margintrace.margin_attribution_backend.warehouse.repository.SalesOrderLineRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;

/** Finds the products sold in the requested period. */
@Component
public class ReadDataHandler extends AbstractTraceHandler {
    private final SalesOrderLineRepository salesRepository;

    public ReadDataHandler(
            SalesOrderLineRepository salesRepository,
            EdgeBuildHandler next
    ) {
        super(next);
        this.salesRepository = salesRepository;
    }

    @Override
    protected void execute(TraceContext context) {
        context.targets = findSoldTargets(context.startDate, context.endDate);
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
