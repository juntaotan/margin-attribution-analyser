package dev.margintrace.margin_attribution_backend.algorithm.attribution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import lombok.RequiredArgsConstructor;

/**
 * This class is responsible for reading partitions of data from the database.
 * It uses a NamedParameterJdbcTemplate to execute SQL queries and retrieve data.
 */
@RequiredArgsConstructor
public class AttributionPartitionReader {
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int TASK_COUNT = 32;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * Reads material consumption data for products within a specified ID range.
     * Java splits the product IDs into batches and schedules those batches in parallel.
     *
     * @param startId The starting product ID (inclusive) for the range of products to read
     * @param endId The ending product ID (inclusive) for the range of products to read
     * @return Material nodes mapped to all corresponding product nodes
     */
    public Map<Node, List<Node>> readMaterialUsage(long startId, long endId) {
        // Read and index all products in the specified period.
        Map<String, Node> productsById = getAllProductsInPeriod(startId, endId);
        if (productsById.isEmpty()) {
            return new HashMap<>();
        }

        // Convert the product IDs to a list so they can be split into query batches.
        List<String> productIds = List.copyOf(productsById.keySet());

        // Determine the number of batches and batch size for parallel processing
        int batchCount = Math.min(TASK_COUNT, productIds.size());
        int batchSize = (productIds.size() + batchCount - 1) / batchCount;

        // Create a list of tasks to read material edges parallelly for each batch of product IDs
        List<Callable<Map<Node, List<Node>>>> tasks = new ArrayList<>(batchCount);

        // Split the product IDs into batches and create a task for each batch
        for (int fromIndex = 0; fromIndex < productIds.size(); fromIndex += batchSize) {
            // Determine the end index for the current batch
            int endIndex = Math.min(fromIndex + batchSize, productIds.size());
            // Create a sublist of product IDs for the current batch
            List<String> batch = productIds.subList(fromIndex, endIndex);
            // Add a task to read material edges for the current batch of product IDs
            tasks.add(() -> readMaterialEdges(batch, productsById));
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(THREAD_COUNT, tasks.size()));

        try {
            // Create a map to hold the actual material usage edges read from the database
            Map<Node, List<Node>> actualMaterialUsage = new HashMap<>();

            // Invoke all tasks and wait for their completion, collecting the results
            // And merge the results from each future into the actualMaterialUsage map
            List<Future<Map<Node, List<Node>>>> futures = executor.invokeAll(tasks);
            for (Future<Map<Node, List<Node>>> future : futures) {
                mergeEdges(actualMaterialUsage, future.get());
            }

            return actualMaterialUsage;

        } catch (InterruptedException exception) {

            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading material batches", exception);

        } catch (ExecutionException exception) {

            Throwable cause = exception.getCause();

            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }

            throw new IllegalStateException("Failed to read material batches", cause);

        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Reads all products produced in a given ID period.
     *
     * @param startId Product ID to start reading from (inclusive)
     * @param endId Product ID to stop reading at (inclusive)
     * @return Product nodes indexed by product ID
     * @throws IllegalArgumentException if startId is greater than endId
     */
    public Map<String, Node> getAllProductsInPeriod(long startId, long endId) {

        if (startId > endId) {
            throw new IllegalArgumentException("startId must not be greater than endId");
        }

        // SQL query to select product numbers and quantities from the production_order table
        String sql = """
                SELECT product_no, product_num
                FROM production_order
                WHERE id BETWEEN :startId AND :endId
                ORDER BY id
                """;
        Map<String, Object> parameters = Map.of("startId", startId, "endId", endId);

        // Execute the query and process the result set
        return jdbcTemplate.query(sql, parameters, resultSet -> {
            // Use a LinkedHashMap to maintain insertion order of products
            Map<String, Node> productsById = new LinkedHashMap<>();

            while (resultSet.next()) {
                // Production has no recorded cost column; null means not yet calculated.
                Node product = new Node(
                        resultSet.getString("product_no"),
                        resultSet.getBigDecimal("product_num"),
                        null
                );

                // Attempt to put the product in the map ??
                Node existing = productsById.putIfAbsent(product.inventoryId(), product);
                if (existing != null && !existing.equals(product)) {
                    throw new IllegalStateException(
                            "Product %s has more than one quantity in the requested period"
                                    .formatted(product.inventoryId())
                    );
                }
            }
            return productsById;
        });
    }

    /**
     * Reads material consumption edges for one batch of product IDs.
     *
     * @param productIds List of product IDs to read material consumption for
     * @param productsById Map of product nodes indexed by product ID
     * @return Material nodes mapped to their corresponding product-node lists
     */
    private Map<Node, List<Node>> readMaterialEdges(List<String> productIds,
                                                    Map<String, Node> productsById
    ) {
        Map<Node, List<Node>> edges = new HashMap<>();

        // Query all material consumption rows for this Java-managed product batch.
        String sql = """
                SELECT material_no, material_num, material_total_cost, product_no
                FROM material_consumption
                WHERE product_no IN (:productIds)
                ORDER BY id
                """;
        Map<String, Object> parameters = Map.of("productIds", productIds);

        // Execute the query and process the result set to build the edges map
        jdbcTemplate.query(sql, parameters, resultSet -> {
            Node material = new Node(resultSet.getString("material_no"),
                                     resultSet.getBigDecimal("material_num"),
                                     resultSet.getBigDecimal("material_total_cost")
            );
            Node product = productsById.get(resultSet.getString("product_no"));
            if (product == null) {
                throw new IllegalStateException("Material consumption references an unknown product");
            }

            // Add the material-product edge to the edges map.
            edges.computeIfAbsent(material, ignored -> new ArrayList<>()).add(product);
        });
        return edges;
    }

    private void mergeEdges(
            Map<Node, List<Node>> target,
            Map<Node, List<Node>> source
    ) {
        source.forEach((material, products) ->
                target.computeIfAbsent(material, ignored -> new ArrayList<>()).addAll(products)
        );
    }
}
