package dev.margintrace.margin_attribution_backend.warehouse.model;

import java.io.Serializable;

public record AccountPayableLineId(Long id, String materialNo) implements Serializable {
}
