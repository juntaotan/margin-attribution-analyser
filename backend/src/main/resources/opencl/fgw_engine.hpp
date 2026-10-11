#pragma once

#include <cstddef>
#include <cstdint>
#include <future>
#include <memory>
#include <string>
#include <utility>
#include <vector>

/** Native FP64 balanced FGW interfaces. All dense matrices use row-major order.
 */
namespace fgw {
constexpr int embeddingDimension = 384;

/** Caller-restored metadata, copied verbatim. Decimal text avoids monetary
 * rounding. */
struct NodeSource {
    std::string occurrenceId;
    std::string materialId;
    std::string productionOrderId;
    std::string quantity;
    std::string amount;
    std::vector<std::string> issueLineIds;
    std::vector<std::string> fifoAllocationIds;
};

/**
 * Complete connected CSR tree/graph, with one own-content embedding per node.
 * Edges are unweighted and interpreted as undirected. Node sources and
 * embeddings must follow CSR order. Product identity must agree across a
 * comparison; instance identity and root index identify the original tree
 * without merging occurrences.
 */
struct Graph {
    std::string productId;
    std::string instanceId;
    int rootIndex = 0;
    std::vector<int> rowOffsets;
    std::vector<int> colIndices;
    std::vector<double> embeddings;
    std::vector<NodeSource> sources;
    int nodeCount() const;
};

enum class LinearOtSolver { GPU_SIMPLEX, CPU_REFERENCE };

struct Config {
    int platform = 0;
    std::string kernelPath = "fgw.cl";
    double alpha = 0.8;
    double tolAbs = 1e-6;
    double tolRel = 1e-5;
    int maxIterations = 200;
    double tolGapAbs = 1e-6;
    double tolGapRel = 1e-5;
    double costScaleFloor = 1e-12;
    double feasibilityTolerance = 1e-9;
    double numericalTolerance = 1e-10;
    double otTolerance = 1e-12;
    int device = 0;
    int maxConcurrentComparisons = 1;
    bool captureTrace = false;
    std::size_t queueCapacity = 0; // Zero selects twice the effective slot count; limits pending inputs.
    std::size_t workspaceBytes =
        4 * 1024 * 1024;           // Retained configuration compatibility; no BLAS workspace required.
    bool profileExecution = false; // Collect kernel event durations and host wait intervals.
    LinearOtSolver otSolver = LinearOtSolver::GPU_SIMPLEX;
    int gpuSlots = 32;         // Queue count is min(gpuSlots, maxConcurrentComparisons,
                               // dispatcherThreads).
    int dispatcherThreads = 2; // One persistent queue per dispatcher, capped by
                               // gpuSlots and comparison limit.
    // Combined exact-OT optimisation is the default. CPU/tracing fallbacks ignore
    // these controls.
    bool incrementalOtTree = true;
    bool candidateOtPricing = true;
};

enum class Status { RUNNING, FW_GAP_REACHED, COST_STALLED, MAX_ITERATIONS, NUMERICAL_FAILURE };
const char *statusName(Status status);

struct Costs {
    double material = 0, structure = 0, fgw = 0;
};
struct MarginalErrors {
    double row = 0, column = 0, total = 0, minimum = 0;
    bool finite = true;
};
struct LinearOtResult {
    std::vector<double> correspondence;
    double linearCost = 0;
    double dualCost = 0;
    double maximumReducedCostViolation = 0;
    std::size_t pivots = 0, degeneratePivots = 0;
    bool usedBlandRule = false;
    // Device simplex work counters; zero for the CPU reference.
    std::size_t fullPricingScans = 0, candidatePricingPasses = 0, treeRebuilds = 0;
    std::size_t incrementalTreeUpdates = 0, treeNodesVisited = 0, cycleEdges = 0;
};
struct LineSearch {
    double a = 0, b = 0, gamma = 0;
};

/** State at a freshly evaluated FW gap; terminal states have no applied step.
 */
struct Iteration {
    int updates = 0;
    Costs costs;
    double gap = 0;
    double absoluteCostChange = 0;
    double relativeCostChange = 0;
    MarginalErrors marginals;
    LinearOtResult ot;
    LineSearch step;
    std::vector<double> correspondence, gradient, direction;
};

struct StartResult {
    std::vector<double> correspondence;
    Costs costs;
    double finalGap = 0;
    int iterations = 0; // Number of applied updates, excluding terminal gap checks.
    Status status = Status::NUMERICAL_FAILURE;
    std::string diagnostic;
    std::vector<Iteration> trace;
    bool converged() const;
};
struct SourceMapping {
    std::string productId, instanceId;
    int rootIndex = 0;
    std::vector<NodeSource> nodes;
};
/** Timings overlap: host synchronisation waits for device work, and worker
 * times may overlap across comparisons. They must not be summed into batch wall
 * time. */
struct ExecutionTimes {
    double cpuOtMilliseconds = 0;
    double gpuOtMilliseconds = 0;
    double deviceMilliseconds = 0;
    double synchronizationMilliseconds = 0;
};
struct Result {
    std::string otExecution; // Explicit exact device simplex or CPU-OT fallback reason.
    int rows = 0, columns = 0;
    SourceMapping left, right;
    std::vector<double> p, q, materialDistances, leftDistances, rightDistances;
    std::vector<StartResult> starts;
    std::size_t bestStart = 0;
    double totalMilliseconds = 0; // Worker service time, excluding queue wait and
                                  // session initialisation.
    ExecutionTimes executionTimes;
};
struct Comparison {
    Graph left, right;
    // Empty means exactly one uniform outer-product start. Otherwise every entry
    // is a caller-supplied feasible [N,M] matrix; no implicit start is added.
    std::vector<std::vector<double>> initialMatrices;
};

/** Resource and queue accounting for validation/monitoring, never objective
 * terms. */
struct SessionStatistics {
    std::size_t accepted = 0, completed = 0, failed = 0;
    std::size_t workersCreated = 0,
                baselinePreparations = 0; // workersCreated counts host dispatchers.
    std::size_t workerDeviceAllocations = 0, workerDeviceBytes = 0;
    std::size_t maximumQueued = 0;
    std::size_t gpuSlotsCreated = 0, maximumInFlight = 0;
};

/** Persistent fixed-baseline OpenCL session. Preparation is performed once.
 * Bounded submissions apply backpressure. Each worker owns one persistent
 * command queue and capacity-reused buffers; workers never share mutable
 * kernels. close() drains accepted work, and destruction joins workers before
 * releasing device resources. Target errors arrive through futures. */
class OpenClFgwSession {
  public:
    explicit OpenClFgwSession(Graph baseline, const Config &config = {});
    ~OpenClFgwSession();
    OpenClFgwSession(const OpenClFgwSession &) = delete;
    OpenClFgwSession &operator=(const OpenClFgwSession &) = delete;
    std::future<Result> submit(Graph target, std::vector<std::vector<double>> initialMatrices = {});
    void close();
    /** Consistent snapshot; completed counts include numerical-failure results.
     */
    SessionStatistics statistics() const;

