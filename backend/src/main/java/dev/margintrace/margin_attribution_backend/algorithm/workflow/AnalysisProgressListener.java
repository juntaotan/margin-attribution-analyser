package dev.margintrace.margin_attribution_backend.algorithm.workflow;

import java.util.UUID;

/** Presentation-only events from the same computation; listeners never rerun stages. */
@FunctionalInterface
public interface AnalysisProgressListener {
    void accept(Event event);
    record Event(UUID analysisId, long sequence, String type, Object data) {}
}
