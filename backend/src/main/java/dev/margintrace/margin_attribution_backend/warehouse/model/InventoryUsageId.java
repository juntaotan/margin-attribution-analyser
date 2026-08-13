package dev.margintrace.margin_attribution_backend.warehouse.model;

import java.io.Serializable;

public record InventoryUsageId(Long id, String productNo) implements Serializable {
}
