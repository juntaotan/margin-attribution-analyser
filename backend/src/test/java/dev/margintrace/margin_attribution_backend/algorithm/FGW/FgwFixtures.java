package dev.margintrace.margin_attribution_backend.algorithm.FGW;

import static dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwEngine.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Portable test/benchmark driver. It never delegates engine calculations. */
public final class FgwFixtures {
    static Graph graph(int[] offsets, int[] columns, double[] values, String id) {
        double[] embeddings = new double[values.length * 384];
        List<NodeSource> sources = new ArrayList<>();
        for (int i = 0; i < values.length; ++i) {
            embeddings[i * 384] = values[i];
            sources.add(new NodeSource(id + ":" + i, "material:" + values[i], id, "1.000000000000000001",
                                       "9007199254740993.01", List.of("issue:" + i), List.of()));
        }
        return new Graph("synthetic-product", id, 0, offsets, columns, embeddings, sources);
    }
    static Comparison fixed() {
        return new Comparison(
            graph(new int[] {0, 1, 1}, new int[] {1}, new double[] {0, 2}, "normal"),
            graph(new int[] {0, 1, 2, 2}, new int[] {1, 2}, new double[] {0, 1, 2}, "actual"));
    }
    static Map<String, Object> map(Object... entries) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2)
            out.put((String)entries[i], entries[i + 1]);
        return out;
    }
    static Object costs(Costs c) {
        return map("material", c.material(), "structure", c.structure(), "fgw", c.fgw());
    }
    static Object mapping(SourceMapping mapping) {
        List<Object> nodes = new ArrayList<>();
        for (NodeSource n : mapping.nodes())
            nodes.add(map("occurrenceId", n.occurrenceId(), "materialId", n.materialId(), "productionOrderId",
                          n.productionOrderId(), "quantity", n.quantity(), "amount", n.amount(),
                          "issueLineIds", n.issueLineIds(), "fifoAllocationIds", n.fifoAllocationIds()));
        return map("productId", mapping.productId(), "instanceId", mapping.instanceId(), "rootIndex",
                   mapping.rootIndex(), "nodes", nodes);
    }
    static Object result(Result r) {
        List<Object> starts = new ArrayList<>();
        for (StartResult s : r.starts) {
            List<Object> trace = new ArrayList<>();
            for (Iteration t : s.trace)
                trace.add(map("updates", t.updates, "P", t.correspondence, "G", t.gradient, "S",
                              t.ot.correspondence, "delta", t.direction, "costs", costs(t.costs), "gap",
                              t.gap, "gamma", t.step.gamma(), "a", t.step.a(), "b", t.step.b(), "rowError",
                              t.marginals.row(), "columnError", t.marginals.column(), "totalError",
                              t.marginals.total(), "linearCost", t.ot.linearCost, "dualCost", t.ot.dualCost,
                              "dualViolation", t.ot.maximumReducedCostViolation, "pivots", t.ot.pivots,
                              "fullPricingScans", t.ot.fullPricingScans, "candidatePricingPasses",
                              t.ot.candidatePricingPasses, "treeRebuilds", t.ot.treeRebuilds,
                              "incrementalTreeUpdates", t.ot.incrementalTreeUpdates, "treeNodesVisited",
                              t.ot.treeNodesVisited, "cycleEdges", t.ot.cycleEdges));
            starts.add(map("P", s.correspondence, "costs", costs(s.costs), "gap", s.finalGap, "iterations",
                           s.iterations, "status", s.status.name(), "converged", s.converged(), "diagnostic",
                           s.diagnostic, "trace", trace));
        }
        return map("leftSources", mapping(r.left), "rightSources", mapping(r.right), "rows", r.rows,
                   "columns", r.columns, "p", r.p, "q", r.q, "material", r.materialDistances, "d1",
                   r.leftDistances, "d2", r.rightDistances, "bestStart", r.bestStart, "starts", starts);
    }
    /**
     * Minimal JSON writer for portable numerical evidence; nonfinite values are
     * null.
     */
    static String json(Object o) {
        if (o == null)
            return "null";
        if (o instanceof String s)
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        if (o instanceof Number n)
            return Double.isFinite(n.doubleValue()) ? n.toString() : "null";
        if (o instanceof Boolean)
            return o.toString();
        if(o instanceof Map<?,?> m){
            StringJoiner j = new StringJoiner(",", "{", "}");
            for (var e : m.entrySet())
                j.add(json(e.getKey()) + ":" + json(e.getValue()));
            return j.toString();
        }
        if (o instanceof Iterable<?> list) {
            StringJoiner j = new StringJoiner(",", "[", "]");
            for (Object v : list)
                j.add(json(v));
            return j.toString();
        }
        if (o instanceof double[] a) {
            StringJoiner j = new StringJoiner(",", "[", "]");
            for (double v : a)
                j.add(json(v));
            return j.toString();
        }
        throw new IllegalArgumentException("Unsupported evidence value: " + o.getClass());
    }
    static Map<String, Object> report() {
        Config c = new Config();
        String mode = System.getenv("FGW_SIMPLEX_MODE");
        if (mode != null) {
            int v = Integer.parseInt(mode);
            c.incrementalOtTree = (v & 1) != 0;
            c.candidateOtPricing = (v & 2) != 0;
        }
        c.captureTrace = true;
        FgwEngine engine = new FgwEngine();
        Comparison f = fixed();
        Result fixed = engine.compare(f, c);
        Result multi = engine.compare(
            new Comparison(f.left(), f.right(),
                           List.of(new double[] {1. / 6, 1. / 6, 1. / 6, 1. / 6, 1. / 6, 1. / 6},
                                   new double[] {1. / 3, 1. / 6, 0, 0, 1. / 6, 1. / 3})),
            c);
        Graph l = graph(new int[] {0, 2, 2, 2}, new int[] {1, 2}, new double[] {0, 1, 2}, "siblings-left"),
              r = graph(new int[] {0, 2, 2, 2}, new int[] {1, 2}, new double[] {0, 2, 1}, "siblings-right");
        List<double[]> starts =
            List.of(new double[] {1. / 9, 1. / 9, 1. / 9, 1. / 9, 1. / 9, 1. / 9, 1. / 9, 1. / 9, 1. / 9},
                    new double[] {1. / 3, 0, 0, 0, 0, 1. / 3, 0, 1. / 3, 0});
        return map("execution", "JAVA_SERIAL", "fixed", result(fixed), "unequalMultiStart", result(multi),
                   "ordering", result(engine.compare(new Comparison(l, r, starts), c)), "orderingReverse",
                   result(engine.compare(new Comparison(r, l, starts), c)));
    }
    /** Fast text reader; fixture parsing is excluded from timed computation. */
    static final class Tokens implements AutoCloseable {
        final BufferedReader reader;
        StringTokenizer tokens = new StringTokenizer("");
        Tokens(String path) throws IOException { reader = Files.newBufferedReader(Path.of(path)); }
        String next() throws IOException {
            while (!tokens.hasMoreTokens()) {
                String line = reader.readLine();
                if (line == null)
                    throw new IOException("Truncated fixture.");
                tokens = new StringTokenizer(line);
            }
            return tokens.nextToken();
        }
        int integer() throws IOException { return Integer.parseInt(next()); }
        double number() throws IOException { return Double.parseDouble(next()); }
        public void close() throws IOException { reader.close(); }
    }
    static Graph readGraph(Tokens t, int index) throws IOException {
        int n = t.integer(), e = t.integer();
        int[] offsets = new int[n + 1], columns = new int[e];
        double[] emb = new double[Math.multiplyExact(n, 384)];
        for (int i = 0; i < offsets.length; ++i)
            offsets[i] = t.integer();
        for (int i = 0; i < e; ++i)
            columns[i] = t.integer();
        for (int i = 0; i < emb.length; ++i)
            emb[i] = t.number();
        List<NodeSource> sources = new ArrayList<>();
        for (int i = 0; i < n; ++i)
            sources.add(new NodeSource("synthetic-" + index + ":" + i, "", "", "", "", List.of(), List.of()));
        return new Graph("benchmark-product", "synthetic-" + index, 0, offsets, columns, emb, sources);
    }
    static double ms(long start) { return (System.nanoTime() - start) / 1e6; }
    static void benchmark(String[] args) throws Exception {
        int count = Integer.parseInt(args[2]), warmup = Integer.parseInt(args[3]);
        Graph baseline;
        List<Graph> targets = new ArrayList<>();
        try (Tokens t = new Tokens(args[1])) {
            baseline = readGraph(t, 0);
            int pool = t.integer();
            for (int i = 0; i < pool; ++i)
                targets.add(readGraph(t, i + 1));
        }
        long total = System.nanoTime();
        Session session = new Session(baseline, new Config());
        double init = ms(total);
        long warm = System.nanoTime();
        List<Result> reference = new ArrayList<>();
        for (Graph target : targets)
            reference.add(session.compare(target));
        for (int i = 0; i < warmup; ++i)
            session.compare(targets.get(i % targets.size()));
        double warmMs = ms(warm);
        long steady = System.nanoTime();
        double maxError = 0;
        for (int i = 0; i < count; ++i) {
            Result r = session.compare(targets.get(i % targets.size()));
            StartResult a = r.starts.get(r.bestStart), b = reference.get(i % targets.size()).starts.get(0);
            MarginalErrors e = marginalErrors(a.correspondence, r.rows, r.columns);
            maxError = Math.max(maxError, Math.max(e.row(), Math.max(e.column(), e.total())));
            if (a.status == Status.NUMERICAL_FAILURE || a.status != b.status ||
                a.iterations != b.iterations || Math.abs(a.costs.fgw() - b.costs.fgw()) > 1e-8 ||
                Math.abs(a.finalGap - b.finalGap) > 1e-8 || maxError > 1e-8)
                throw new AssertionError("Repeated serial comparison differs.");
        }
        double steadyMs = ms(steady);
        List<Object> poolResults = new ArrayList<>();
        for (Result r : reference)
            poolResults.add(result(r));
        Files.writeString(
            Path.of(args[4]),
            json(map("execution", "JAVA_SERIAL", "threads", 1, "requested", count, "completed", count,
                     "status", "PASS", "initializationMilliseconds", init, "warmupMilliseconds", warmMs,
                     "steadyMilliseconds", steadyMs, "totalMilliseconds", ms(total), "comparisonsPerSecond",
                     count * 1000 / steadyMs, "maximumMarginalError", maxError, "poolResults", poolResults)));
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--batch")) {
            benchmark(args);
            return;
        }
        if (args.length == 2 && args[0].equals("--ot")) {
            try (Tokens t = new Tokens(args[1])) {
                int n = t.integer(), m = t.integer();
                double[] cost = new double[Math.multiplyExact(n, m)];
                for (int i = 0; i < cost.length; ++i)
                    cost[i] = t.number();
                Config c = new Config();
                String mode = System.getenv("FGW_SIMPLEX_MODE");
                if (mode != null) {
                    int v = Integer.parseInt(mode);
                    c.incrementalOtTree = (v & 1) != 0;
                    c.candidateOtPricing = (v & 2) != 0;
                }
                OtWorkspace workspace = new OtWorkspace();
                LinearOtResult cold = exactLinearOt(cost, n, m, c, workspace);
                for (int i = 0; i < cost.length; ++i)
                    cost[i] = -cost[i];
                LinearOtResult warm = exactLinearOt(cost, n, m, c, workspace);
                System.out.println(json(map(
                    "P", cold.correspondence, "linearCost", cold.linearCost, "dualCost", cold.dualCost,
                    "dualViolation", cold.maximumReducedCostViolation, "warmP", warm.correspondence,
                    "warmLinearCost", warm.linearCost, "warmDualCost", warm.dualCost, "warmDualViolation",
                    warm.maximumReducedCostViolation, "pivots", cold.pivots, "bland", cold.usedBlandRule)));
            }
            return;
        }
        if (args.length == 1) {
            Files.writeString(Path.of(args[0]), json(report()));
            return;
        }
        if (args.length >= 2) {
            try (Tokens t = new Tokens(args[0])) {
                Config c = new Config();
                c.captureTrace = args.length > 2 && !args[2].equals("0");
                Result r = new FgwEngine().compare(new Comparison(readGraph(t, 0), readGraph(t, 1)), c);
                System.out.println(json(result(r)));
            }
            return;
        }
        System.out.println(json(report()));
    }
}
