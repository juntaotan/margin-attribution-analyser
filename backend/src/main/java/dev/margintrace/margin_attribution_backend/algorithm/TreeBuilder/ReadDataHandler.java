package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.algorithm.port.PeriodRecords;
import dev.margintrace.margin_attribution_backend.warehouse.repository.SalesDetailRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.stereotype.Component;

/** Finds sold products in the requested period. */
@Component
public class ReadDataHandler extends AbstractTraceHandler {
    private final BiFunction<LocalDate, LocalDate, List<PeriodRecords.Sale>> sales;

    @org.springframework.beans.factory.annotation.Autowired
    public ReadDataHandler(SalesDetailRepository salesDetailRepository, EdgeBuildHandler next) {
        this(
                (start, end) -> salesDetailRepository.findAllByDateBetweenOrderByDateAscIdAsc(start, end).stream()
                        .map(s -> new PeriodRecords.Sale(
                                String.valueOf(s.getId()),
                                s.getDate(),
                                s.getProductNo(),
                                s.getProductQuantity(),
                                s.getTotalCost()))
                        .toList(),
                next);
    }

    /** Reuses the construction chain with explicitly passed source records. */
    public ReadDataHandler(BiFunction<LocalDate, LocalDate, List<PeriodRecords.Sale>> sales, EdgeBuildHandler next) {
        super(next);
        this.sales = sales;
    }

    @Override
    protected void execute(TraceContext context) {
        Map<String, BigDecimal> quantities = new LinkedHashMap<>();
        for (var detail : sales.apply(context.startDate, context.endDate)) {
            quantities.merge(detail.productId(), detail.quantity(), BigDecimal::add);
        }
        quantities.forEach((productNo, quantity) -> context.nodes.put(productNo, new Node(productNo, quantity)));
        context.targets = List.copyOf(quantities.keySet());
    }
}
