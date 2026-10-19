package dev.margintrace.margin_attribution_backend.warehouse.model;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * Composite key of a sales line: its ID and date.
 */
public record SalesDetailId(Long id, LocalDate date) implements Serializable {}
