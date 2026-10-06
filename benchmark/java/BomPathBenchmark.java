import dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier.BomFrontierReconciler;
import dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier.BomUpwardEngine;
import java.nio.file.Path;
import java.util.Locale;

/** Measures complete in-memory API path reconstruction on a prepared fixture. */
public final class BomPathBenchmark {
    /** Runs once, retaining every returned path exactly as the API does. */
    public static void main(String[] args) throws Exception {
        var reconciler = new BomFrontierReconciler();
        long start = System.nanoTime();
        var graph = BomFrontierJavaBenchmark.loadGraph(Path.of(args[0]));
        long loaded = System.nanoTime();
        System.out.printf("Loaded %,d nodes, %,d edges, %,d terminals%n",
                graph.nodeValues().length, graph.reverseSuccessors().length, graph.terminalNodes().length);
        long pruneStart = System.nanoTime();
        int[] selected = new BomUpwardEngine().run(graph, Long.parseUnsignedLong(args[1]));
        long pruned = System.nanoTime();
        System.out.printf("Pruning complete: %,d selected nodes. Building all paths...%n", selected.length);
        long pathStart = System.nanoTime();
        var paths = reconciler.buildPaths(graph, selected);
        long finished = System.nanoTime();
        long positions = 0;
        for (var path : paths) positions += path.positions().size();
        System.out.printf(Locale.ROOT,
                "PATH_BENCHMARK_JSON {\"nodes\":%d,\"edges\":%d,\"selected\":%d,\"paths\":%d,"
                + "\"path_positions\":%d,\"load_ms\":%.3f,\"prune_ms\":%.3f,\"path_build_ms\":%.3f,"
                + "\"pipeline_ms\":%.3f}%n", graph.nodeValues().length, graph.reverseSuccessors().length,
                selected.length, paths.size(), positions, (loaded-start)/1e6,
                (pruned-pruneStart)/1e6, (finished-pathStart)/1e6, (finished-start)/1e6);
    }
}
