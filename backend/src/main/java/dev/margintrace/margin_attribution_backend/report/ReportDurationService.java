package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.analysis.ai.AiErrorMapper;
import dev.margintrace.margin_attribution_backend.report.ai.PeriodExtraction;
import dev.margintrace.margin_attribution_backend.report.ai.ReportPeriodAssistant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportDurationService {

    private final ReportDocumentStore documentStore;
    private final ReportContentControlScanner contentControlScanner;
    private final ReportPeriodAssistant periodAssistant;
    private final AiErrorMapper aiErrorMapper;

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

        String duration = "Unavailable";
        boolean detected = false;
        boolean analyzed = false;
        String message = null;
        try {
            PeriodExtraction extraction = periodAssistant.extract(documentText(contexts));
            if (extraction == null || (extraction.found()
                    && (extraction.duration() == null || extraction.duration().isBlank()))) {
                message = "llama.cpp returned an invalid duration";
            } else {
                analyzed = true;
                detected = extraction.found();
                duration = detected ? extraction.duration().trim() : "Not specified";
            }
        } catch (RuntimeException exception) {
            message = aiErrorMapper.message(exception);
        }

        return new DurationAnalysis(
                firstNonBlank(tag, wordId, alias),
                contexts,
                duration,
                detected,
                analyzed,
                message);
    }

    private String documentText(List<String> contexts) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < Math.min(contexts.size(), 10); index++) {
            String context = contexts.get(index);
            if (context.length() > 2_000) {
                context = context.substring(0, 2_000);
            }
            text.append(index + 1).append(". ").append(context).append('\n');
        }
        return text.toString();
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
