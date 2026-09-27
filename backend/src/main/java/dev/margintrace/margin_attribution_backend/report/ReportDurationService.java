package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.analysis.service.LocalLlamaClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportDurationService {

    private final ReportDocumentStore documentStore;
    private final ReportContentControlScanner contentControlScanner;
    private final LocalLlamaClient llamaClient;

    public DurationAnalysis analyze(String tag) throws Exception {
        if (tag == null || tag.isBlank() || tag.length() > 200) {
            throw new IllegalArgumentException("Select a content control with a valid Tag");
        }

        List<String> contexts = contentControlScanner.contextsForTag(
                documentStore.loadOrCreateDefaultDocument(),
                tag);
        if (contexts.isEmpty()) {
            throw new IllegalArgumentException("No saved content control was found for Tag: " + tag);
        }

        LocalLlamaClient.DurationResult result = llamaClient.extractDuration(contexts);
        String duration = result.analyzed()
                ? (result.detected() ? result.duration() : "Not specified")
                : "Unavailable";
        return new DurationAnalysis(
                tag,
                contexts,
                duration,
                result.detected(),
                result.analyzed(),
                result.message());
    }

    public record DurationAnalysis(
            String tag,
            List<String> sentences,
            String duration,
            boolean detected,
            boolean analyzed,
            String message) {
    }
}