  private:
    friend class OpenClFgwEngine;
    struct Impl;
    std::unique_ptr<Impl> impl;
};

/** Convenience pair interfaces. Invalid input throws std::invalid_argument;
 * OpenCL resource errors throw std::runtime_error. Numerical optimisation
 * failures return NUMERICAL_FAILURE. Source decimal text is preserved verbatim.
 */
class OpenClFgwEngine {
  public:
    /** Compare one pair. For repeated fixed-baseline work use OpenClFgwSession to
     * retain resources across calls. No embedding or FIFO data is inferred. */
    Result compare(const Comparison &input, const Config &config = {}) const;
    /** Compare arbitrary pairs in input order using a fixed thread/resource pool.
     * Pair sizes may differ. This materialised convenience API returns all
     * results; use the streaming session for millions of fixed-baseline
     * comparisons. */
    std::vector<Result> compareBatch(const std::vector<Comparison> &inputs, const Config &config = {}) const;
};

// Exact host mathematics used only for explicitly reported CPU-OT fallback.
namespace algorithm {
/** Direct device OT validation hook. Cold/warm solves retain the same basis;
 * throws when the device cannot run the exact simplex. No CPU substitution. */
std::pair<LinearOtResult, LinearOtResult> deviceOtFixture(const std::vector<double> &cost, int n, int m,
                                                          const Config &config);
struct UndirectedCsr {
    std::vector<int> offsets, columns;
};
// Mathematical product order and row-major/column-major views. The engine only
// binds buffers and invokes the library with this algorithm-owned schedule.
enum class ProductBuffer { Input, LeftDistances, RightDistances, Temporary, Output };
struct ProductOperation {
    ProductBuffer left, right, output;
    int rows, columns, inner, leftLeading, rightLeading, outputLeading;
};
std::vector<ProductOperation> matrixProductPlan(int n, int m);
UndirectedCsr validateGraph(const Graph &graph);
void validateConfig(const Config &config);
void validateComparison(const Comparison &input, const Config &config);
MarginalErrors marginalErrors(const std::vector<double> &matrix, int n, int m);
void validateInitial(const std::vector<double> &matrix, int n, int m, const Config &config);
/** Per-comparison/start feasible basis cache. Algorithm-owned contents; callers
 * must not edit its fields. Reuse across FW iterations avoids rebuilding an
 * arbitrary starting basis; every call still solves and certifies the exact LP.
 * Dimensions changing resets the cache. Do not carry it between comparisons. */
struct LinearOtWorkspace {
    int rows = 0, columns = 0;
    std::vector<std::int64_t> flow;
    std::vector<std::size_t> basis;
};
/** Exact transportation-simplex balanced LP with a primal/dual certificate.
 * Accepts finite [N,M] costs (including negative costs), fixed uniform
 * marginals; throws on infeasibility or failed primal/dual optimality to
 * numerical tolerance.
 */
LinearOtResult exactLinearOt(const std::vector<double> &cost, int n, int m, double tolerance,
                             LinearOtWorkspace *workspace = nullptr);
Costs combineCosts(double material, double structure, double alpha);
double fwGap(double directionalGradient);
/** Select the exact quadratic minimum on [0,1]. directionProduct is
 * <D1*delta*transpose(D2),delta>; directionalGradient is <G,delta>.
 * Handles zero curvature explicitly; rejects nonfinite coefficients. */
LineSearch quadraticLineSearch(double directionProduct, double directionalGradient, double alpha,
                               double numericalTolerance);
/** Decide stopping using the freshly computed gap, original marginals and
 * finite costs. Gap success precedes the update limit and cost stalling;
 * significantly negative gaps are numerical failures, never convergence. */
Status stoppingStatus(double gap, const Costs &costs, const MarginalErrors &marginals, int updates,
                      double previousCost, bool hasPrevious, const Config &config);
bool objectiveIncreased(double previous, double current, const Config &config);
std::size_t selectBestStart(const std::vector<StartResult> &starts);
/** Check an exact OT primal/dual certificate and original uniform marginals.
 * costEpsilon is the solver tolerance scaled by the maximum absolute arc cost.
 * Invalid or nonfinite certificates return false rather than certifying a gap.
 */
bool linearOtCertified(const LinearOtResult &result, const MarginalErrors &marginals, double costEpsilon,
                       double tolerance);

/** Per-start device-to-host iteration summary. Scalar indices are shared by
 * computational kernels and execution management; all entries use FP64. */
enum SummaryIndex {
    MaterialCost,
    StructureCost,
    DirectionalGradient,
    DirectionProduct,
    RowError,
    ColumnError,
    TotalError,
    MinimumWeight,
    FiniteWeights,
    CandidateRowError,
    CandidateColumnError,
    CandidateTotalError,
    CandidateMinimumWeight,
    CandidateFiniteWeights,
    LinearCost,
    DualCost,
    ReducedCostViolation,
    OtSuccess,
    OtCostEpsilon,
    OtFullPricingScans,
    OtCandidatePricingPasses,
    OtTreeRebuilds,
    OtIncrementalTreeUpdates,
    OtTreeNodesVisited,
    OtCycleEdges,
    SummaryCount
};
} // namespace algorithm

} // namespace fgw
