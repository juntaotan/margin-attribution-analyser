package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.analysis.ai.AiErrorMapper;
import dev.margintrace.margin_attribution_backend.analysis.ai.AiUnavailableException;
import dev.margintrace.margin_attribution_backend.report.ai.BlueprintInputField;
import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprint;
import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprintAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportBlueprintServiceTests {
    @Mock private ReportBlueprintAssistant assistant;
    @Mock private AiErrorMapper errorMapper;

    @Test
    void validatesAndNormalizesStructuredBlueprint() {
        when(assistant.generate("Revenue")).thenReturn(new ReportBlueprint(
                "sales_order", "", List.of(" date is within January 2026 "),
                List.of(new BlueprintInputField(
                        "product_total_price", "Revenue", "Sales line total")),
                "SUM(product_total_price)", "Sum revenue", "CURRENCY", "Recorded sales"));
        ReportBlueprintService service = new ReportBlueprintService(assistant, errorMapper);

        ReportBlueprint result = service.generate("Revenue");

        assertThat(result.sourceTableLabel()).isEqualTo("sales_order");
        assertThat(result.format()).isEqualTo("currency");
        assertThat(result.filterConditions()).containsExactly("date is within January 2026");
    }

    @Test
    void rejectsUnknownSourceTable() {
        when(assistant.generate("Revenue")).thenReturn(new ReportBlueprint(
                "unknown_table", "Unknown", List.of(),
                List.of(new BlueprintInputField("value", "Value", "Invalid")),
                "SUM(value)", "Invalid", "number", "Invalid"));
        ReportBlueprintService service = new ReportBlueprintService(assistant, errorMapper);

        assertThatThrownBy(() -> service.generate("Revenue"))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("unknown source table");
    }
}
