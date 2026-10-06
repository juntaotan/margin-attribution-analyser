package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.warehouse.model.SalesDetail;
import dev.margintrace.margin_attribution_backend.warehouse.repository.SalesDetailRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finds sold products in the requested period. */
@Component
public class ReadDataHandler extends AbstractTraceHandler {
    private final SalesDetailRepository salesDetailRepository;

    public ReadDataHandler(
            SalesDetailRepository salesDetailRepository,
            EdgeBuildHandler next
    ) {
        super(next);
        this.salesDetailRepository = salesDetailRepository;
    }

    @Override
    protected void execute(TraceContext context) {
        Map<String, BigDecimal> quantities = new LinkedHashMap<>();
        for (SalesDetail detail : salesDetailRepository
                .findAllByDateBetweenOrderByDateAscIdAsc(context.startDate, context.endDate)) {
            quantities.merge(detail.getProductNo(), detail.getProductQuantity(), BigDecimal::add);
        }
        quantities.forEach((productNo, quantity) -> context.nodes.put(productNo, new Node(productNo, quantity)));
        context.targets = List.copyOf(quantities.keySet());
    }
}
