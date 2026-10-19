package dev.margintrace.margin_attribution_backend.analysis.dto;

import dev.margintrace.margin_attribution_backend.algorithm.workflow.AnalysisOutcome;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/** Decimal-safe JSON for the new workflow API; existing endpoint serialisation is unchanged. */
public final class AnalysisRunResponse {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
            .addModule(new SimpleModule().addSerializer(BigDecimal.class, new ValueSerializer<BigDecimal>() {
                @Override
                public void serialize(BigDecimal value, JsonGenerator generator, SerializationContext context) {
                    generator.writeString(value.toPlainString());
                }
            }))
            .build();

    private AnalysisRunResponse() {}

    /**
     * @param outcome the workflow outcome
     * @return the outcome as a JSON tree, with {@link java.math.BigDecimal} values as plain decimal strings
     */
    public static JsonNode json(AnalysisOutcome outcome) {
        return MAPPER.valueToTree(outcome);
    }
    /**
     * Writes a decimal-safe NDJSON event incrementally, retaining all evidence.
     * The caller owns the output stream, which remains open for subsequent events.
     *
     * @param output response stream receiving JSON and a newline
     * @param value progress event to serialise
     * @throws IOException if writing or flushing the response fails
     */
    public static void writeEvent(OutputStream output, Object value) throws IOException {
        try (JsonGenerator generator = MAPPER.createGenerator(output)) {
            MAPPER.writeValue(generator, value);
        }
        output.write('\n');
        output.flush();
    }
}
