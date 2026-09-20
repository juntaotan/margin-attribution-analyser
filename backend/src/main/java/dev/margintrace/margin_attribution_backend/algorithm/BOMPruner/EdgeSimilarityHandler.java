package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.GraphEdge;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Scores each directed edge by whether the other graph contains its ID pair. */
final class EdgeSimilarityHandler extends PruningHandler {
    private final NodeSimilarityScorer scorer = new NodeSimilarityScorer();

    EdgeSimilarityHandler(PruningHandler next) {
        super(next);
    }

    @Override
    protected void process(PruningContext context) {
        Set<NodeSimilarityScorer.EdgeEmbedding> actualEmbeddings = embeddings(context.actualGraph);
        Set<NodeSimilarityScorer.EdgeEmbedding> comparableEmbeddings = embeddings(context.comparableGraph);
        score(context.actualGraph, comparableEmbeddings, context.actualEdgeScores);
        score(context.comparableGraph, actualEmbeddings, context.comparableEdgeScores);
    }

    private Set<NodeSimilarityScorer.EdgeEmbedding> embeddings(CsrGraph graph) {
        Set<NodeSimilarityScorer.EdgeEmbedding> result = new HashSet<>();
        for (int from = 0; from < graph.nodes().length; from++) {
            for (int index = graph.offset()[from]; index < graph.offset()[from + 1]; index++) {
                result.add(scorer.buildEdgeEmbedding(graph.nodes()[from],
                        graph.nodes()[graph.successors()[index]]));
            }
        }
        return result;
    }

    private void score(CsrGraph graph, Set<NodeSimilarityScorer.EdgeEmbedding> candidates,
                       Map<GraphEdge, Integer> scores) {
        for (int from = 0; from < graph.nodes().length; from++) {
            for (int index = graph.offset()[from]; index < graph.offset()[from + 1]; index++) {
                int to = graph.successors()[index];
                scores.put(new GraphEdge(from, to, index), scorer.scoreEdge(
                        scorer.buildEdgeEmbedding(graph.nodes()[from], graph.nodes()[to]), candidates));
            }
        }
    }
}
