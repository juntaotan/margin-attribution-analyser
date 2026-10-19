package dev.margintrace.margin_attribution_backend.analysis.service;

import dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder.AttributionWorkflow;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.analysis.ai.AiErrorMapper;
import dev.margintrace.margin_attribution_backend.analysis.ai.RootCauseAssistant;
import dev.margintrace.margin_attribution_backend.analysis.dto.RootCauseReportRequest;
import dev.margintrace.margin_attribution_backend.analysis.dto.RootCauseReportResponse;
import dev.margintrace.margin_attribution_backend.warehouse.model.BillOfMaterial;
import dev.margintrace.margin_attribution_backend.warehouse.model.InventoryUsage;
import dev.margintrace.margin_attribution_backend.warehouse.repository.BillOfMaterialRepository;
import dev.margintrace.margin_attribution_backend.warehouse.repository.InventoryUsageRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Classifies recorded differences before any model is asked to describe them. */
@Service
@RequiredArgsConstructor
public class RootCauseReportService {
    private final AttributionWorkflow workflow;
    private final InventoryUsageRepository inventoryUsageRepository;
    private final BillOfMaterialRepository bomRepository;
    private final RootCauseAssistant rootCauseAssistant;
    private final AiErrorMapper aiErrorMapper;

    public RootCauseReportResponse report(RootCauseReportRequest request) {
        Diagnosis diagnosis = diagnosis(request);
        String summary = null;
        boolean generated = false;
        String message = null;
        try {
            summary = rootCauseAssistant.summarize(diagnosis.label(), String.join("\n", diagnosis.evidence()));
            if (summary != null) summary = summary.trim();
            generated = summary != null && !summary.isBlank();
            if (!generated) message = "llama.cpp returned no usable content";
        } catch (RuntimeException exception) {
            message = aiErrorMapper.message(exception);
        }
        return new RootCauseReportResponse(
                request.inventoryId(),
                diagnosis.category(),
                diagnosis.label(),
                diagnosis.certainty(),
                diagnosis.evidence(),
                summary,
                generated,
                message);
    }

    /**
     * Classifies the selected material's recorded differences without asking a model to describe them.
     * Used by {@link #report} and by the optional anomaly investigation feature.
     *
     * @param request selected inventory node and both ordered periods
     * @return classification, evidence lines, both period nodes (null when absent) and parent products
     * @throws IllegalArgumentException if the request is invalid or the node is absent from both periods
     */
    public Diagnosis diagnosis(RootCauseReportRequest request) {
        validate(request);
        CsrGraph actual = workflow.trace(request.actualStartDate(), request.actualEndDate());
        CsrGraph comparable = workflow.trace(request.comparableStartDate(), request.comparableEndDate());
        return diagnose(actual, comparable, request);
    }

    private void validate(RootCauseReportRequest request) {
        if (request == null
                || request.inventoryId() == null
                || request.inventoryId().isBlank()
                || request.inventoryId().length() > 100) {
            throw new IllegalArgumentException("Select a valid inventory node");
        }
        if (request.actualStartDate() == null
                || request.actualEndDate() == null
                || request.comparableStartDate() == null
                || request.comparableEndDate() == null
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
            evidence.add("Material " + id + ": current quantity "
                    + actualNode.quantity().toPlainString() + ", comparison quantity "
                    + comparableNode.quantity().toPlainString() + ".");
            if (actualNode.cost() != null && comparableNode.cost() != null) {
                evidence.add("Current cost " + actualNode.cost().toPlainString() + ", comparison cost "
                        + comparableNode.cost().toPlainString() + "; difference "
                        + actualNode.cost().subtract(comparableNode.cost()).toPlainString() + ".");
            }
        } else {
            evidence.add("Material " + id
                    + (actualNode == null
                            ? " appears only in the comparison period."
                            : " appears only in the current period."));
        }

        Set<String> actualParents = parents(actual, actualPosition);
        Set<String> comparableParents = parents(comparable, comparablePosition);
        Set<String> allParents = new HashSet<>(actualParents);
        allParents.addAll(comparableParents);
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
                evidence.add("Actual material mix for product " + parent + ": added " + added + ", removed " + removed
                        + ".");
                possibleReplacement |= !added.isEmpty() && !removed.isEmpty();
            }

