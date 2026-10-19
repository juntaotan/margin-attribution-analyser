package dev.margintrace.margin_attribution_backend.analysis.dto;

import java.util.List;
import java.util.UUID;

/** One line in the streamed analysis response. */
public record ReconciliationStreamEvent(
        String type,
        UUID analysisId,
        StreamCsrGraph actualGraph,
        StreamCsrGraph comparableGraph,
        List<ReconciliationStreamPath> paths,
        String message) {
    /**
     * @return a {@code graph} event carrying both period snapshots
     */
    public static ReconciliationStreamEvent graph(UUID id, StreamCsrGraph actual, StreamCsrGraph comparable) {
        return new ReconciliationStreamEvent("graph", id, actual, comparable, null, null);
    }

    /**
     * @return a {@code diff} event carrying the threshold paths
     */
    public static ReconciliationStreamEvent diff(UUID id, List<ReconciliationStreamPath> paths) {
        return new ReconciliationStreamEvent("diff", id, null, null, List.copyOf(paths), null);
    }

    /**
     * @return an {@code error} event carrying a message
     */
    public static ReconciliationStreamEvent error(UUID id, String message) {
        return new ReconciliationStreamEvent("error", id, null, null, null, message);
    }
}
