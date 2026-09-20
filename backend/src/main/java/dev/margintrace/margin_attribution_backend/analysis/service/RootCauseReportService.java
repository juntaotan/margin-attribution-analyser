package dev.margintrace.margin_attribution_backend.analysis.service;

import dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder.AttributionWorkflow;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.analysis.dto.RootCauseReportRequest;
import dev.margintrace.margin_attribution_backend.analysis.dto.RootCauseReportResponse;
import dev.margintrace.margin_attribution_backend.warehouse.model.BillOfMaterial;
import dev.margintrace.margin_attribution_backend.warehouse.model.Production;
import dev.margintrace.margin_attribution_backend.warehouse.repository.BillOfMaterialRepository;
import dev.margintrace.margin_attribution_backend.warehouse.repository.ProductionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Classifies recorded differences before any model is asked to describe them. */
@Service
@RequiredArgsConstructor
public class RootCauseReportService {
    private final AttributionWorkflow workflow;
    private final ProductionRepository productionRepository;
    private final BillOfMaterialRepository bomRepository;
    private final LocalLlamaClient llamaClient;

    public RootCauseReportResponse report(RootCauseReportRequest request) {
        validate(request);
        CsrGraph actual = workflow.trace(request.actualStartDate(), request.actualEndDate());
        CsrGraph comparable = workflow.trace(request.comparableStartDate(), request.comparableEndDate());
        Diagnosis diagnosis = diagnose(actual, comparable, request);
        LocalLlamaClient.Result ai = llamaClient.explain(diagnosis.label(), diagnosis.evidence());
        return new RootCauseReportResponse(request.inventoryId(), diagnosis.category(), diagnosis.label(),
                diagnosis.certainty(), diagnosis.evidence(), ai.summary(), ai.generated(), ai.message());
    }

    private void validate(RootCauseReportRequest request) {
        if (request == null || request.inventoryId() == null || request.inventoryId().isBlank()
                || request.inventoryId().length() > 100) {
            throw new IllegalArgumentException("Select a valid inventory node");
        }
        if (request.actualStartDate() == null || request.actualEndDate() == null
                || request.comparableStartDate() == null || request.comparableEndDate() == null
                || request.actualStartDate().isAfter(request.actualEndDate())
                || request.comparableStartDate().isAfter(request.comparableEndDate())) {
            throw new IllegalArgumentException("Both periods require ordered dates");
        }
    }

