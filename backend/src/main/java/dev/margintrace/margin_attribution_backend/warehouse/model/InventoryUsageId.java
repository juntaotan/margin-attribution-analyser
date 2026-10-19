package dev.margintrace.margin_attribution_backend.warehouse.model;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * Composite key of an inventory movement: its ID and date.
 */
public record InventoryUsageId(Long id, LocalDate date) implements Serializable {}
