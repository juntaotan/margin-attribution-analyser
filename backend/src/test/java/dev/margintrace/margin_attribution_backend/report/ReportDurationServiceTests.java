package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.analysis.ai.AiErrorMapper;
import dev.margintrace.margin_attribution_backend.report.ai.PeriodExtraction;
import dev.margintrace.margin_attribution_backend.report.ai.ReportPeriodAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportDurationServiceTests {
    @Mock private ReportDocumentStore documentStore;
    @Mock private ReportContentControlScanner scanner;
    @Mock private ReportPeriodAssistant assistant;
    @Mock private AiErrorMapper errorMapper;

    @Test
    void mapsStructuredPeriodExtractionToExistingApiShape() throws Exception {
        byte[] document = {1, 2, 3};
        when(documentStore.loadOrCreateDefaultDocument()).thenReturn(document);
        when(scanner.contextsForControl(document, "duration", null, null))
                .thenReturn(List.of("[[TARGET tag=duration]] January 2026"));
        when(assistant.extract(contains("January 2026")))
                .thenReturn(new PeriodExtraction(true, "January 2026"));
        ReportDurationService service =
                new ReportDurationService(documentStore, scanner, assistant, errorMapper);

        var result = service.analyze("duration", null, null);

        assertThat(result.duration()).isEqualTo("January 2026");
        assertThat(result.detected()).isTrue();
        assertThat(result.analyzed()).isTrue();
    }
}
