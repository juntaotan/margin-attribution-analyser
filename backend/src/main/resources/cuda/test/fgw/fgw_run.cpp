#include "fgw_engine.cuh"
#include "json.hpp"
#include <algorithm>
#include <chrono>
#include <cuda_runtime.h>
#include <deque>
#include <fstream>
#include <iostream>
#include <stdexcept>

// Portable numerical/benchmark input: two graphs, each n, edge count, offsets,
// columns, then n*384 embedding values. Metadata below labels synthetic inputs.
fgw::Graph readGraph(std::istream &in, int index) {
    int n, edges;
    if (!(in >> n >> edges) || n < 1 || edges < 0)
        throw std::runtime_error("Invalid FGW fixture header.");
    fgw::Graph g;
    g.productId = "benchmark-product";
    g.instanceId = "synthetic-" + std::to_string(index);
    g.rowOffsets.resize(n + 1);
    g.colIndices.resize(edges);
    g.embeddings.resize(static_cast<std::size_t>(n) * 384);
    g.sources.resize(n);
    for (auto &x : g.rowOffsets)
        in >> x;
    for (auto &x : g.colIndices)
        in >> x;
    for (auto &x : g.embeddings)
        in >> x;
    for (int i = 0; i < n; ++i)
        g.sources[i].occurrenceId = g.instanceId + ":" + std::to_string(i);
    if (!in)
        throw std::runtime_error("Truncated FGW fixture.");
    return g;
}
namespace {
using Clock = std::chrono::steady_clock;
double milliseconds(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}
int batch(int argc, char **argv) {
    if (argc != 9)
        throw std::invalid_argument(
            "Usage: fgw_cuda_run --batch fixture requested workers device warmup maxSeconds output");
    const auto requested = std::stoull(argv[3]);
    const int workers = std::stoi(argv[4]), device = std::stoi(argv[5]);
    const auto warmup = std::stoull(argv[6]);
    const double maxSeconds = std::stod(argv[7]);
    if (!requested || workers < 1 || !std::isfinite(maxSeconds) || maxSeconds < 0)
        throw std::invalid_argument("Invalid benchmark count, workers or time limit.");
    std::ifstream input(argv[2]);
    auto baseline = readGraph(input, 0);
    int poolSize = 0;
    input >> poolSize;
    if (poolSize < 1 || poolSize > 1024)
        throw std::invalid_argument("Target pool size must be in [1,1024].");
    std::vector<fgw::Graph> targets;
    for (int i = 0; i < poolSize; ++i)
        targets.push_back(readGraph(input, i + 1));
    fgw::Config config;
    config.device = device;
    config.maxConcurrentComparisons = workers;
    config.queueCapacity = 2 * static_cast<std::size_t>(workers);
    config.profileExecution = true;
    const auto totalBegin = Clock::now();
    std::size_t freeBytes = 0, totalBytes = 0;
    cudaSetDevice(device);
    if (cudaMemGetInfo(&freeBytes, &totalBytes) != cudaSuccess)
        throw std::runtime_error("Cannot query CUDA memory.");
    std::cerr << "CUDA memory free/total bytes " << freeBytes << '/' << totalBytes << '\n';
    std::vector<fgw::Result> reference(poolSize);
    struct Pending {
        std::size_t target;
        std::future<fgw::Result> future;
    };
    std::deque<Pending> pending;
    std::size_t completed = 0;
    fgw::ExecutionTimes times;
    double serviceMilliseconds = 0, maximumMarginalError = 0;
    double initializationMilliseconds = 0, warmupMilliseconds = 0, steadyMilliseconds = 0,
           totalMilliseconds = 0;
    fgw::SessionStatistics warmed, final;
    Clock::time_point destructionBegin;
    {
        fgw::CudaFgwSession session(std::move(baseline), config);
        initializationMilliseconds = milliseconds(totalBegin);
        const auto warmBegin = Clock::now();
        // Every target is first solved in the same session. Warm-up is never a cached answer.
        for (int i = 0; i < poolSize; ++i)
            reference[i] = session.submit(targets[i]).get();
        for (std::size_t i = 0; i < warmup; ++i) {
            pending.push_back({i % poolSize, session.submit(targets[i % poolSize])});
            if (pending.size() >= 2 * static_cast<std::size_t>(workers)) {
                auto value = pending.front().future.get();
                if (value.starts[value.bestStart].status == fgw::Status::NUMERICAL_FAILURE)
                    throw std::runtime_error("Warm-up numerical failure.");
                pending.pop_front();
            }
        }
        while (!pending.empty()) {
            pending.front().future.get();
            pending.pop_front();
        }
        warmupMilliseconds = milliseconds(warmBegin);
        warmed = session.statistics();
        const auto steadyBegin = Clock::now();
        auto consume = [&] {
            auto item = std::move(pending.front());
            pending.pop_front();
            auto result = item.future.get();
            const auto &actual = result.starts[result.bestStart];
            const auto &expected = reference[item.target].starts[reference[item.target].bestStart];
            auto marginals =
                fgw::algorithm::marginalErrors(actual.correspondence, result.rows, result.columns);
            maximumMarginalError =
                std::max({maximumMarginalError, marginals.row, marginals.column, marginals.total});
            if (!marginals.finite || marginals.minimum < -1e-10 || maximumMarginalError > 1e-8 ||
                actual.status == fgw::Status::NUMERICAL_FAILURE || actual.status != expected.status ||
                actual.iterations != expected.iterations || !std::isfinite(actual.costs.fgw) ||
                !std::isfinite(actual.finalGap) || std::abs(actual.costs.fgw - expected.costs.fgw) > 1e-8 ||
                std::abs(actual.finalGap - expected.finalGap) > 1e-8)
                throw std::runtime_error("Repeated comparison failed status/cost/gap/marginal validation.");
            ++completed;
            serviceMilliseconds += result.totalMilliseconds;
            times.cpuOtMilliseconds += result.executionTimes.cpuOtMilliseconds;
            times.deviceMilliseconds += result.executionTimes.deviceMilliseconds;
            times.synchronizationMilliseconds += result.executionTimes.synchronizationMilliseconds;
        };
        for (std::size_t i = 0; i < requested; ++i) {
            if (i && maxSeconds > 0 && milliseconds(steadyBegin) >= maxSeconds * 1000)
                break;
            pending.push_back({i % poolSize, session.submit(targets[i % poolSize])});
            if (pending.size() >= 2 * static_cast<std::size_t>(workers))
                consume();
            if (completed && completed % 1000 == 0)
                std::cerr << "CUDA completed " << completed << '/' << requested << '\n';
        }
        session.close();
        while (!pending.empty())
            consume();
        steadyMilliseconds = milliseconds(steadyBegin);
        final = session.statistics();
        destructionBegin = Clock::now();
    }
    const double destructionMilliseconds = milliseconds(destructionBegin);
    totalMilliseconds = milliseconds(totalBegin);
    std::ofstream out(argv[8]);
    if (!out)
        throw std::runtime_error("Cannot write benchmark report.");
    out << std::setprecision(17) << "{\"execution\":\"CUDA\",\"requested\":" << requested
        << ",\"completed\":" << completed << ",\"status\":\""
        << (completed == requested ? "PASS" : "INCOMPLETE_TIME_LIMIT") << "\",\"workers\":" << workers
        << ",\"poolSize\":" << poolSize << ",\"initializationMilliseconds\":" << initializationMilliseconds
        << ",\"destructionMilliseconds\":" << destructionMilliseconds
        << ",\"warmupMilliseconds\":" << warmupMilliseconds
        << ",\"steadyMilliseconds\":" << steadyMilliseconds
        << ",\"totalIncludingWarmupAndDestructionMilliseconds\":" << totalMilliseconds
        << ",\"comparisonsPerSecond\":" << completed * 1000.0 / steadyMilliseconds
        << ",\"meanServiceMilliseconds\":" << serviceMilliseconds / completed
        << ",\"meanCpuOtMilliseconds\":" << times.cpuOtMilliseconds / completed
        << ",\"meanDeviceIntervalMilliseconds\":" << times.deviceMilliseconds / completed
        << ",\"meanSynchronizationMilliseconds\":" << times.synchronizationMilliseconds / completed
        << ",\"maximumMarginalError\":" << maximumMarginalError
        << ",\"resources\":{\"baselinePreparations\":" << final.baselinePreparations
        << ",\"workersCreated\":" << final.workersCreated << ",\"maximumQueued\":" << final.maximumQueued
        << ",\"workerDeviceBytes\":" << final.workerDeviceBytes
        << ",\"allocationsAfterWarmup\":" << final.workerDeviceAllocations - warmed.workerDeviceAllocations
        << "},\"reference\":[";
    for (std::size_t i = 0; i < reference.size(); ++i) {
        if (i)
            out << ',';
        const auto &value = reference[i];
        const auto &selected = value.starts[value.bestStart];
        out << "{\"rows\":" << value.rows << ",\"columns\":" << value.columns
            << ",\"bestStart\":0,\"starts\":[{\"status\":";
        testjson::string(out, fgw::statusName(selected.status));
        out << ",\"iterations\":" << selected.iterations << ",\"gap\":";
        testjson::number(out, selected.finalGap);
        out << ",\"costs\":";
        testjson::costs(out, selected.costs);
        out << ",\"iterationCosts\":[";
        for (std::size_t k = 0; k < selected.trace.size(); ++k) {
            if (k)
                out << ',';
            testjson::costs(out, selected.trace[k].costs);
        }
        out << "]}]}";
    }
    out << "],\"deviceFreeBytesBeforeInitialization\":" << freeBytes << ",\"deviceTotalBytes\":" << totalBytes
        << "}\n";
    std::cout << (completed == requested ? "PASS" : "INCOMPLETE") << " CUDA " << completed << '/' << requested
              << " comparisons, " << completed * 1000.0 / steadyMilliseconds << "/s\n";
    return completed == requested ? 0 : 2;
}
} // namespace

