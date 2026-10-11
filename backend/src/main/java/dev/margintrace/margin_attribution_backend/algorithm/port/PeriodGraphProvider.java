package dev.margintrace.margin_attribution_backend.algorithm.port;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Constructs discovery CSR from one retained run snapshot, with original source evidence. */
public interface PeriodGraphProvider {
    Snapshot load(LocalDate start, LocalDate end);
    record SourcePosition(String sourceId, String productionId, Integer materialPosition, Integer receivingPosition) {}
    record Snapshot(CsrGraph graph, PeriodRecords records, LocalDate start, LocalDate end, List<String> diagnostics,
                    Map<String, SourcePosition> sources) {}
}
