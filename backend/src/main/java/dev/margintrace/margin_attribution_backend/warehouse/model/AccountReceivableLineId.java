package dev.margintrace.margin_attribution_backend.warehouse.model;

import java.io.Serializable;
import java.time.LocalDate;

public record AccountReceivableLineId(Long id, LocalDate date) implements Serializable {
}
