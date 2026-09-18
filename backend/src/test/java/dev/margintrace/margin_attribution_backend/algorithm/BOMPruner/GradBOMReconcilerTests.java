package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.algorithm.model.ReconciliationResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class GradBOMReconcilerTests {
    private final GradBOMReconciler reconciler = new GradBOMReconciler();

    @Test
    void mapsEachNodeToWhetherTheOtherGraphContainsTheSameInventoryId() {
        Node actualMaterial = node("MATERIAL", 10);
        Node actualProductOne = node("PRODUCT-1", 2);
        Node actualOnly = node("ACTUAL-ONLY", 3);
        CsrGraph actualGraph = new CsrGraph(
                new Node[] {actualMaterial, actualProductOne, actualOnly},
                new int[] {0, 2, 2, 2},
                new int[] {1, 2}
        );

        Node comparableOnly = node("COMPARABLE-ONLY", 3);
        Node comparableMaterial = node("MATERIAL", 99);
        Node comparableProductOne = node("PRODUCT-1", 2);
        CsrGraph comparableGraph = new CsrGraph(
                new Node[] {comparableOnly, comparableMaterial, comparableProductOne},
                new int[] {0, 0, 2, 2},
                new int[] {0, 2}
        );

        ReconciliationResult result = reconciler.gradBOMReconciler(
                actualGraph, comparableGraph, BigDecimal.ZERO.setScale(2));

        assertThat(result.actualGraphDiff())
                .containsEntry(0, 1)
                .containsEntry(1, 1)
                .containsEntry(2, 0);
        assertThat(result.comparableGraphDiff())
                .containsEntry(0, 0)
                .containsEntry(1, 1)
                .containsEntry(2, 1);
    }

    private Node node(String inventoryId, long quantity) {
        return new Node(inventoryId, BigDecimal.valueOf(quantity));
    }
}
