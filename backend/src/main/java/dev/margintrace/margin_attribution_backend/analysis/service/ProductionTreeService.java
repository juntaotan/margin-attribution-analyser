package dev.margintrace.margin_attribution_backend.analysis.service;

import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree;
import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTreeBuilder;
import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTreeFgwAdapter;
import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionScopeTraversal;
import dev.margintrace.margin_attribution_backend.analysis.dto.*;
import dev.margintrace.margin_attribution_backend.warehouse.model.InventoryUsage;
import dev.margintrace.margin_attribution_backend.warehouse.repository.InventoryUsageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/**
 * Read-only warehouse orchestration. order_no is the confirmed receiving
 * production ID; (id,date) is the issue identity. Selection dates never limit
 * subsequent instance loading. No schema changes or external services are used.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ProductionTreeService {
    private final InventoryUsageRepository repository;
    private final ProductionTreeBuilder builder = new ProductionTreeBuilder();
    private final ProductionTreeFgwAdapter adapter = new ProductionTreeFgwAdapter();
    public ProductionTreeService(InventoryUsageRepository repository) { this.repository = repository; }

    /**
     * Inspect selected instances in a consistent transaction. Existing abnormal
     * paths are reused as selection evidence and followed through actual issue
     * relationships to products. Candidate selection is not a causal assertion.
     * Blank/absent IDs and ambiguous products return explicit identity statuses.
     * The required preceding actual graph bounds each root before terminal queries;
     * whole-instance and material queries share this read-only transaction. Results
     * retain rejected candidates and externally referenced processing allocations.
     */
    public ProductionTreeResponse inspect(ProductionTreeRequest request) {
        if (request == null) throw new IllegalArgumentException("Missing tree request");
        ProductionTreeBuilder.validateEvidence(request.evidence());
        ProductionScopeTraversal.validate(request.actualGraph(), request.evidence().namespace());
        if ((request.startDate() == null) != (request.endDate() == null) ||
                (request.startDate() != null && request.startDate().isAfter(request.endDate())))
            throw new IllegalArgumentException("Selection requires ordered start and end dates");
        Map<String, Set<String>> selected = new TreeMap<>();
        List<ProductionTreeResponse.IdentityStatus> identities = new ArrayList<>();
        if (request.productionIds() != null) for (String id : request.productionIds()) {
            if (ProductionTreeBuilder.blank(id)) identities.add(new ProductionTreeResponse.IdentityStatus(id, Set.of("MISSING_PRODUCTION_ID")));
            else selected.computeIfAbsent(id, x -> new TreeSet<>()).add("explicit-production-id");
        }
        Map<String, Set<String>> products = new TreeMap<>();
        if (request.productIds() != null) for (String product : request.productIds()) {
            if (ProductionTreeBuilder.blank(product)) throw new IllegalArgumentException("Blank selected product ID");
            products.computeIfAbsent(product, x -> new TreeSet<>()).add("explicit-product-id");
        }
        if (request.abnormalPaths() != null) for (int i = 0; i < request.abnormalPaths().size(); ++i) {
            ReconciliationPathEntry path = request.abnormalPaths().get(i);
            if (path == null) throw new IllegalArgumentException("Null abnormal path");
            for (var node : path.nodes()) products.computeIfAbsent(node.inventoryId(), x -> new TreeSet<>()).add("abnormal-path:" + i);
        }
        if (!products.isEmpty() || (selected.isEmpty() && identities.isEmpty() && request.startDate() != null)) {
            List<InventoryUsage> selectionRows = request.startDate() == null ? repository.findAll() :
                    repository.findAllByDateBetweenOrderByDateAsc(request.startDate(), request.endDate());
            // Propagate all contributing references along recorded manufacturing
            // relationships; set growth terminates even when data contains cycles.
            if (request.abnormalPaths() != null && !request.abnormalPaths().isEmpty()) {
                boolean changed;
                do {
                    changed = false;
                    for (InventoryUsage row : selectionRows) {
                        Set<String> refs = row.getMaterialNo() == null ? null : products.get(row.getMaterialNo());
                        if (refs != null && !ProductionTreeBuilder.blank(row.getProductNo()))
                            changed |= products.computeIfAbsent(row.getProductNo(), x -> new TreeSet<>()).addAll(refs);
                    }
                } while (changed);
            }
            for (InventoryUsage row : selectionRows) {
                Set<String> refs = products.get(row.getProductNo());
                if (!products.isEmpty() && refs == null) continue;
                if (ProductionTreeBuilder.blank(row.getOrderNo())) {
                    identities.add(new ProductionTreeResponse.IdentityStatus(row.getOrderNo(), Set.of("MISSING_PRODUCTION_ID")));
                } else selected.computeIfAbsent(row.getOrderNo(), x -> new TreeSet<>()).addAll(
                        refs == null ? Set.of("reporting-period") : refs);
            }
        }
        if (selected.isEmpty() && identities.isEmpty() && products.isEmpty() && request.startDate() == null)
            throw new IllegalArgumentException("Select production IDs, products, abnormal paths or a reporting period");
        Map<String, List<ProductionTree.Issue>> cache = new HashMap<>();
        java.util.function.Function<String, List<ProductionTree.Issue>> loader = id -> cache.computeIfAbsent(id,
                key -> repository.findAllByOrderNo(key).stream().map(ProductionTreeService::issue).toList());
        Map<String, List<ProductionTree.Issue>> materialCache = new HashMap<>();
        java.util.function.Function<String, List<ProductionTree.Issue>> materialLoader = material -> materialCache.computeIfAbsent(material,
                key -> repository.findAllByMaterialNo(key).stream().map(ProductionTreeService::issue).toList());
        List<ProductionTree> trees = new ArrayList<>();
        for (var selection : selected.entrySet()) {
            try { trees.add(builder.build(selection.getKey(), request.evidence(), request.actualGraph(),
                    loader, materialLoader, List.copyOf(selection.getValue()))); }
            catch (IllegalArgumentException exception) {
                if (!Set.of("MISSING_PRODUCTION_ID", "AMBIGUOUS_PRODUCTION_PRODUCT").contains(exception.getMessage())) throw exception;
                identities.add(new ProductionTreeResponse.IdentityStatus(selection.getKey(), Set.of(exception.getMessage())));
            }
        }
        return new ProductionTreeResponse(List.copyOf(trees), List.copyOf(identities),
                "productionId=inventory_usage.order_no; productId=product_no; issueLineId=(id,date)",
                "All available inventory_usage records in the connected warehouse; namespace supplied by caller; imported coverage is caller-declared",
                request);
    }

    /** Build both current trees and execute FGW synchronously in this transaction/thread. */
    public ProductionComparisonResponse compare(ProductionComparisonRequest request) {
        if (request == null) throw new IllegalArgumentException("Missing comparison request");
        return adapter.compare(inspect(request.trees()), request);
    }
    private static ProductionTree.Issue issue(InventoryUsage row) {
        return new ProductionTree.Issue(row.getId() + "@" + row.getDate(), row.getDate(), row.getMovementNo(),
                row.getOrderNo(), row.getProductNo(), row.getMaterialNo(), row.getMaterialNum(), row.getMaterialTotalCost(),
                row.getProductNum(), row.getProductTotalCost());
    }
}