int main(int argc, char **argv) {
    int count = 0;
    auto error = cudaGetDeviceCount(&count);
    if (error != cudaSuccess || count == 0) {
        std::cerr << "UNVERIFIED CUDA: " << cudaGetErrorString(error) << '\n';
        return 77;
    }
    try {
        if (argc > 1 && std::string(argv[1]) == "--batch")
            return batch(argc, argv);
        if (argc < 2)
            throw std::runtime_error("Usage: fgw_cuda_run fixture [device] [trace:0|1]");
        std::ifstream in(argv[1]);
        fgw::Comparison comparison;
        comparison.left = readGraph(in, 0);
        comparison.right = readGraph(in, 1);
        fgw::Config config;
        if (argc > 2)
            config.device = std::stoi(argv[2]);
        config.captureTrace = argc > 3 && std::stoi(argv[3]) != 0;
        const auto begin = std::chrono::steady_clock::now();
        auto result = fgw::CudaFgwEngine().compare(comparison, config);
        // The wall-clock interval includes engine-owned resource destruction.
        result.totalMilliseconds =
            std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - begin).count();
        testjson::result(std::cout, result);
        std::cout << '\n';
        return result.starts[result.bestStart].status == fgw::Status::NUMERICAL_FAILURE ? 1 : 0;
    } catch (const std::exception &e) {
        std::cerr << "FAIL: " << e.what() << '\n';
        return 1;
    }
}
