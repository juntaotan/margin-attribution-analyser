package dev.margintrace.margin_attribution_backend.algorithm.FGW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reusable FP64 balanced FGW engine. All calculations, including exact integral
 * transportation simplex, execute serially in the calling thread. No executor,
 * parallel stream, device, Python process or native numerical library is used.
 * Dense matrices are row-major. Uniform marginals and squared distances follow
 * the native CUDA contract; decimal source text is copied without conversion.
 */
public final class FgwEngine {
    public static final int EMBEDDING_DIMENSION = 384;

    /** Caller-provided evidence; quantity/amount text does not affect weights. */
    public record NodeSource(
            String occurrenceId,
            String materialId,
            String productionOrderId,
            String quantity,
            String amount,
            List<String> issueLineIds,
            List<String> fifoAllocationIds) {
        public NodeSource {
            issueLineIds = List.copyOf(issueLineIds);
            fifoAllocationIds = List.copyOf(fifoAllocationIds);
        }
    }

    /** Connected, unweighted CSR with embeddings and sources in node order. */
    public record Graph(
            String productId,
            String instanceId,
            int rootIndex,
            int[] rowOffsets,
            int[] colIndices,
            double[] embeddings,
            List<NodeSource> sources) {
        public Graph {
            rowOffsets = rowOffsets.clone();
            colIndices = colIndices.clone();
            embeddings = embeddings.clone();
            sources = List.copyOf(sources);
        }

        public int nodeCount() {
            return rowOffsets.length - 1;
        }
    }

    /** Mathematical defaults match CUDA. Device-only controls are omitted. */
    public static final class Config {
        public double alpha = 0.8, tolAbs = 1e-6, tolRel = 1e-5;
        public int maxIterations = 200;
        public double tolGapAbs = 1e-6, tolGapRel = 1e-5, costScaleFloor = 1e-12;
        public double feasibilityTolerance = 1e-9, numericalTolerance = 1e-10, otTolerance = 1e-12;
        public boolean captureTrace = false, incrementalOtTree = true, candidateOtPricing = true;

        private Config copy() {
            Config c = new Config();
            c.alpha = alpha;
            c.tolAbs = tolAbs;
            c.tolRel = tolRel;
            c.maxIterations = maxIterations;
            c.tolGapAbs = tolGapAbs;
            c.tolGapRel = tolGapRel;
            c.costScaleFloor = costScaleFloor;
            c.feasibilityTolerance = feasibilityTolerance;
            c.numericalTolerance = numericalTolerance;
            c.otTolerance = otTolerance;
            c.captureTrace = captureTrace;
            c.incrementalOtTree = incrementalOtTree;
            c.candidateOtPricing = candidateOtPricing;
            return c;
        }
    }

    public enum Status {
        RUNNING,
        FW_GAP_REACHED,
        COST_STALLED,
        MAX_ITERATIONS,
        NUMERICAL_FAILURE
    }

    public record Costs(double material, double structure, double fgw) {}

    public record MarginalErrors(double row, double column, double total, double minimum, boolean finite) {}

    public record LineSearch(double a, double b, double gamma) {}

    public record SourceMapping(String productId, String instanceId, int rootIndex, List<NodeSource> nodes) {}

    public record Comparison(Graph left, Graph right, List<double[]> initialMatrices) {
        public Comparison {
            List<double[]> copies = new ArrayList<>();
            for (double[] initial : initialMatrices) copies.add(initial.clone());
            initialMatrices = List.copyOf(copies);
        }

        public Comparison(Graph left, Graph right) {
            this(left, right, List.of());
        }
    }

    public static final class LinearOtResult {
        public double[] correspondence;
        public double linearCost, dualCost, maximumReducedCostViolation;
        public long pivots, degeneratePivots, fullPricingScans, candidatePricingPasses;
        public long treeRebuilds, incrementalTreeUpdates, treeNodesVisited, cycleEdges;
        public boolean usedBlandRule;
    }

