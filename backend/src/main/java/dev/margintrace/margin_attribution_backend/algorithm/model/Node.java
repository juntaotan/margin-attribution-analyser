package dev.margintrace.margin_attribution_backend.algorithm.model;

import java.math.BigDecimal;
import java.util.Objects;

/** A recorded quantity and its total cost; null cost means unavailable, not zero. */
public record Node (
    String inventoryId,
    BigDecimal quantity,
    BigDecimal cost
) {
    public Node(String inventoryId, BigDecimal quantity) {
        this(inventoryId, quantity, null);
    }

    public Node {
        Objects.requireNonNull(inventoryId, "inventoryId must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        quantity = quantity.stripTrailingZeros();
        if (cost != null) {
            cost = cost.stripTrailingZeros();
        }
    }
}
