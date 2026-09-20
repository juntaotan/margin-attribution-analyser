package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.warehouse.model.InventoryUsage;
import dev.margintrace.margin_attribution_backend.warehouse.model.Production;
import dev.margintrace.margin_attribution_backend.warehouse.model.SalesOrderLine;
import dev.margintrace.margin_attribution_backend.warehouse.repository.InventoryUsageRepository;
import dev.margintrace.margin_attribution_backend.warehouse.repository.ProductionRepository;
import dev.margintrace.margin_attribution_backend.warehouse.repository.SalesOrderLineRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class AttributionWorkflowTests {
    private static final LocalDate DATE = LocalDate.of(2026, 1, 15);

    @Test
    void buildsTheRequestedRelationshipsAndCsrArrays() {
        SalesOrderLineRepository sales = repository(SalesOrderLineRepository.class,
                "findAllByDateBetweenOrderByDateAscIdAsc",
                arguments -> List.of(sale("A001"), sale("A002")));

        Map<String, List<Production>> productions = new HashMap<>();
        for (String id : List.of("A001", "A002", "B001", "B002", "B003", "B004")) {
            productions.put(id, List.of(Production.of("PO-" + id, DATE, id,
                    BigDecimal.ONE, "Production", "BOM-" + id)));
        }
        ProductionRepository production = repository(ProductionRepository.class,
                "findAllByProductNoAndDateBetweenOrderByDateAscIdAsc",
                arguments -> productions.getOrDefault(arguments[0], List.of()));

        // Parent-child relationships supplied for this topology.
        Map<String, List<InventoryUsage>> usages = Map.of(
                "A001", List.of(line("A001", "B001"), line("A001", "B002")),
                "B001", List.of(line("B001", "C001"), line("B001", "C002")),
                "A002", List.of(line("A002", "B003"), line("A002", "B004")),
                "B003", List.of(line("B003", "C003")),
                "B004", List.of(line("B004", "C003"), line("B004", "C004")));
        InventoryUsageRepository usage = repository(InventoryUsageRepository.class,
                "findAllByProductNoAndDateBetweenAndMaterialNoIsNotNullOrderByDateAscIdAsc",
                arguments -> usages.getOrDefault(arguments[0], List.of()));

        AttributionWorkflow workflow = new AttributionWorkflow(new ReadDataHandler(sales,
                new EdgeBuildHandler(production, usage, new CsrGraphHandler())));
        CsrGraph graph = workflow.trace(DATE, DATE);

        List<String> productRelationships = new ArrayList<>();
        for (int source = 0; source < graph.nodes().length; source++) {
            for (int edge = graph.offset()[source]; edge < graph.offset()[source + 1]; edge++) {
                String child = graph.nodes()[source].inventoryId();
                String parent = graph.nodes()[graph.successors()[edge]].inventoryId();
                assertThat(child).isNotEqualTo(parent);
                productRelationships.add(parent + "-" + child);
            }
        }
        assertThat(productRelationships).containsExactlyInAnyOrder(
                "A001-B001", "B001-C001", "B001-C002", "A001-B002",
                "A002-B003", "A002-B004", "B003-C003", "B004-C003", "B004-C004");

        assertThat(graph.nodes()).containsExactly(
                produced("A001"), produced("A002"),
                consumed("B001"), consumed("B002"),
                consumed("B003"), consumed("B004"),
                consumed("C001"), consumed("C002"),
                new Node("C003", BigDecimal.valueOf(2), BigDecimal.valueOf(20)), consumed("C004"));
        assertThat(graph.offset()).containsExactly(
                0, 0, 0, 1, 2, 3, 4, 5, 6, 8, 9);
        assertThat(graph.successors()).containsExactly(0, 0, 1, 1, 2, 2, 4, 5, 5);

        System.out.println("offset = " + Arrays.toString(graph.offset()));
        System.out.println("successors = " + Arrays.toString(graph.successors()));
        System.out.println("edges (upstream -> downstream):");
        for (int source = 0; source < graph.nodes().length; source++) {
            for (int edge = graph.offset()[source]; edge < graph.offset()[source + 1]; edge++) {
                int downstream = graph.successors()[edge];
                System.out.println(source + ": " + graph.nodes()[source]
                        + " -> " + downstream + ": " + graph.nodes()[downstream]);
            }
        }
    }

    private static <T> T repository(Class<T> type, String queryMethod, Function<Object[], Object> answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> {
                    if (!method.getName().equals(queryMethod)) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return answer.apply(arguments);
                }));
    }

    private static SalesOrderLine sale(String id) {
        return SalesOrderLine.of("SO-" + id, DATE, "MOVE-" + id,
                id, BigDecimal.ONE, BigDecimal.ONE);
    }

    private static InventoryUsage line(String parent, String child) {
        return new InventoryUsage() {
            @Override public String getOrderNo() { return "PO-" + parent; }
            @Override public String getMaterialNo() { return child; }
            @Override public BigDecimal getMaterialNum() { return BigDecimal.ONE; }
            @Override public BigDecimal getMaterialTotalCost() { return BigDecimal.TEN; }
        };
    }

    private static Node produced(String id) {
        return new Node(id, BigDecimal.ONE);
    }

    private static Node consumed(String id) {
        return new Node(id, BigDecimal.ONE, BigDecimal.TEN);
    }
}
