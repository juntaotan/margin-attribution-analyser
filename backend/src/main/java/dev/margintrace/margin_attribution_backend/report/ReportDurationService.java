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

    public DurationAnalysis analyze(String tag, String wordId, String alias) throws Exception {
        if (!validSelector(tag) || !validSelector(wordId) || !validSelector(alias)
                || allBlank(tag, wordId, alias)) {
            throw new IllegalArgumentException(
                    "Select a content control with a valid Tag, Word ID, or Alias");
        }

        List<String> contexts = contentControlScanner.contextsForControl(
                documentStore.loadOrCreateDefaultDocument(), tag, wordId, alias);
        if (contexts.isEmpty()) {
            throw new IllegalArgumentException("No saved content control was found for: "
                    + firstNonBlank(tag, wordId, alias));
        }

        LocalLlamaClient.DurationResult result = llamaClient.extractDuration(contexts);
        String duration = result.analyzed()
                ? (result.detected() ? result.duration() : "Not specified")
                : "Unavailable";
        return new DurationAnalysis(
                firstNonBlank(tag, wordId, alias),
                contexts,
                duration,
                result.detected(),
                result.analyzed(),
                result.message());
    }

    private boolean validSelector(String value) {
        return value == null || value.length() <= 200;
    }

    private boolean allBlank(String... values) {
        return java.util.Arrays.stream(values)
                .allMatch(value -> value == null || value.isBlank());
    }

    private String firstNonBlank(String... values) {
        return java.util.Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
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
