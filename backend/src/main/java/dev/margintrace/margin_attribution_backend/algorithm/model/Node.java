package dev.margintrace.margin_attribution_backend.algorithm.model;

import java.math.BigDecimal;
import java.util.Objects;

public record Node (
    String inventoryId,
    BigDecimal quantity
) {
    public Node {
        Objects.requireNonNull(inventoryId, "inventoryId must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        quantity = quantity.stripTrailingZeros();
    }
}
