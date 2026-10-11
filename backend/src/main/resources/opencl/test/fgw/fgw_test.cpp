#include "fgw_engine.hpp"
#include "json.hpp"
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <queue>
#include <stdexcept>
#include <utility>

using namespace fgw;
namespace {
void require(bool condition, const char *message) {
    if (!condition)
        throw std::runtime_error(message);
}
void near(double value, double expected, const char *message) {
    require(std::isfinite(value) && std::abs(value - expected) <= 1e-8, message);
}
void matrix(const std::vector<double> &value, const std::vector<double> &expected, const char *name) {
    require(value.size() == expected.size(), name);
    for (std::size_t i = 0; i < value.size(); ++i)
        near(value[i], expected[i], name);
}
Graph graph(std::vector<int> offsets, std::vector<int> columns, std::vector<double> values,
            const std::string &instance) {
    Graph g;
    g.productId = "synthetic-product";
    g.instanceId = instance;
    g.rowOffsets = std::move(offsets);
    g.colIndices = std::move(columns);
    for (std::size_t i = 0; i < values.size(); ++i) {
        std::vector<double> embedding(embeddingDimension, 0);
        embedding[0] = values[i];
        g.embeddings.insert(g.embeddings.end(), embedding.begin(), embedding.end());
        NodeSource source;
        source.occurrenceId = instance + ":" + std::to_string(i);
        source.materialId = std::to_string(values[i]);
        source.quantity = "1.000000000000000001";
        source.amount = "9007199254740993.01";
        source.productionOrderId = instance;
        source.issueLineIds = {"issue:" + std::to_string(i)};
        source.fifoAllocationIds = {"allocation:" + std::to_string(i)};
        g.sources.push_back(source);
    }
    return g;
}
// Independent, deliberately direct tensor reference used only for small
// fixtures.
std::vector<double> distances(const Graph &graph) {
    int n = graph.nodeCount();
    std::vector<std::vector<int>> edges(n);
    for (int i = 0; i < n; ++i)
        for (int e = graph.rowOffsets[i]; e < graph.rowOffsets[i + 1]; ++e) {
            int j = graph.colIndices[e];
            edges[i].push_back(j);
            edges[j].push_back(i);
        }
    std::vector<double> d(static_cast<std::size_t>(n) * n, -1);
    for (int s = 0; s < n; ++s) {
        std::queue<int> q;
        q.push(s);
        d[s * n + s] = 0;
        while (!q.empty()) {
            int u = q.front();
            q.pop();
            for (int v : edges[u])
                if (d[s * n + v] < 0) {
                    d[s * n + v] = d[s * n + u] + 1;
                    q.push(v);
                }
        }
    }
    return d;
}
Costs tensorCosts(const Result &r, const std::vector<double> &p) {
    double material = 0, structure = 0;
    int n = r.rows, m = r.columns;
    for (int i = 0; i < n; ++i)
        for (int j = 0; j < m; ++j) {
            material += r.materialDistances[i * m + j] * p[i * m + j];
            for (int k = 0; k < n; ++k)
                for (int l = 0; l < m; ++l) {
                    double d = r.leftDistances[i * n + k] - r.rightDistances[j * m + l];
                    structure += d * d * p[i * m + j] * p[k * m + l];
                }
        }
    return {material, structure, 0.2 * material + 0.8 * structure};
}
Result run(const Comparison &input, const Config &config) { return OpenClFgwEngine().compare(input, config); }
void validateFixed(const Result &r) {
    const double sixth = 1.0 / 6, third = 1.0 / 3;
    const std::vector<double> P0(6, sixth), S{third, sixth, 0, 0, sixth, third};
    matrix(r.materialDistances, {0, 1, 4, 4, 1, 0}, "material distances");
    matrix(r.leftDistances, {0, 1, 1, 0}, "D1");
    matrix(r.rightDistances, {0, 1, 2, 1, 0, 1, 2, 1, 0}, "D2");
    matrix(r.p, {.5, .5}, "p");
    matrix(r.q, {third, third, third}, "q");
    const auto &start = r.starts.at(0);
    require(start.trace.size() == 2, "fresh final gap trace");
    const auto &t0 = start.trace[0];
    const auto &t1 = start.trace[1];
    matrix(t0.correspondence, P0, "P0");
    near(t0.costs.material, 1.6666666667, "initial material cost");
    near(t0.costs.structure, .9444444444, "initial structure cost");
    near(t0.costs.fgw, 1.0888888889, "initial FGW cost");
    matrix(t0.gradient, {1.8666666667, 1, 2.6666666667, 2.6666666667, 1, 1.8666666667}, "G0");
    matrix(t0.ot.correspondence, S, "S0");
    near(t0.gap, .2666666667, "gap0");
    matrix(t0.direction, {sixth, 0, -sixth, -sixth, 0, sixth}, "delta");
    near(t0.step.gamma, 1, "gamma");
    matrix(t1.correspondence, S, "P1");
    near(t1.costs.material, .3333333333, "updated material");
    near(t1.costs.structure, .5, "updated structure");
    near(t1.costs.fgw, .4666666667, "updated FGW");
    matrix(t1.gradient, {.8, 1, 3.7333333333, 3.7333333333, 1, .8}, "G1");
    matrix(t1.ot.correspondence, S, "S1");
    near(t1.gap, 0, "final gap");
    matrix(start.correspondence, S, "final P");
    require(start.converged(), "convergence");
    for (const auto &t : start.trace) {
        require(t.marginals.finite && t.marginals.minimum >= -1e-8, "finite/nonnegative P");
        near(t.marginals.row, 0, "row marginals");
        near(t.marginals.column, 0, "column marginals");
        near(t.marginals.total, 0, "total marginals");
        auto e = algorithm::marginalErrors(t.ot.correspondence, 2, 3);
        require(e.finite && e.minimum >= 0, "finite/nonnegative S");
        near(e.row, 0, "S row");
        near(e.column, 0, "S column");
        near(t.ot.linearCost, t.ot.dualCost, "OT primal dual optimality");
        // Feasible centred 2x2 direction; the terminal sparse matrix uses a
        // one-sided derivative towards the interior to preserve nonnegativity.
        auto direction = t.direction;
        if (t.updates == 1)
            for (int k = 0; k < 6; ++k)
                direction[k] = P0[k] - t.correspondence[k];
        double predicted = 0;
        for (int k = 0; k < 6; ++k)
            predicted += t.gradient[k] * direction[k];
        auto plus = t.correspondence;
        const double epsilon = 1e-5;
        for (int k = 0; k < 6; ++k)
            plus[k] += epsilon * direction[k];
        if (t.updates == 0) {
            auto minus = t.correspondence;
            for (int k = 0; k < 6; ++k)
                minus[k] -= epsilon * direction[k];
            near((tensorCosts(r, plus).fgw - tensorCosts(r, minus).fgw) / (2 * epsilon), predicted,
                 "feasible gradient derivative");
        } else {
            auto plus2 = t.correspondence;
            for (int k = 0; k < 6; ++k)
                plus2[k] += 2 * epsilon * direction[k];
            near((-3 * t.costs.fgw + 4 * tensorCosts(r, plus).fgw - tensorCosts(r, plus2).fgw) /
                     (2 * epsilon),
                 predicted, "boundary gradient derivative");
        }
    }
    require(r.left.nodes[0].amount == "9007199254740993.01" &&
                r.left.nodes[0].quantity == "1.000000000000000001" &&
                r.right.nodes[1].fifoAllocationIds[0] == "allocation:1",
            "lossless source mapping");
}
} // namespace
int main(int argc, char **argv) {
    try {
        Config c;
        c.kernelPath = FGW_KERNEL_PATH;
        if (const char *platform = std::getenv("FGW_PLATFORM"))
            c.platform = std::stoi(platform);
        require(c.incrementalOtTree && c.candidateOtPricing, "combined simplex enabled by default");
        int simplexMode = (c.incrementalOtTree ? 1 : 0) | (c.candidateOtPricing ? 2 : 0);
        if (const char *mode = std::getenv("FGW_SIMPLEX_MODE")) {
            simplexMode = std::stoi(mode);
            c.incrementalOtTree = (simplexMode & 1) != 0;
            c.candidateOtPricing = (simplexMode & 2) != 0;
        }
        require(simplexMode >= 0 && simplexMode <= 3, "Simplex mode must be 0..3");
        if (argc == 3 && std::string(argv[1]) == "--ot") {
            std::ifstream input(argv[2]);
            int n, m;
            input >> n >> m;
            require(input.good() && n > 0 && m > 0, "OT fixture header");
            std::vector<double> cost(static_cast<std::size_t>(n) * m);
            for (auto &value : cost)
                input >> value;
            require(!input.fail(), "OT fixture costs");
            algorithm::LinearOtWorkspace workspace;
            auto device = algorithm::deviceOtFixture(cost, n, m, c);
            auto solved = device.first;
            auto changedCost = cost;
            for (auto &value : changedCost)
                value = -value;
            auto warm = device.second;
            std::cout << "{\"P\":";
            testjson::vector(std::cout, solved.correspondence);
            std::cout << ",\"linearCost\":";
            testjson::number(std::cout, solved.linearCost);
            std::cout << ",\"dualCost\":";
            testjson::number(std::cout, solved.dualCost);
            std::cout << ",\"dualViolation\":";
            testjson::number(std::cout, solved.maximumReducedCostViolation);
            std::cout << ",\"pivots\":" << solved.pivots
                      << ",\"degeneratePivots\":" << solved.degeneratePivots
                      << ",\"bland\":" << (solved.usedBlandRule ? "true" : "false") << ",\"warmP\":";
            testjson::vector(std::cout, warm.correspondence);
            std::cout << ",\"warmLinearCost\":";
            testjson::number(std::cout, warm.linearCost);
            std::cout << ",\"warmDualCost\":";
            testjson::number(std::cout, warm.dualCost);
            std::cout << ",\"warmDualViolation\":";
            testjson::number(std::cout, warm.maximumReducedCostViolation);
            std::cout << ",\"simplexMode\":" << simplexMode
                      << ",\"fullPricingScans\":" << solved.fullPricingScans
                      << ",\"candidatePricingPasses\":" << solved.candidatePricingPasses
                      << ",\"treeRebuilds\":" << solved.treeRebuilds
                      << ",\"incrementalTreeUpdates\":" << solved.incrementalTreeUpdates
                      << ",\"treeNodesVisited\":" << solved.treeNodesVisited
                      << ",\"cycleEdges\":" << solved.cycleEdges << ",\"warmPivots\":" << warm.pivots
                      << ",\"warmFullPricingScans\":" << warm.fullPricingScans
                      << ",\"warmCandidatePricingPasses\":" << warm.candidatePricingPasses
                      << ",\"warmTreeRebuilds\":" << warm.treeRebuilds
                      << ",\"warmIncrementalTreeUpdates\":" << warm.incrementalTreeUpdates
                      << ",\"warmTreeNodesVisited\":" << warm.treeNodesVisited;
            std::cout << "}\n";
            return 0;
        }
        for (int field = 0; field < 2; ++field) {
            auto invalid = c;
            if (field == 0)
                invalid.gpuSlots = 0;
            else
                invalid.dispatcherThreads = 0;
            bool rejected = false;
            try {
                algorithm::validateConfig(invalid);
            } catch (const std::invalid_argument &) {
                rejected = true;
            }
            require(rejected, "invalid slot/dispatcher configuration rejected");
        }
        c.captureTrace = true;
        if (const char *device = std::getenv("FGW_DEVICE"))
            c.device = std::stoi(device);
        Comparison fixed{
            graph({0, 1, 1}, {1}, {0, 2}, "normal"), graph({0, 1, 2, 2}, {1, 2}, {0, 1, 2}, "actual"), {}};
        auto r = run(fixed, c);
        validateFixed(r);
        auto unequalStarts = fixed;
        unequalStarts.initialMatrices = {std::vector<double>(6, 1.0 / 6),
                                         {1.0 / 3, 1.0 / 6, 0, 0, 1.0 / 6, 1.0 / 3}};
        auto unequalResult = run(unequalStarts, c);
        validateFixed(unequalResult);
        require(unequalResult.starts.size() == 2 && unequalResult.starts[1].converged() &&
                    unequalResult.starts[1].iterations == 0,
                "unequal-size feasible multi-start");
        near(unequalResult.starts[1].costs.fgw, .4666666667, "unequal-size feasible start cost");
        Comparison ordering{graph({0, 2, 2, 2}, {1, 2}, {0, 1, 2}, "siblings-left"),
                            graph({0, 2, 2, 2}, {1, 2}, {0, 2, 1}, "siblings-right"),
                            {}};
        ordering.initialMatrices = {std::vector<double>(9, 1.0 / 9),
                                    {1.0 / 3, 0, 0, 0, 0, 1.0 / 3, 0, 1.0 / 3, 0}};
        auto ordered = run(ordering, c);
        near(ordered.starts[ordered.bestStart].costs.fgw, 0, "node-ordering invariance");
        auto swapped = ordering;
        std::swap(swapped.left, swapped.right);
        auto swappedResult = run(swapped, c);
        near(swappedResult.starts[swappedResult.bestStart].costs.fgw, 0, "reverse ordering invariance");
        // Required stopping distinctions, always evaluated at the returned matrix.
        auto limit = c;
        limit.maxIterations = 0;
        auto limited = run(fixed, limit);
        require(limited.starts[0].status == Status::MAX_ITERATIONS && !limited.starts[0].converged(),
                "iteration limit is not convergence");
        auto finalUpdate = c;
        finalUpdate.maxIterations = 1;
        auto afterOne = run(fixed, finalUpdate);
        require(afterOne.starts[0].converged(), "fresh gap after final allowed update");
        auto stall = c;
        stall.tolAbs = 100;
        stall.tolGapAbs = 0;
        stall.tolGapRel = 0;
        require(algorithm::stoppingStatus(.1, {1, 1, 1}, {}, 1, 1.01, true, stall) == Status::COST_STALLED,
                "cost stall is not gap convergence");
        require(algorithm::stoppingStatus(-.1, {1, 1, 1}, {}, 1, 1, false, c) == Status::NUMERICAL_FAILURE,
                "negative gap failure");
        near(algorithm::quadraticLineSearch(-1, -.8, .8, 1e-10).gamma, .25, "convex line stationary point");
        near(algorithm::quadraticLineSearch(0, -1, .8, 1e-10).gamma, 1, "zero curvature endpoint");
        // Invalid data must fail before device execution.
        for (int caseIndex = 0; caseIndex < 5; ++caseIndex) {
            auto bad = fixed;
            if (caseIndex == 0)
                bad.left.embeddings.pop_back();
            if (caseIndex == 1)
                bad.left.colIndices[0] = 2;
            if (caseIndex == 2) {
                bad.left.colIndices.clear();
                bad.left.rowOffsets = {0, 0, 0};
            }
            if (caseIndex == 3)
                bad.initialMatrices = {{0, 0, 0, 0, 0, 0}};
            if (caseIndex == 4)
                bad.left.sources.pop_back();
            bool rejected = false;
            try {
                algorithm::validateComparison(bad, c);
            } catch (const std::invalid_argument &) {
                rejected = true;
            }
            require(rejected, "invalid input rejected");
        }
        auto deviceConfig = c;
        deviceConfig.captureTrace = false;
        auto untraced = run(fixed, deviceConfig);
        require(untraced.otExecution == "OPENCL_DEVICE_EXACT_SIMPLEX", "actual device simplex required");
        near(untraced.starts[0].costs.fgw, r.starts[0].costs.fgw, "device/tracing agreement");
        require(untraced.starts[0].trace[0].ot.fullPricingScans > 0, "full-scan certification");
        auto direct = algorithm::deviceOtFixture({3, -2, 5, 1, 0, 7, -3, 4, -4, 2, 1, 0}, 3, 4, deviceConfig);
        require(direct.second.pivots > 0, "changed-cost warm solve exercises pivots");
        require(direct.second.fullPricingScans > 0, "warm solve full-scan certification");
        if (deviceConfig.incrementalOtTree)
            require(direct.second.treeRebuilds == 1 &&
                        direct.second.incrementalTreeUpdates == direct.second.pivots,
                    "incremental tree counters");
        if (deviceConfig.candidateOtPricing)
            require(direct.second.candidatePricingPasses > 0, "candidate repricing counters");
        auto poolConfig = deviceConfig;
        poolConfig.maxConcurrentComparisons = 2;
        poolConfig.queueCapacity = 1;
        OpenClFgwSession session(fixed.left, poolConfig);
        std::vector<std::future<Result>> futures;
        for (int i = 0; i < 8; ++i)
            futures.push_back(session.submit(fixed.right));
        for (auto &future : futures)
            near(future.get().starts[0].costs.fgw, .4666666667, "pooled device result");
        // Let accounting follow the fulfilled promise before observing the
        // snapshot.
        auto reused = session.submit(fixed.right).get();
        auto before = session.statistics();
        auto next = session.submit(fixed.right).get();
        auto bad = fixed.right;
        bad.embeddings.pop_back();
        bool rejected = false;
        try {
            session.submit(bad).get();
        } catch (const std::invalid_argument &) {
            rejected = true;
        }
        require(rejected, "target error arrives through future");
        near(session.submit(fixed.right).get().starts[0].costs.fgw, .4666666667, "error recovery");
        auto multiple = session.submit(fixed.right, unequalStarts.initialMatrices).get();
        require(multiple.starts.size() == 2 && multiple.starts[1].iterations == 0,
                "independent session starts");
        auto bigger = graph({0, 1, 2, 3, 3}, {1, 2, 3}, {0, 1, 2, 3}, "larger");
        require(session.submit(bigger).get().columns == 4, "capacity growth");
        auto overflow = fixed.right;
        overflow.embeddings[0] = 1e308;
        require(session.submit(overflow).get().starts[0].status == Status::NUMERICAL_FAILURE,
                "numerical failure visible");
        near(session.submit(fixed.right).get().starts[0].costs.fgw, .4666666667,
             "numerical failure recovery");
        auto warmStats = session.statistics();
        session.submit(fixed.right).get();
        session.submit(fixed.right).get();
        require(session.statistics().workerDeviceAllocations == warmStats.workerDeviceAllocations,
                "no allocations after capacity warm-up");
        auto drained = session.submit(fixed.right);
        session.close();
        drained.get();
        auto stats = session.statistics();
        require(stats.baselinePreparations == 1 && stats.maximumQueued <= 1 && stats.workersCreated == 2,
                "persistent baseline/queue bounds");
        require(stats.accepted == stats.completed + stats.failed, "shutdown draining accounting");
        auto cpuConfig = deviceConfig;
        cpuConfig.otSolver = LinearOtSolver::CPU_REFERENCE;
        require(run(fixed, cpuConfig).otExecution.find("EXACT_CPU_OT_FALLBACK") == 0,
                "explicit CPU fallback reported");
        Graph oversized;
        oversized.productId = fixed.left.productId;
        oversized.instanceId = "oversized";
        oversized.rowOffsets.push_back(0);
        for (int i = 0; i < 319; ++i) {
            if (i + 1 < 319)
                oversized.colIndices.push_back(i + 1);
            oversized.rowOffsets.push_back(oversized.colIndices.size());
            oversized.embeddings.insert(oversized.embeddings.end(), 384, 0);
            NodeSource source;
            source.occurrenceId = std::to_string(i);
            oversized.sources.push_back(source);
        }
        auto fallback = OpenClFgwEngine().compare({fixed.left, oversized, {}}, deviceConfig);
        require(fallback.otExecution == "EXACT_CPU_OT_FALLBACK: N+M exceeds 320",
                "oversized fallback explicitly reported");
        require(fallback.starts[0].status != Status::NUMERICAL_FAILURE, "oversized exact CPU OT result");
        std::ostream *out = &std::cout;
        std::ofstream file;
        if (argc > 1) {
            file.open(argv[1]);
            require(file.good(), "report file");
            out = &file;
        }
        *out << "{\"execution\":\"OPENCL\",\"fixed\":";
        testjson::result(*out, r);
        *out << ",\"unequalMultiStart\":";
        testjson::result(*out, unequalResult);
        *out << ",\"ordering\":";
        testjson::result(*out, ordered);
        *out << ",\"orderingReverse\":";
        testjson::result(*out, swappedResult);
        *out << ",\"deviceFixed\":";
        testjson::result(*out, untraced);
        *out << ",\"sessionChecks\":\"PASS\",\"sessionStatistics\":{"
                "\"baselinePreparations\":"
             << stats.baselinePreparations << ",\"queuesCreated\":" << stats.gpuSlotsCreated
             << ",\"maximumQueued\":" << stats.maximumQueued << ",\"accepted\":" << stats.accepted
             << ",\"completed\":" << stats.completed << ",\"failed\":" << stats.failed << "}";
        *out << ",\"nativeChecks\":\"PASS\"}\n";
        std::cerr << "PASS: fixed matrices, gradients, OT certificates, marginals, "
                     "line search, stopping, "
                     "ordering and mapping\n";
        return 0;
    } catch (const std::exception &e) {
        std::cerr << "FAIL: " << e.what() << '\n';
        return 1;
    }
}
