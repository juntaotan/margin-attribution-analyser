package dev.margintrace.margin_attribution_backend.algorithm.attribution;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.yaml.snakeyaml.nodes.NodeId;

/**
 * This class is responsible for reading partitions of data from the database.
 * It uses a NamedParameterJdbcTemplate to execute SQL queries and retrieve data.
 */
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

    public void getAllProductsInPeriod (long startId, long endId) {
        // TODO: Implement the logic to get all products in a given period

    }
}