    private Diagnosis diagnose(CsrGraph actual, CsrGraph comparable, RootCauseReportRequest request) {
        String id = request.inventoryId();
        Map<String, Integer> actualPositions = positions(actual);
        Map<String, Integer> comparablePositions = positions(comparable);
        Integer actualPosition = actualPositions.get(id);
        Integer comparablePosition = comparablePositions.get(id);
        if (actualPosition == null && comparablePosition == null) {
            throw new IllegalArgumentException("Inventory node is absent from both selected periods: " + id);
        }

        List<String> evidence = new ArrayList<>();
        Node actualNode = actualPosition == null ? null : actual.nodes()[actualPosition];
        Node comparableNode = comparablePosition == null ? null : comparable.nodes()[comparablePosition];
        if (actualNode != null && comparableNode != null) {
            evidence.add("Material " + id + ": current quantity " + actualNode.quantity().toPlainString()
                    + ", comparison quantity " + comparableNode.quantity().toPlainString() + ".");
            if (actualNode.cost() != null && comparableNode.cost() != null) {
                evidence.add("Current cost " + actualNode.cost().toPlainString() + ", comparison cost "
                        + comparableNode.cost().toPlainString() + "; difference "
                        + actualNode.cost().subtract(comparableNode.cost()).toPlainString() + ".");
            }
        } else {
            evidence.add("Material " + id + (actualNode == null
                    ? " appears only in the comparison period." : " appears only in the current period."));
        }

        Set<String> actualParents = parents(actual, actualPosition);
        Set<String> comparableParents = parents(comparable, comparablePosition);
        Set<String> allParents = new HashSet<>(actualParents);
        allParents.addAll(comparableParents);
        boolean bomChanged = false;
        boolean possibleReplacement = false;
        boolean topologyChanged = false;
        boolean overuse = false;
        boolean increasedUse = false;
        for (String parent : allParents) {
            Set<String> actualMaterials = materialsFor(actual, parent, actualPositions);
            Set<String> comparableMaterials = materialsFor(comparable, parent, comparablePositions);
            Set<String> added = new HashSet<>(actualMaterials);
            added.removeAll(comparableMaterials);
            Set<String> removed = new HashSet<>(comparableMaterials);
            removed.removeAll(actualMaterials);
            if (!added.isEmpty() || !removed.isEmpty()) {
                topologyChanged = true;
                evidence.add("Actual material mix for product " + parent + ": added " + added
                        + ", removed " + removed + ".");
                possibleReplacement |= !added.isEmpty() && !removed.isEmpty();
            }

            BomDefinition currentBom = bomFor(parent, request.actualStartDate(), request.actualEndDate());
            BomDefinition previousBom = bomFor(parent, request.comparableStartDate(), request.comparableEndDate());
            if (currentBom.numbers().size() == 1 && previousBom.numbers().size() == 1
                    && currentBom.hasLines() && previousBom.hasLines()
                    && !currentBom.lines().equals(previousBom.lines())) {
                bomChanged = true;
                evidence.add("BOM material lines differ for product " + parent + ": current "
                        + currentBom.numbers() + ", comparison " + previousBom.numbers() + ".");
            } else if (!currentBom.numbers().equals(previousBom.numbers())
                    && !currentBom.numbers().isEmpty() && !previousBom.numbers().isEmpty()) {
                evidence.add("Production orders for product " + parent
                        + " reference different BOM numbers, but the available line details do not confirm a BOM content change.");
            }

            if (actualNode != null && comparableNode != null
                    && actualParents.size() == 1 && comparableParents.size() == 1
                    && actualParents.contains(parent) && comparableParents.contains(parent)) {
                Node actualOutput = actual.nodes()[actualPositions.get(parent)];
                Node comparableOutput = comparable.nodes()[comparablePositions.get(parent)];
                BigDecimal currentOutput = currentBom.outputQuantity().signum() > 0
                        ? currentBom.outputQuantity() : actualOutput.quantity();
                BigDecimal previousOutput = previousBom.outputQuantity().signum() > 0
                        ? previousBom.outputQuantity() : comparableOutput.quantity();
                if (currentOutput.signum() > 0 && previousOutput.signum() > 0) {
                    BigDecimal currentRate = actualNode.quantity().divide(currentOutput, MathContext.DECIMAL64);
                    BigDecimal previousRate = comparableNode.quantity().divide(previousOutput, MathContext.DECIMAL64);
                    if (currentRate.compareTo(previousRate) > 0) {
                        increasedUse = true;
                        evidence.add("Material " + id + " used per unit of product " + parent + ": current "
                                + currentRate.stripTrailingZeros().toPlainString() + ", comparison "
                                + previousRate.stripTrailingZeros().toPlainString() + ".");
                    }
                    BigDecimal plannedRate = currentBom.lines().get(id);
                    if (currentBom.numbers().size() == 1 && plannedRate != null
                            && currentRate.compareTo(plannedRate) > 0) {
                        overuse = true;
                        evidence.add("BOM planned usage per unit of product " + parent + " is "
                                + plannedRate.toPlainString() + "; current actual usage is "
                                + currentRate.stripTrailingZeros().toPlainString() + ", above plan.");
                    }
                }
            }
        }
        if (actualParents.size() > 1 || comparableParents.size() > 1) {
            evidence.add("This material feeds multiple products. Its graph quantity is aggregated, so per-product overuse cannot be determined.");
        }

        if (bomChanged) return new Diagnosis("BOM_CHANGE", "BOM change", "Confirmed by records", evidence);
        if (possibleReplacement) return new Diagnosis("MATERIAL_REPLACEMENT", "Suspected material substitution", "Needs verification", evidence);
        if (overuse) return new Diagnosis("OVERUSE", "Material usage exceeds BOM plan", "Confirmed by records", evidence);
        if (topologyChanged) return new Diagnosis("STRUCTURE_CHANGE", "Actual material mix changed", "Confirmed by records", evidence);
        if (increasedUse) return new Diagnosis("USAGE_INCREASE", "Material usage per unit increased", "Confirmed by records", evidence);
        if (actualNode != null && comparableNode != null && actualNode.cost() != null
                && comparableNode.cost() != null && actualNode.quantity().signum() > 0
                && comparableNode.quantity().signum() > 0) {
            BigDecimal currentPrice = actualNode.cost().divide(actualNode.quantity(), MathContext.DECIMAL64);
            BigDecimal previousPrice = comparableNode.cost().divide(comparableNode.quantity(), MathContext.DECIMAL64);
            if (currentPrice.compareTo(previousPrice) != 0) {
                evidence.add("Unit cost: current " + currentPrice.stripTrailingZeros().toPlainString()
                        + ", comparison " + previousPrice.stripTrailingZeros().toPlainString() + ".");
                return new Diagnosis("UNIT_COST_CHANGE", "Unit cost change", "Confirmed by records", evidence);
            }
        }
        return new Diagnosis("UNDETERMINED", "Insufficient data to identify a cause", "Needs verification", evidence);
    }

    private Map<String, Integer> positions(CsrGraph graph) {
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < graph.nodes().length; i++) positions.put(graph.nodes()[i].inventoryId(), i);
        return positions;
    }

    private Set<String> parents(CsrGraph graph, Integer position) {
        Set<String> result = new HashSet<>();
        if (position != null) {
            for (int i = graph.offset()[position]; i < graph.offset()[position + 1]; i++) {
                result.add(graph.nodes()[graph.successors()[i]].inventoryId());
            }
        }
        return result;
    }

    private Set<String> materialsFor(CsrGraph graph, String parent, Map<String, Integer> positions) {
        Set<String> materials = new HashSet<>();
        Integer parentPosition = positions.get(parent);
        if (parentPosition == null) return materials;
        for (int i = 0; i < graph.nodes().length; i++) {
            for (int edge = graph.offset()[i]; edge < graph.offset()[i + 1]; edge++) {
                if (graph.successors()[edge] == parentPosition) materials.add(graph.nodes()[i].inventoryId());
            }
        }
        return materials;
    }

    private BomDefinition bomFor(String product, LocalDate start, LocalDate end) {
        Set<String> numbers = new HashSet<>();
        Map<String, BigDecimal> lines = new HashMap<>();
        BigDecimal outputQuantity = BigDecimal.ZERO;
        for (Production order : productionRepository.findAllByProductNoAndDateBetweenOrderByDateAscIdAsc(
                product, start, end)) {
            outputQuantity = outputQuantity.add(order.getCompletedQuantity());
            if (numbers.add(order.getBomNo())) {
                for (BillOfMaterial line : bomRepository.findAllByBomNoAndProductNo(order.getBomNo(), product)) {
                    lines.put(line.getMaterialNo(), line.getMaterialUsage());
                }
            }
        }
        return new BomDefinition(numbers, lines, outputQuantity);
    }

    private record BomDefinition(Set<String> numbers, Map<String, BigDecimal> lines, BigDecimal outputQuantity) {
        boolean hasLines() { return !lines.isEmpty(); }
    }
    private record Diagnosis(String category, String label, String certainty, List<String> evidence) { }
}
