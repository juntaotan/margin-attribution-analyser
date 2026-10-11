package dev.margintrace.margin_attribution_backend.analysis.dto;

import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree.Evidence;
import java.time.LocalDate;
import java.util.List;

/**
 * Select explicit IDs, products in an optional period, or existing abnormal paths.
 * Dates select instances only; all available rows of selected IDs are then loaded.
 * Evidence confirms semantics absent from the warehouse rather than replacing values.
 */
public record ProductionTreeRequest(List<String> productionIds, List<String> productIds,
                                    LocalDate startDate, LocalDate endDate,
                                    List<ReconciliationPathEntry> abnormalPaths, Evidence evidence) {}