    public static final class Iteration {
        public int updates;
        public Costs costs;
        public double gap, absoluteCostChange, relativeCostChange;
        public MarginalErrors marginals;
        public LinearOtResult ot;
        public LineSearch step = new LineSearch(0, 0, 0);
        public double[] correspondence = {}, gradient = {}, direction = {};
    }

    public static final class StartResult {
        public double[] correspondence;
        public Costs costs;
        public double finalGap;
        public int iterations;
        public Status status = Status.NUMERICAL_FAILURE;
        public String diagnostic = "";
        public final List<Iteration> trace = new ArrayList<>();

        public boolean converged() {
            return status == Status.FW_GAP_REACHED;
        }
    }
    /** Overlapping native execution intervals; these must not be summed into wall time. */
    public static final class ExecutionTimes {
        public double cpuOtMilliseconds, gpuOtMilliseconds, deviceMilliseconds, synchronizationMilliseconds;
    }

    public static final class Result {
        public int rows, columns, bestStart;
        public SourceMapping left, right;
        public double[] p, q, materialDistances = {}, leftDistances = {}, rightDistances = {};
        public final List<StartResult> starts = new ArrayList<>();
        public double totalMilliseconds;
        public String execution = "JAVA_SERIAL_EXACT_SIMPLEX";
        public ExecutionTimes executionTimes = new ExecutionTimes();
    }

    /**
     * Prepare immutable baseline distances, weights and constants once.
     * Calls to compare remain synchronous on the calling thread.
     */
    public static final class Session {
        private final Graph baseline;
        private final double[] distances, norm;
        private final Config config;
        private final long ownerThread = Thread.currentThread().threadId();

        public Session(Graph baseline, Config config) {
            validateConfig(config);
            this.config = config.copy();
            this.baseline = new Graph(
                    baseline.productId,
                    baseline.instanceId,
                    baseline.rootIndex,
                    baseline.rowOffsets,
                    baseline.colIndices,
                    baseline.embeddings,
                    baseline.sources);
            distances = distances(this.baseline);
            norm = norm(distances, this.baseline.nodeCount());
        }
        /**
         * Compare a target using fresh per-start bases; no target result is
         * cached. Invalid inputs throw IllegalArgumentException. Numerical failures
         * are explicit statuses and do not affect subsequent comparisons.
         */
        public Result compare(Graph target, List<double[]> initialMatrices) {
            if (Thread.currentThread().threadId() != ownerThread)
                throw new IllegalStateException("Serial FGW session must be used by its calling thread.");
            return solve(baseline, target, initialMatrices, config, distances, norm);
        }

        public Result compare(Graph target) {
            return compare(target, List.of());
        }
    }

    /** Compare a single pair with the supplied mathematical settings. */
    public Result compare(Comparison input, Config config) {
        return new Session(input.left, config).compare(input.right, input.initialMatrices);
    }

