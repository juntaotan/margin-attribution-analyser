#include "fgw_engine.cuh"
#include <cuda_runtime.h>
#include <stdexcept>
#include <utility>

namespace {
void check(cudaError_t status) {
    if (status != cudaSuccess)
        throw std::runtime_error(cudaGetErrorString(status));
}
struct Storage {
    cudaStream_t stream{};
    double *cost = nullptr, *candidate = nullptr, *summary = nullptr;
    int *flow = nullptr, *pivots = nullptr;
    bool *basic = nullptr;
    ~Storage() {
        if (stream)
            cudaStreamSynchronize(stream);
        cudaFree(cost);
        cudaFree(candidate);
        cudaFree(summary);
        cudaFree(flow);
        cudaFree(pivots);
        cudaFree(basic);
        if (stream)
            cudaStreamDestroy(stream);
    }
};
} // namespace

/** Test-only direct device LP regression, including changed-cost warm starts.
 * This bypasses the FGW tracing fallback so GPU exact OT is tested separately. */
std::pair<fgw::LinearOtResult, fgw::LinearOtResult> fgwGpuOtFixture(const std::vector<double> &cost, int n,
                                                                    int m, int mode) {
    using namespace fgw;
    using namespace fgw::algorithm;
    if (n < 1 || m < 1 || n + m > 320 || cost.size() != static_cast<std::size_t>(n) * m)
        throw std::invalid_argument("GPU OT fixture dimensions exceed the supported limit.");
    Storage storage;
    check(cudaStreamCreateWithFlags(&storage.stream, cudaStreamNonBlocking));
    auto bytes = cost.size() * sizeof(double);
    check(cudaMalloc(&storage.cost, bytes));
    check(cudaMalloc(&storage.candidate, bytes));
    check(cudaMalloc(&storage.summary, SummaryCount * sizeof(double)));
    check(cudaMalloc(&storage.flow, cost.size() * sizeof(int)));
    check(cudaMalloc(&storage.basic, cost.size() * sizeof(bool)));
    check(cudaMalloc(&storage.pivots, sizeof(int)));
    auto solve = [&](const std::vector<double> &values, bool warm) {
        check(cudaMemcpyAsync(storage.cost, values.data(), bytes, cudaMemcpyHostToDevice, storage.stream));
        check(cudaMemsetAsync(storage.summary, 0, SummaryCount * sizeof(double), storage.stream));
        transportationSimplexKernel<<<1, 256, 0, storage.stream>>>(storage.cost, storage.flow, storage.basic,
                                                                   storage.candidate, storage.pivots, n, m,
                                                                   1e-12, warm, cost.size(), storage.summary,
                                                                   (mode & 1) != 0, (mode & 2) != 0);
        check(cudaGetLastError());
        LinearOtResult result;
        result.correspondence.resize(cost.size());
        double summary[SummaryCount]{};
        int pivots = 0;
        check(cudaMemcpyAsync(result.correspondence.data(), storage.candidate, bytes, cudaMemcpyDeviceToHost,
                              storage.stream));
        check(cudaMemcpyAsync(summary, storage.summary, sizeof(summary), cudaMemcpyDeviceToHost,
                              storage.stream));
        check(
            cudaMemcpyAsync(&pivots, storage.pivots, sizeof(pivots), cudaMemcpyDeviceToHost, storage.stream));
        check(cudaStreamSynchronize(storage.stream));
        result.linearCost = summary[LinearCost];
        result.dualCost = summary[DualCost];
        result.maximumReducedCostViolation = summary[ReducedCostViolation];
        result.pivots = pivots < 0 ? 0 : pivots;
        result.fullPricingScans = static_cast<std::size_t>(summary[OtFullPricingScans]);
        result.candidatePricingPasses = static_cast<std::size_t>(summary[OtCandidatePricingPasses]);
        result.treeRebuilds = static_cast<std::size_t>(summary[OtTreeRebuilds]);
        result.incrementalTreeUpdates = static_cast<std::size_t>(summary[OtIncrementalTreeUpdates]);
        result.treeNodesVisited = static_cast<std::size_t>(summary[OtTreeNodesVisited]);
        result.cycleEdges = static_cast<std::size_t>(summary[OtCycleEdges]);
        if (result.fullPricingScans < 1 ||
            ((mode & 1) && (result.treeRebuilds != 1 || result.incrementalTreeUpdates != result.pivots)) ||
            (!(mode & 1) && result.treeRebuilds != result.pivots + 1) ||
            (!(mode & 2) && (result.fullPricingScans != result.pivots + 1 || result.candidatePricingPasses)))
            throw std::runtime_error("GPU OT work counters do not match the selected experiment.");
        if (summary[OtSuccess] != 1 || !linearOtCertified(result, marginalErrors(result.correspondence, n, m),
                                                          summary[OtCostEpsilon], 1e-12))
            throw std::runtime_error("GPU OT fixture failed its primal/dual certificate.");
        return result;
    };
    auto cold = solve(cost, false);
    auto changed = cost;
    for (auto &value : changed)
        value = -value;
    return {std::move(cold), solve(changed, true)};
}
