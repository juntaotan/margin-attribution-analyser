package dev.margintrace.margin_attribution_backend.analysis.config;

import dev.margintrace.margin_attribution_backend.algorithm.attribution.AttributionPartitionReader;
import dev.margintrace.margin_attribution_backend.algorithm.attribution.MarginAttributionAlgorithm;
import dev.margintrace.margin_attribution_backend.algorithm.attribution.TopologicalSort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class AnalysisConfiguration {

    @Bean
    public AttributionPartitionReader attributionPartitionReader(NamedParameterJdbcTemplate jdbcTemplate) {
        return new AttributionPartitionReader(jdbcTemplate);
    }

    @Bean
    public TopologicalSort topologicalSort() {
        return new TopologicalSort();
    }

    @Bean
    public MarginAttributionAlgorithm marginAttributionAlgorithm() {
        return new MarginAttributionAlgorithm();
    }
}
