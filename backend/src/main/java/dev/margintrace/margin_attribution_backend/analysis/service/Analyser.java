package dev.margintrace.margin_attribution_backend.analysis.service;

import dev.margintrace.margin_attribution_backend.algorithm.AttributionWorkflow;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrResult;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisResults;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class Analyser {

    private final AttributionWorkflow attributionWorkflow;

    /**
     *
     * For the list of target points, its input's type can include all products in specified period or specified product
     * that user directly provides.
     *
     * @param targets a list of all product to be analysis
     * @return a new analysis result containing the graph
     */
    public AnalysisResults analyser (List<String> targets, LocalDate startDate, LocalDate endDate) {

        // Create a hashmap to make frontend using this data to build up its DAG diagram.
        // Hash map can make
        HashMap<String, List<Node[]>> analysisGraph = new HashMap<>();

        CsrResult csrResult = attributionWorkflow.trace(startDate, endDate, targets);

        for (int i = 0; i < targets.size(); i++){
            // 1st. Confirm i's index

            // 2nd. Finds all upstream for this index

            // 3rd. Get the information of this index
            List<Node[]> nodeList = new ArrayList<>();
            // 4th. Record this node into the hashmap
            analysisGraph.put(targets.get(i), nodeList);
        }

        // Generate result and return it
        AnalysisResults results = AnalysisResults.builder()
                .analysisId(UUID.randomUUID())
                .analysisGraph(analysisGraph)
                .build();
        return results;
    }
}
