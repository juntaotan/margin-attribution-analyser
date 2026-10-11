package dev.margintrace.margin_attribution_backend.analysis.dto;

import dev.margintrace.margin_attribution_backend.algorithm.workflow.AnalysisOutcome;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import java.math.BigDecimal;

/** Decimal-safe JSON for the new workflow API; existing endpoint serialisation is unchanged. */
public final class AnalysisRunResponse {
    private static final ObjectMapper MAPPER = JsonMapper.builder().addModule(new SimpleModule().addSerializer(
            BigDecimal.class, new ValueSerializer<BigDecimal>() {
                @Override public void serialize(BigDecimal value, JsonGenerator generator, SerializationContext context) {
                    generator.writeString(value.toPlainString());
                }
            })).build();
    private AnalysisRunResponse() {}
    public static JsonNode json(AnalysisOutcome outcome) { return MAPPER.valueToTree(outcome); }
}
