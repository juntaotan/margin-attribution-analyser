package dev.margintrace.margin_attribution_backend.algorithm;

import dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder.AttributionPartitionReader;
import dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder.MarginAttributionAlgorithm;
import dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder.TopologicalSort;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrResult;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.warehouse.model.Production;
import dev.margintrace.margin_attribution_backend.warehouse.repository.ProductionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttributionWorkflowTests {
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 31);

    @Mock private ProductionRepository productionRepository;
    @Mock private AttributionPartitionReader reader;
    @Spy private TopologicalSort graphBuilder = new TopologicalSort();
    @Spy private MarginAttributionAlgorithm algorithm = new MarginAttributionAlgorithm();
    @InjectMocks private AttributionWorkflow workflow;

    @Test
    void tracesMultipleTargetsInOneSharedGraphUsingExactProductionIds() {
        Production component = production(11L, "COMPONENT", 5);
        Production finished = production(19L, "FINISHED", 2);
        when(productionRepository.findAllByDateBetweenOrderByDateAsc(START, END))
                .thenReturn(List.of(component, finished));

        Node material = node("MATERIAL", 8);
        Node componentNode = node("COMPONENT", 5);
        Node finishedNode = node("FINISHED", 2);
        when(reader.readMaterialUsageForProductionIds(List.of(11L, 19L)))
                .thenReturn(Map.of(
                        material, List.of(componentNode),
                        componentNode, List.of(finishedNode),
                        finishedNode, List.of()
                ));

        CsrResult result = workflow.trace(START, END, List.of("COMPONENT", "FINISHED"));

        assertThat(result.pathsByTarget().keySet()).containsExactly("COMPONENT", "FINISHED");
        assertThat(result.pathsByTarget().get("COMPONENT"))
                .isDeepEqualTo(new int[][] {{2, 0}});
        assertThat(result.pathsByTarget().get("FINISHED"))
                .isDeepEqualTo(new int[][] {{2, 0, 1}});
        verify(reader).readMaterialUsageForProductionIds(List.of(11L, 19L));
    }

    @Test
    void omittedTargetsSelectEveryPeriodProductIncludingOneWithoutMaterials() {
        Production product = production(11L, "P", 5);
        when(productionRepository.findAllByDateBetweenOrderByDateAsc(START, END))
                .thenReturn(List.of(product));
        when(reader.readMaterialUsageForProductionIds(List.of(11L)))
                .thenReturn(Map.of(node("P", 5), List.of()));

        CsrResult result = workflow.trace(START, END, null);

        assertThat(result.pathsByTarget().get("P")).isDeepEqualTo(new int[][] {{0}});
    }

    @Test
    void tracesThroughAProducedComponentAndItsCostedConsumptionNode() {
        Production component = production(11L, "COMPONENT", 50);
        Production finished = production(19L, "FINISHED", 100);
        when(productionRepository.findAllByDateBetweenOrderByDateAsc(START, END))
                .thenReturn(List.of(component, finished));

        Node steel = new Node("STEEL", BigDecimal.valueOf(150), BigDecimal.valueOf(3000));
        Node producedComponent = node("COMPONENT", 50);
        Node consumedComponent = new Node(
                "COMPONENT", BigDecimal.valueOf(50), BigDecimal.valueOf(3500));
        Node producedFinished = node("FINISHED", 100);
        when(reader.readMaterialUsageForProductionIds(List.of(11L, 19L)))
                .thenReturn(Map.of(
                        steel, List.of(producedComponent),
                        producedComponent, List.of(),
                        consumedComponent, List.of(producedFinished),
                        producedFinished, List.of()
                ));

        CsrResult result = workflow.trace(START, END, List.of("FINISHED"));
        int[] path = result.pathsByTarget().get("FINISHED")[0];
        Node[] tracedNodes = java.util.Arrays.stream(path)
                .mapToObj(index -> result.csrGraph().nodes()[index])
                .toArray(Node[]::new);

        assertThat(tracedNodes).containsExactly(
                steel, producedComponent, consumedComponent, producedFinished);
    }

    @Test
    void rejectsUnknownTargetsAfterBuildingThePeriodGraph() {
        when(productionRepository.findAllByDateBetweenOrderByDateAsc(START, END))
                .thenReturn(List.of());
        when(reader.readMaterialUsageForProductionIds(List.of())).thenReturn(Map.of());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> workflow.trace(START, END, List.of("MISSING")))
                .withMessage("Target not found in period: MISSING");
    }

    @Test
    void rejectsInvertedDatesBeforeQueryingTheRepository() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> workflow.trace(END, START, List.of()))
                .withMessage("startDate must not be after endDate");
    }

    /** Creates a persisted-looking production with the ID returned by the repository. */
    private Production production(long id, String productNo, long quantity) {
        Production production = Production.of(
                "PO-" + id, START, productNo, BigDecimal.valueOf(quantity), "Production", "BOM-1");
        ReflectionTestUtils.setField(production, "id", id);
        return production;
    }

    /** Creates the exact Node value the graph builder receives from the reader. */
    private Node node(String inventoryId, long quantity) {
        return new Node(inventoryId, BigDecimal.valueOf(quantity));
    }
}
