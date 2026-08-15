package dev.margintrace.margin_attribution_backend.algorithm.attribution;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.yaml.snakeyaml.nodes.NodeId;

import lombok.RequiredArgsConstructor;

/**
 * This class is responsible for reading partitions of data from the database.
 * It uses a NamedParameterJdbcTemplate to execute SQL queries and retrieve data.
 */
@RequiredArgsConstructor
public class AttributionPartitionReader {
    private static final int PARTITION_COUNT = 8;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Map<NodeId, List<NodeId>> readPartitions(
                                                    int partition, 
                                                    long startId, 
                                                    long endId) {
        if (partition < 0 || partition >= PARTITION_COUNT) {
            throw new IllegalArgumentException("Invalid partition");
        }

        Map<NodeId, List<NodeId>> actualMaterialUsage = new HashMap<>();
        
        // TODO: Implement the logic to read partitions from the database using jdbcTemplate
        String sql = "";

        return actualMaterialUsage;
    }

    public List<Node> getAllProductsInPeriod(long startId, long endId) {
        if (startId > endId) {
            throw new IllegalArgumentException("startId must not be greater than endId");
        }

        String sql = """
                SELECT product_no, product_num
                FROM production_order
                WHERE id BETWEEN :startId AND :endId
                ORDER BY id
                """;

        Map<String, Object> parameters = Map.of(
                "startId", startId,
                "endId", endId
        );

        return jdbcTemplate.query(
                sql,
                parameters,
                (resultSet, rowNumber) -> new Node(
                        resultSet.getString("product_no"),
                        resultSet.getBigDecimal("product_num").intValueExact()
                )
        );
    }
}
