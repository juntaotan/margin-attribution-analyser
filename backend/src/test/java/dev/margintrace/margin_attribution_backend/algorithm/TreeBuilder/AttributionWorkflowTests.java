package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.warehouse.model.SalesDetail;
import dev.margintrace.margin_attribution_backend.warehouse.model.InventoryUsage;
import dev.margintrace.margin_attribution_backend.warehouse.repository.SalesDetailRepository;
import dev.margintrace.margin_attribution_backend.warehouse.repository.InventoryUsageRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class AttributionWorkflowTests {
    private static final LocalDate DATE = LocalDate.of(2026, 1, 15);

    @Test
    void tracesCostedProductThroughInventoryUsageWithoutOrderMaster() {
        SalesDetailRepository costs = repository(SalesDetailRepository.class, method -> {
            if (method.equals("findAllByDateBetweenOrderByDateAscIdAsc")) {
                return List.of(SalesDetail.of(DATE, "C-1", "A", BigDecimal.TEN,
                        BigDecimal.valueOf(150), BigDecimal.valueOf(100)));
            }
            if (method.equals("sumTotalCostBetween")) return BigDecimal.valueOf(100);
            throw new UnsupportedOperationException(method);
        });
        Map<String, List<InventoryUsage>> usageByProduct = Map.of(
                "A", List.of(usage("A", "B", "PO-A", BigDecimal.TEN)),
                "B", List.of(usage("B", "C", "PO-B", BigDecimal.valueOf(5))));
        InventoryUsageRepository usages = repository(InventoryUsageRepository.class,
                method -> usageByProduct.getOrDefault(method, List.of()));
        AttributionWorkflow workflow = new AttributionWorkflow(new ReadDataHandler(costs,
                new EdgeBuildHandler(usages, costs, new CsrGraphHandler())));

        CsrGraph graph = workflow.trace(DATE, DATE);

        assertThat(graph.nodes()).extracting(node -> node.inventoryId())
                .containsExactly("A", "B", "C", "__COGS__");
        assertThat(graph.nodes()[0].quantity()).isEqualByComparingTo("10");
        assertThat(graph.nodes()[1].cost()).isEqualByComparingTo("10");
        assertThat(graph.successors()).hasSize(3);
    }

    private static InventoryUsage usage(String product, String material, String order, BigDecimal quantity) {
        return new InventoryUsage() {
            @Override public String getProductNo() { return product; }
            @Override public String getOrderNo() { return order; }
            @Override public String getMaterialNo() { return material; }
            @Override public BigDecimal getMaterialNum() { return quantity; }
            @Override public BigDecimal getMaterialTotalCost() { return BigDecimal.TEN; }
        };
    }

    private static <T> T repository(Class<T> type, Function<String, Object> answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> answer.apply(method.getName().equals(
                        "findAllByProductNoAndDateBetweenOrderByDateAscIdAsc")
                        ? String.valueOf(arguments[0]) : method.getName())));
    }
}