    public Result compare(Comparison input) {
        return compare(input, new Config());
    }
    /** Ordered arbitrary-pair comparisons, executed serially in this thread. */
    public List<Result> compareBatch(List<Comparison> inputs, Config config) {
        validateConfig(config);
        List<Result> results = new ArrayList<>();
        for (Comparison input : inputs) results.add(compare(input, config));
        return results;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public static void validateConfig(Config c) {
        for (double x : new double[] {
            c.alpha,
            c.tolAbs,
            c.tolRel,
            c.tolGapAbs,
            c.tolGapRel,
            c.costScaleFloor,
            c.feasibilityTolerance,
            c.numericalTolerance,
            c.otTolerance
        }) require(Double.isFinite(x) && x >= 0, "FGW configuration must be finite and nonnegative.");
        require(
                c.alpha <= 1
                        && c.costScaleFloor > 0
                        && c.feasibilityTolerance > 0
                        && c.numericalTolerance > 0
                        && c.otTolerance > 0
                        && c.maxIterations >= 0,
                "Invalid FGW tolerances/iteration limit.");
    }

    private static int[][] adjacency(Graph g) {
        int n = g.nodeCount();
        require(n > 0, "FGW requires a nonempty graph.");
        require(
                g.productId != null && !g.productId.isEmpty() && g.instanceId != null && !g.instanceId.isEmpty(),
                "Missing product/instance identity.");
        require(
                g.rootIndex >= 0
                        && g.rootIndex < n
                        && g.rowOffsets[0] == 0
                        && g.rowOffsets[n] == g.colIndices.length
                        && g.embeddings.length == Math.multiplyExact(n, EMBEDDING_DIMENSION)
                        && g.sources.size() == n,
                "Missing/misaligned CSR, embedding or source mapping.");
        List<List<Integer>> edges = new ArrayList<>();
        for (int i = 0; i < n; ++i) edges.add(new ArrayList<>());
        for (int i = 0; i < n; ++i) {
            require(
                    g.rowOffsets[i] >= 0
                            && g.rowOffsets[i] <= g.rowOffsets[i + 1]
                            && g.rowOffsets[i + 1] <= g.colIndices.length,
                    "Invalid CSR offsets.");
            require(
                    g.sources.get(i).occurrenceId != null
                            && !g.sources.get(i).occurrenceId.isEmpty(),
                    "Missing occurrence identity.");
            for (int e = g.rowOffsets[i]; e < g.rowOffsets[i + 1]; ++e) {
                int j = g.colIndices[e];
                require(j >= 0 && j < n, "Invalid CSR column.");
                edges.get(i).add(j);
                edges.get(j).add(i);
            }
        }
        for (double x : g.embeddings) require(Double.isFinite(x), "Nonfinite embedding.");
        int[][] out = new int[n][];
        for (int i = 0; i < n; ++i) {
            // Sorting/deduplication is serial, including preparation.
            List<Integer> list = edges.get(i);
            list.sort(Integer::compare);
            int[] row = new int[list.size()];
            int count = 0;
            for (int v : list) if (count == 0 || row[count - 1] != v) row[count++] = v;
            out[i] = Arrays.copyOf(row, count);
        }
        return out;
    }

    private static double[] distances(Graph g) {
        int[][] edges = adjacency(g);
        int n = edges.length;
        double[] d = new double[Math.multiplyExact(n, n)];
        Arrays.fill(d, -1);
        int[] queue = new int[n];
        for (int source = 0; source < n; ++source) {
            int head = 0, tail = 1;
            queue[0] = source;
            d[source * n + source] = 0;
            while (head < tail) {
                int u = queue[head++];
                for (int v : edges[u])
                    if (d[source * n + v] < 0) {
                        d[source * n + v] = d[source * n + u] + 1;
                        queue[tail++] = v;
                    }
            }
            require(tail == n, "Disconnected undirected graph.");
        }
        return d;
    }

    private static double[] norm(double[] d, int n) {
        double[] a = new double[n];
        for (int i = 0; i < n; ++i) for (int k = 0; k < n; ++k) a[i] += d[i * n + k] * d[i * n + k] / n;
        return a;
    }

    private static double[] product(double[] d1, double[] p, double[] d2, int n, int m) {
        double[] temp = new double[p.length], out = new double[p.length];
        for (int i = 0; i < n; ++i)
            for (int k = 0; k < n; ++k) {
                double d = d1[i * n + k];
                for (int j = 0; j < m; ++j) temp[i * m + j] += d * p[k * m + j];
            }
        for (int i = 0; i < n; ++i)
            for (int j = 0; j < m; ++j) {
                double v = 0;
                for (int k = 0; k < m; ++k) v += temp[i * m + k] * d2[j * m + k];
                out[i * m + j] = v;
            }
        return out;
    }

    private static double dot(double[] a, double[] b) {
        double v = 0;
        for (int i = 0; i < a.length; ++i) v += a[i] * b[i];
        return v;
    }

    public static MarginalErrors marginalErrors(double[] p, int n, int m) {
        if (n < 1 || m < 1 || p.length != (long) n * m) return new MarginalErrors(0, 0, 0, 0, false);
        double row = 0, column = 0, total = 0, min = Double.POSITIVE_INFINITY;
        boolean finite = true;
        double[] cols = new double[m];
        for (int i = 0; i < n; ++i) {
            double sum = 0;
            for (int j = 0; j < m; ++j) {
                double v = p[i * m + j];
                sum += v;
                cols[j] += v;
                min = Math.min(min, v);
                finite &= Double.isFinite(v);
            }
            row = Math.max(row, Math.abs(sum - 1.0 / n));
            total += sum;
        }
        for (double v : cols) column = Math.max(column, Math.abs(v - 1.0 / m));
        return new MarginalErrors(row, column, Math.abs(total - 1), min, finite);
    }

    private static boolean feasible(MarginalErrors e, double tol) {
        return e.finite && e.minimum >= -tol && Math.max(e.row, Math.max(e.column, e.total)) <= tol;
    }
    /** Exact quadratic minimum on [0,1], preserving near-zero handling. */
    public static LineSearch quadraticLineSearch(double directionProduct, double b, Config c) {
        double a = -2 * c.alpha * directionProduct;
        if (!Double.isFinite(a) || !Double.isFinite(b))
            throw new ArithmeticException("Nonfinite line-search coefficients.");
        double gamma = 0, best = 0;
        if (a + b < best) {
            best = a + b;
            gamma = 1;
        }
        if (a > 0 && (a > c.numericalTolerance || Math.abs(b) <= 2 * a)) {
            double candidate = Math.clamp(-b / (2 * a), 0, 1), change = a * candidate * candidate + b * candidate;
            if (change < best) gamma = candidate;
        }
        return new LineSearch(a, b, gamma);
    }
    /**
     * Decide stopping from a freshly evaluated gap and original marginals.
     * A gap success precedes the update limit, which precedes cost stalling;
     * nonfinite costs or a significantly negative gap are numerical failures.
     */
    public static Status stoppingStatus(
            double gap, Costs f, MarginalErrors e, int updates, double previous, boolean hasPrevious, Config c) {
        if (!Double.isFinite(gap)
                || !Double.isFinite(f.material)
                || !Double.isFinite(f.structure)
                || !Double.isFinite(f.fgw)
                || !feasible(e, c.feasibilityTolerance)
                || gap < -c.numericalTolerance * Math.max(Math.abs(f.fgw), c.costScaleFloor))
            return Status.NUMERICAL_FAILURE;
        if (gap <= c.tolGapAbs + c.tolGapRel * Math.max(Math.abs(f.fgw), c.costScaleFloor))
            return Status.FW_GAP_REACHED;
        if (updates >= c.maxIterations) return Status.MAX_ITERATIONS;
        if (hasPrevious) {
            double change = Math.abs(previous - f.fgw);
            if (change <= c.tolAbs || change / Math.max(Math.abs(previous), c.costScaleFloor) <= c.tolRel)
                return Status.COST_STALLED;
        }
        return Status.RUNNING;
    }

    private static SourceMapping mapping(Graph g) {
        return new SourceMapping(g.productId, g.instanceId, g.rootIndex, g.sources);
    }

    private static Result solve(
            Graph left, Graph right, List<double[]> initials, Config c, double[] d1, double[] leftNorm) {
        long begin = System.nanoTime();
        double[] d2 = distances(right);
        require(left.productId.equals(right.productId), "FGW compares the same product.");
        int n = left.nodeCount(), m = right.nodeCount(), count = Math.multiplyExact(n, m);
        for (double[] p : initials) {
            MarginalErrors e = marginalErrors(p, n, m);
            require(feasible(e, c.feasibilityTolerance) && e.minimum >= 0, "Invalid initial correspondence.");
        }
        double[] material = new double[count], fixed = new double[count], rightNorm = norm(d2, m);
        for (int i = 0; i < n; ++i)
            for (int j = 0; j < m; ++j) {
                int z = i * m + j;
                fixed[z] = leftNorm[i] + rightNorm[j];
                for (int k = 0; k < EMBEDDING_DIMENSION; ++k) {
                    double delta = left.embeddings[i * EMBEDDING_DIMENSION + k]
                            - right.embeddings[j * EMBEDDING_DIMENSION + k];
                    material[z] += delta * delta;
                }
            }
        Result r = new Result();
        r.rows = n;
        r.columns = m;
        r.left = mapping(left);
        r.right = mapping(right);
        r.p = new double[n];
        r.q = new double[m];
        Arrays.fill(r.p, 1.0 / n);
        Arrays.fill(r.q, 1.0 / m);
        if (c.captureTrace) {
            r.materialDistances = material.clone();
            r.leftDistances = d1.clone();
            r.rightDistances = d2.clone();
        }
        int starts = initials.isEmpty() ? 1 : initials.size();
        for (int start = 0; start < starts; ++start) {
            double[] p =
                    initials.isEmpty() ? new double[count] : initials.get(start).clone();
            if (initials.isEmpty()) Arrays.fill(p, 1.0 / n / m);
            StartResult out = new StartResult();
            OtWorkspace workspace = new OtWorkspace();
            boolean hasPrevious = false;
            double previous = 0;
            for (; ; ) {
                if (Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException("FGW cancelled");
                Iteration t = new Iteration();
                t.updates = out.iterations;
                double[] prod = product(d1, p, d2, n, m), h = new double[count], gradient = new double[count];
                for (int z = 0; z < count; ++z) {
                    h[z] = fixed[z] - 2 * prod[z];
                    gradient[z] = (1 - c.alpha) * material[z] + 2 * c.alpha * h[z];
                }
                double mat = dot(material, p), str = dot(h, p);
                t.costs = new Costs(mat, str, (1 - c.alpha) * mat + c.alpha * str);
                t.marginals = marginalErrors(p, n, m);
                double[] delta = new double[count];
                try {
                    t.ot = exactLinearOt(gradient, n, m, c, workspace);
                    for (int z = 0; z < count; ++z) delta[z] = t.ot.correspondence[z] - p[z];
                    double b = dot(gradient, delta);
                    t.gap = -b;
                    out.status = stoppingStatus(t.gap, t.costs, t.marginals, out.iterations, previous, hasPrevious, c);
                    if (hasPrevious) {
                        t.absoluteCostChange = Math.abs(previous - t.costs.fgw);
                        t.relativeCostChange = t.absoluteCostChange / Math.max(Math.abs(previous), c.costScaleFloor);
                        if (t.costs.fgw > previous + c.numericalTolerance * Math.max(1, Math.abs(previous))) {
                            out.status = Status.NUMERICAL_FAILURE;
                            out.diagnostic = "Exact line search increased the true objective.";
                        }
                    }
                    if (out.status == Status.RUNNING)
                        t.step = quadraticLineSearch(dot(product(d1, delta, d2, n, m), delta), b, c);
                } catch (ArithmeticException error) {
                    out.status = Status.NUMERICAL_FAILURE;
                    out.diagnostic = error.getMessage();
                    t.gap = Double.NaN;
                    if (t.ot == null) t.ot = new LinearOtResult();
                }
                out.costs = t.costs;
                out.finalGap = t.gap;
                out.correspondence = p.clone();
                if (c.captureTrace) {
                    t.correspondence = p.clone();
                    t.gradient = gradient;
                    t.direction = delta.clone();
                } else t.ot.correspondence = new double[0];
                out.trace.add(t);
                if (out.status != Status.RUNNING) {
                    if (out.status == Status.NUMERICAL_FAILURE && out.diagnostic.isEmpty())
                        out.diagnostic = "Nonfinite costs, failed marginals or " + "significantly negative fresh gap.";
                    break;
                }
                previous = t.costs.fgw;
                hasPrevious = true;
                for (int z = 0; z < count; ++z) p[z] += t.step.gamma * delta[z];
                ++out.iterations;
            }
            r.starts.add(out);
        }
        boolean found = false;
        for (int i = 0; i < r.starts.size(); ++i) {
            StartResult s = r.starts.get(i);
            if (s.status != Status.NUMERICAL_FAILURE
                    && Double.isFinite(s.costs.fgw)
                    && (!found || s.costs.fgw < r.starts.get(r.bestStart).costs.fgw)) {
                r.bestStart = i;
                found = true;
            }
        }
        r.totalMilliseconds = (System.nanoTime() - begin) / 1e6;
        return r;
    }

    /**
     * Algorithm-owned feasible basis, reusable only across changed costs in one
     * comparison/start. A size change resets it. Fields are deliberately private.
     */
    public static final class OtWorkspace {
        private int n, m;
        private long[] flow;
        private boolean[] basic;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }
        return a;
    }

    private static int arc(int a, int b, int n, int m) {
        return a < n ? a * m + b - n : b * m + a - n;
    }

    private static long hash(int arc) {
        long v = arc + 0x9e3779b97f4a7c15L;
        v = (v ^ (v >>> 30)) * 0xbf58476d1ce4e5b9L;
        v = (v ^ (v >>> 27)) * 0x94d049bb133111ebL;
        return v ^ (v >>> 31);
    }

    private static void add(List<List<Integer>> adjacency, int arc, int n, int m) {
        int row = arc / m, col = n + arc % m;
        adjacency.get(row).add(col);
        adjacency.get(col).add(row);
    }

    private static int rebuild(
            List<List<Integer>> edges,
            double[] cost,
            int n,
            int m,
            int[] parent,
            int[] parentArc,
            int[] depth,
            double[] dual,
            int[] queue,
            int root,
            int excluded) {
        int head = 0, tail = 1;
        queue[0] = root;
        boolean[] visited = new boolean[n + m];
        visited[root] = true;
        while (head < tail) {
            int u = queue[head++];
            for (int v : edges.get(u))
                if (v != excluded && !visited[v]) {
                    visited[v] = true;
                    parent[v] = u;
                    parentArc[v] = arc(u, v, n, m);
                    depth[v] = depth[u] + 1;
                    dual[v] = cost[parentArc[v]] - dual[u];
                    queue[tail++] = v;
                }
        }
        return tail;
    }
    /**
     * Exact balanced LP using integral mass units 1/lcm(N,M). Incremental tree
     * updates and cached edge pricing preserve a full-scan optimality
     * certificate. Changed-cost warm starts reuse only feasible bases. Degenerate
     * repeated bases activate Bland's rule. Throws ArithmeticException on
     * numerical, disconnected-basis or 100,000-pivot safety failures.
     */
    public static LinearOtResult exactLinearOt(double[] cost, int n, int m, Config c, OtWorkspace workspace) {
        validateConfig(c);
        require(n > 0 && m > 0 && cost.length == (long) n * m, "Invalid OT dimensions.");
        int nodes = Math.addExact(n, m), count = cost.length;
        double scale = 1;
        for (double x : cost) {
            if (!Double.isFinite(x)) throw new ArithmeticException("Nonfinite exact OT cost.");
            scale = Math.max(scale, Math.abs(x));
        }
        double epsilon = c.otTolerance * scale;
        int divisor = gcd(n, m);
        long rowUnits = m / divisor, colUnits = n / divisor, total = (long) n * rowUnits;
        boolean warm = workspace.n == n && workspace.m == m;
        long[] flow = warm ? workspace.flow : new long[count];
        boolean[] basic = warm ? workspace.basic : new boolean[count];
        List<List<Integer>> edges = new ArrayList<>();
        for (int i = 0; i < nodes; ++i) edges.add(new ArrayList<>());
        if (!warm) {
            int row = 0, col = 0;
            long supply = rowUnits, demand = colUnits;
            while (row < n && col < m) {
                int z = row * m + col;
                long mass = Math.min(supply, demand);
                flow[z] = mass;
                basic[z] = true;
                supply -= mass;
                demand -= mass;
                if (supply == 0 && demand == 0) {
                    if (row + 1 < n && col + 1 < m) basic[row * m + col + 1] = true;
                    ++row;
                    ++col;
                    supply = rowUnits;
                    demand = colUnits;
                } else if (supply == 0) {
                    ++row;
                    supply = rowUnits;
                } else {
                    ++col;
                    demand = colUnits;
                }
            }
        }
        long basisHash = 0;
        for (int z = 0; z < count; ++z)
            if (basic[z]) {
                add(edges, z, n, m);
                basisHash ^= hash(z);
            }
        int[] parent = new int[nodes],
                parentArc = new int[nodes],
                depth = new int[nodes],
                queue = new int[nodes],
                cycle = new int[nodes],
                reverse = new int[nodes];
        int lanes = Math.min(256, count);
        int[] candidates = new int[lanes];
        Arrays.fill(candidates, -1);
        boolean candidatesReady = false, bland = false;
        Set<Long> repeated = new HashSet<>();
        double[] dual = new double[nodes];
        LinearOtResult out = new LinearOtResult();
        for (; ; ) {
            if (Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("Exact OT cancelled");
            if (!c.incrementalOtTree || out.pivots == 0) {
                parent[0] = 0;
                depth[0] = 0;
                dual[0] = 0;
                int visited = rebuild(edges, cost, n, m, parent, parentArc, depth, dual, queue, 0, -1);
                ++out.treeRebuilds;
                out.treeNodesVisited += visited;
                if (visited != nodes) throw new ArithmeticException("Disconnected exact OT basis.");
            }
            int entering = -1;
            double best = -epsilon;
            if (c.candidateOtPricing && candidatesReady && !bland) {
                ++out.candidatePricingPasses;
                for (int z : candidates)
                    if (z >= 0 && !basic[z]) {
                        double red = cost[z] - dual[z / m] - dual[n + z % m];
                        if (red < best) {
                            best = red;
                            entering = z;
                        }
                    }
            }
            if (entering < 0) {
                ++out.fullPricingScans;
                if (c.candidateOtPricing && !bland) {
                    Arrays.fill(candidates, -1);
                    for (int lane = 0; lane < lanes; ++lane) {
                        double laneBest = -epsilon;
                        for (int z = lane; z < count; z += lanes)
                            if (!basic[z]) {
                                double red = cost[z] - dual[z / m] - dual[n + z % m];
                                if (red < laneBest) {
                                    laneBest = red;
                                    candidates[lane] = z;
                                }
                            }
                        if (laneBest < best) {
                            best = laneBest;
                            entering = candidates[lane];
                        }
                    }
                    candidatesReady = true;
                } else
                    for (int z = 0; z < count; ++z)
                        if (!basic[z]) {
                            double red = cost[z] - dual[z / m] - dual[n + z % m];
                            if (red < best) {
                                best = red;
                                entering = z;
                                if (bland) break;
                            }
                        }
            }
            // Only a complete scan with no improving arc can reach this branch.
            if (entering < 0) break;
            if (out.pivots >= 100000) throw new ArithmeticException("Exact OT pivot safety limit exhausted.");
            int entRow = entering / m, entCol = n + entering % m, len = 0;
            if (c.incrementalOtTree) {
                int u = entRow, v = entCol, rev = 0;
                while (u != v) {
                    if (depth[u] >= depth[v]) {
                        cycle[len++] = parentArc[u];
                        u = parent[u];
                    } else {
                        reverse[rev++] = parentArc[v];
                        v = parent[v];
                    }
                }
                for (int k = rev - 1; k >= 0; --k) cycle[len++] = reverse[k];
            } else {
                Arrays.fill(parent, -1);
                int head = 0, tail = 1;
                queue[0] = entCol;
                parent[entCol] = entCol;
                while (head < tail && parent[entRow] < 0) {
                    int u = queue[head++];
                    for (int v : edges.get(u))
                        if (parent[v] < 0) {
                            parent[v] = u;
                            parentArc[v] = arc(u, v, n, m);
                            queue[tail++] = v;
                        }
                }
                out.treeNodesVisited += tail;
                if (parent[entRow] < 0) throw new ArithmeticException("Missing OT pivot cycle.");
                for (int u = entRow; u != entCol; u = parent[u]) cycle[len++] = parentArc[u];
            }
            out.cycleEdges += len;
            long mass = total;
            int leaving = -1;
            for (int k = 0; k < len; k += 2) {
                int z = cycle[k];
                if (flow[z] < mass || (flow[z] == mass && (leaving < 0 || z < leaving))) {
                    mass = flow[z];
                    leaving = z;
                }
            }
            if (leaving < 0 || mass < 0) throw new ArithmeticException("Invalid OT pivot mass.");
            int moving = -1, fixed = -1;
            if (c.incrementalOtTree) {
                int leaveRow = leaving / m,
                        leaveCol = n + leaving % m,
                        child = parent[leaveRow] == leaveCol ? leaveRow : leaveCol;
                int ancestor = entRow;
                while (depth[ancestor] > depth[child]) ancestor = parent[ancestor];
                boolean rowDetached = ancestor == child;
                moving = rowDetached ? entRow : entCol;
                fixed = rowDetached ? entCol : entRow;
            }
            flow[entering] += mass;
            for (int k = 0; k < len; ++k) flow[cycle[k]] += k % 2 == 0 ? -mass : mass;
            int leaveRow = leaving / m, leaveCol = n + leaving % m;
            edges.get(leaveRow).remove(Integer.valueOf(leaveCol));
            edges.get(leaveCol).remove(Integer.valueOf(leaveRow));
            basic[leaving] = false;
            if (c.incrementalOtTree) {
                parent[moving] = fixed;
                parentArc[moving] = entering;
                depth[moving] = depth[fixed] + 1;
                dual[moving] = cost[entering] - dual[fixed];
                out.treeNodesVisited += rebuild(edges, cost, n, m, parent, parentArc, depth, dual, queue, moving, -1);
                ++out.incrementalTreeUpdates;
            }
            add(edges, entering, n, m);
            basic[entering] = true;
            basisHash ^= hash(leaving) ^ hash(entering);
            ++out.pivots;
            if (mass == 0) {
                ++out.degeneratePivots;
                if (!bland && !repeated.add(basisHash)) {
                    bland = true;
                    repeated.clear();
                }
            } else repeated.clear();
        }
        out.usedBlandRule = bland;
        out.correspondence = new double[count];
        for (int z = 0; z < count; ++z) {
            out.correspondence[z] = (double) flow[z] / total;
            out.linearCost += cost[z] * out.correspondence[z];
            out.maximumReducedCostViolation =
                    Math.max(out.maximumReducedCostViolation, dual[z / m] + dual[n + z % m] - cost[z]);
        }
        for (int i = 0; i < n; ++i) out.dualCost += dual[i] / n;
        for (int j = 0; j < m; ++j) out.dualCost += dual[n + j] / m;
        if (!Double.isFinite(out.linearCost)
                || !Double.isFinite(out.dualCost)
                || !feasible(marginalErrors(out.correspondence, n, m), c.otTolerance * 16)
                || out.maximumReducedCostViolation > epsilon * 16
                || Math.abs(out.linearCost - out.dualCost) > epsilon * 32)
            throw new ArithmeticException(
                    "Exact OT failed original marginals/primal/dual optimality " + "certificate.");
        workspace.n = n;
        workspace.m = m;
        workspace.flow = flow;
        workspace.basic = basic;
        return out;
    }
}