            BomDefinition currentBom = bomFor(parent);

            if (actualNode != null
                    && comparableNode != null
                    && actualParents.size() == 1
                    && comparableParents.size() == 1
                    && actualParents.contains(parent)
                    && comparableParents.contains(parent)) {
                BigDecimal currentOutput = outputQuantity(parent, request.actualStartDate(), request.actualEndDate());
                BigDecimal previousOutput =
                        outputQuantity(parent, request.comparableStartDate(), request.comparableEndDate());
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
                    if (currentBom.numbers().size() == 1
                            && plannedRate != null
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
            evidence.add(
                    "This material feeds multiple products. Its graph quantity is aggregated, so per-product overuse cannot be determined.");
        }

        if (possibleReplacement)
            return new Diagnosis(
                    "MATERIAL_REPLACEMENT",
                    "Suspected material substitution",
                    "Needs verification",
                    evidence,
                    actualNode,
                    comparableNode,
                    allParents);
        if (overuse)
            return new Diagnosis(
                    "OVERUSE",
                    "Material usage exceeds BOM plan",
                    "Confirmed by records",
                    evidence,
                    actualNode,
                    comparableNode,
                    allParents);
        if (topologyChanged)
            return new Diagnosis(
                    "STRUCTURE_CHANGE",
                    "Actual material mix changed",
                    "Confirmed by records",
                    evidence,
                    actualNode,
                    comparableNode,
                    allParents);
        if (increasedUse)
            return new Diagnosis(
                    "USAGE_INCREASE",
                    "Material usage per unit increased",
                    "Confirmed by records",
                    evidence,
                    actualNode,
                    comparableNode,
                    allParents);
        if (actualNode != null
                && comparableNode != null
                && actualNode.cost() != null
                && comparableNode.cost() != null
                && actualNode.quantity().signum() > 0
                && comparableNode.quantity().signum() > 0) {
            BigDecimal currentPrice = actualNode.cost().divide(actualNode.quantity(), MathContext.DECIMAL64);
            BigDecimal previousPrice = comparableNode.cost().divide(comparableNode.quantity(), MathContext.DECIMAL64);
            if (currentPrice.compareTo(previousPrice) != 0) {
                evidence.add("Unit cost: current "
                        + currentPrice.stripTrailingZeros().toPlainString() + ", comparison "
                        + previousPrice.stripTrailingZeros().toPlainString() + ".");
                return new Diagnosis(
                        "UNIT_COST_CHANGE",
                        "Unit cost change",
                        "Confirmed by records",
                        evidence,
                        actualNode,
                        comparableNode,
                        allParents);
            }
        }
        return new Diagnosis(
                "UNDETERMINED",
                "Insufficient data to identify a cause",
                "Needs verification",
                evidence,
                actualNode,
                comparableNode,
                allParents);
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

    private BomDefinition bomFor(String product) {
        Set<String> numbers = new HashSet<>();
        Map<String, BigDecimal> lines = new HashMap<>();
        for (BillOfMaterial line : bomRepository.findAllByProductNoIn(Set.of(product))) {
            numbers.add(line.getBomNo());
            if (numbers.size() == 1) {
                lines.put(line.getMaterialNo(), line.getMaterialUsage());
            }
        }
        return new BomDefinition(numbers, lines);
    }

    private BigDecimal outputQuantity(String product, LocalDate start, LocalDate end) {
        Map<String, BigDecimal> byOrder = new HashMap<>();
        for (InventoryUsage usage :
                inventoryUsageRepository.findAllByProductNoAndDateBetweenOrderByDateAscIdAsc(product, start, end)) {
            if (usage.getProductNum() != null) {
                byOrder.putIfAbsent(usage.getOrderNo(), usage.getProductNum());
            }
        }
        return byOrder.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private record BomDefinition(Set<String> numbers, Map<String, BigDecimal> lines) {}
    /** Deterministic classification of one material's change between two periods. */
    public record Diagnosis(
            String category,
            String label,
            String certainty,
            List<String> evidence,
            Node actualNode,
            Node comparableNode,
            Set<String> products) {}
}
