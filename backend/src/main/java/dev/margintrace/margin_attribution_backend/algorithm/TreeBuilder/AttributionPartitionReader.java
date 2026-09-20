package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import lombok.RequiredArgsConstructor;

/**
 * This class is responsible for reading partitions of data from the database.
 * It uses a NamedParameterJdbcTemplate to execute SQL queries and retrieve data.
 */
@RequiredArgsConstructor
public class AttributionPartitionReader {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    /** Maximum number of production IDs bound in one SQL query. */
    private static final int PRODUCTION_ID_BATCH_SIZE = 500;

    /**
     * Reads the products and material edges for exactly the supplied production rows.
     * A date query can return non-contiguous IDs, so using a minimum-to-maximum ID
     * interval here would accidentally include production outside the requested dates.
     * Each material row is joined by both production order number and product number;
     * matching only the product number would also include other orders for that product.
     *
     * @param productionIds IDs selected by the inclusive production-date query
     * @return material-to-product edges, plus empty entries for products without materials
     */
    public Map<Node, List<Node>> readMaterialUsageForProductionIds(List<Long> productionIds) {
        Objects.requireNonNull(productionIds, "productionIds must not be null");
        if (productionIds.isEmpty()) {
            return Map.of();
        }

        // Preserve the first occurrence of each ID so repeated IDs do not repeat query work.
        List<Long> selectedIds = new ArrayList<>(new java.util.LinkedHashSet<>(productionIds));
        if (selectedIds.contains(null)) {
            throw new IllegalArgumentException("productionIds must not contain null");
        }

        Map<String, Node> productsByInventoryId = new LinkedHashMap<>();
        Map<Node, List<Node>> materialUsage = new LinkedHashMap<>();
        String sql = """
                SELECT p.product_no, p.product_num,
                       m.material_no, m.material_num, m.material_total_cost
                FROM production_order p
                LEFT JOIN inventory_usage m
                  ON m.order_no = p.production_order_no
                 AND m.product_no = p.product_no
                 AND m.material_no IS NOT NULL
                WHERE p.id IN (:productionIds)
                ORDER BY p.id, m.id
                """;

        // Bind IDs in bounded batches: the selected period may contain more rows than
        // a database permits in a single IN expression or prepared statement.
        for (int fromIndex = 0; fromIndex < selectedIds.size(); fromIndex += PRODUCTION_ID_BATCH_SIZE) {
            int toIndex = Math.min(fromIndex + PRODUCTION_ID_BATCH_SIZE, selectedIds.size());
            Map<String, Object> parameters = Map.of(
                    "productionIds", selectedIds.subList(fromIndex, toIndex));

            jdbcTemplate.query(sql, parameters, resultSet -> {
                Node product = new Node(
                        resultSet.getString("product_no"),
                        resultSet.getBigDecimal("product_num")
                );
                Node existing = productsByInventoryId.putIfAbsent(product.inventoryId(), product);
                if (existing != null && !existing.equals(product)) {
                    throw new IllegalStateException(
                            "Product %s has more than one quantity in the requested period"
                                    .formatted(product.inventoryId()));
                }

                // A left-joined row with no material still puts the product in the
                // graph, allowing reverse tracing to return the product itself.
                materialUsage.computeIfAbsent(product, ignored -> new ArrayList<>());
                if (resultSet.getString("material_no") == null) {
                    return;
                }

                Node material = new Node(
                        resultSet.getString("material_no"),
                        resultSet.getBigDecimal("material_num"),
                        resultSet.getBigDecimal("material_total_cost")
                );
                List<Node> downstream = materialUsage.computeIfAbsent(
                        material, ignored -> new ArrayList<>());
                // Several selected orders can record the same logical graph edge.
                // Keep it once so tracing does not emit duplicate paths.
                if (!downstream.contains(product)) {
                    downstream.add(product);
                }
            });
        }
        return materialUsage;
    }

}
