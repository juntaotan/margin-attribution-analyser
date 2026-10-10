#include "fgw_engine.cuh"
#include <cublas_v2.h>
#include <cuda_runtime.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <deque>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <utility>

namespace fgw {
namespace {
using Clock = std::chrono::steady_clock;
double elapsed(Clock::time_point begin) {
    return std::chrono::duration<double, std::milli>(Clock::now() - begin).count();
}
void check(cudaError_t error) {
    if (error != cudaSuccess)
        throw std::runtime_error(std::string("FGW CUDA: ") + cudaGetErrorString(error));
}
void check(cublasStatus_t error) {
    if (error != CUBLAS_STATUS_SUCCESS)
        throw std::runtime_error("FGW cuBLAS status " + std::to_string(error));
}
struct Stream {
    cudaStream_t value{};
    Stream() { check(cudaStreamCreateWithFlags(&value, cudaStreamNonBlocking)); }
    ~Stream() {
        if (value) {
            cudaStreamSynchronize(value);
            cudaStreamDestroy(value);
        }
    }
};
struct Event {
    cudaEvent_t value{};
    explicit Event(unsigned flags = cudaEventDefault) { check(cudaEventCreateWithFlags(&value, flags)); }
    ~Event() {
        if (value)
            cudaEventDestroy(value);
    }
};
template <class T> struct Buffer {
    T *value = nullptr;
    std::size_t capacity = 0, allocations = 0;
    Buffer() = default;
    explicit Buffer(std::size_t count) { reserve(count); }
    void reserve(std::size_t count) {
        if (count <= capacity)
            return;
        if (count > std::numeric_limits<std::size_t>::max() / sizeof(T))
            throw std::invalid_argument("FGW allocation size overflow.");
        T *next = nullptr;
        check(cudaMalloc(reinterpret_cast<void **>(&next), count * sizeof(T)));
        if (value)
            cudaFree(value);
        value = next;
        capacity = count;
        ++allocations;
    }
    ~Buffer() {
        if (value)
            cudaFree(value);
    }
    Buffer(const Buffer &) = delete;
    Buffer &operator=(const Buffer &) = delete;
};
struct Handle {
    cublasHandle_t value{};
    explicit Handle(cudaStream_t stream, void *workspace, std::size_t bytes) {
        check(cublasCreate(&value));
        try {
            check(cublasSetStream(value, stream));
            // SetStream resets the workspace, so bind the dedicated workspace
            // afterwards.
            check(cublasSetWorkspace(value, workspace, bytes));
            check(cublasSetMathMode(value, CUBLAS_DEFAULT_MATH));
        } catch (...) {
            cublasDestroy(value);
            throw;
        }
    }
    ~Handle() {
        if (value)
            cublasDestroy(value);
    }
};
template <class T> void upload(T *target, const std::vector<T> &source, cudaStream_t stream) {
    if (!source.empty())
        check(cudaMemcpyAsync(target, source.data(), source.size() * sizeof(T), cudaMemcpyHostToDevice,
                              stream));
}
void synchronize(cudaStream_t stream, ExecutionTimes *times = nullptr) {
    auto begin = Clock::now();
    check(cudaStreamSynchronize(stream));
    if (times)
        times->synchronizationMilliseconds += elapsed(begin);
}
std::vector<double> download(const double *source, std::size_t count, cudaStream_t stream,
                             ExecutionTimes *times = nullptr) {
    std::vector<double> result(count);
    check(cudaMemcpyAsync(result.data(), source, count * sizeof(double), cudaMemcpyDeviceToHost, stream));
    synchronize(stream, times);
    return result;
}
unsigned grid(std::size_t count, unsigned block = 256) {
    std::size_t blocks = (count + block - 1) / block;
    if (blocks > std::numeric_limits<int>::max())
        throw std::invalid_argument("FGW kernel grid exceeds supported dimensions.");
    return static_cast<unsigned>(blocks);
}
void enqueueDot(cublasHandle_t handle, const double *a, const double *b, int count, double *out) {
    check(cublasSetPointerMode(handle, CUBLAS_POINTER_MODE_DEVICE));
    auto status = cublasDdot(handle, count, a, 1, b, 1, out);
    auto reset = cublasSetPointerMode(handle, CUBLAS_POINTER_MODE_HOST);
    check(status);
    check(reset);
}
double dot(cublasHandle_t handle, const double *a, const double *b, int count, double *scalar,
           cudaStream_t stream, ExecutionTimes *times) {
    enqueueDot(handle, a, b, count, scalar);
    return download(scalar, 1, stream, times)[0];
}
struct DeviceInterval {
    Event begin, end;
    bool enabled = false;
    void start(cudaStream_t stream, bool profile) {
        enabled = profile;
        if (enabled)
            check(cudaEventRecord(begin.value, stream));
    }
    void finish(cudaStream_t stream, ExecutionTimes &times) {
        if (!enabled)
            return;
        check(cudaEventRecord(end.value, stream));
        check(cudaEventSynchronize(end.value));
        float milliseconds = 0;
        check(cudaEventElapsedTime(&milliseconds, begin.value, end.value));
        times.deviceMilliseconds += milliseconds;
        enabled = false;
    }
};
/** Pointer batches share read-only structures; buffers grow only at capacity
 * changes. */
struct ProductBatch {
    Buffer<const double *> left, right, current, direction, temporaryInput;
    Buffer<double *> temporary, output;
    const double *d1 = nullptr, *d2 = nullptr, *p = nullptr, *delta = nullptr;
    double *temp = nullptr, *product = nullptr;
    void bind(const double *a, const double *b, const double *c, const double *d, double *t, double *out,
              std::size_t stride, int starts, cudaStream_t stream) {
        d1 = a;
        d2 = b;
        p = c;
        delta = d;
        temp = t;
        product = out;
        if (starts == 1)
            return;
        left.reserve(starts);
        right.reserve(starts);
        current.reserve(starts);
        direction.reserve(starts);
        temporaryInput.reserve(starts);
        temporary.reserve(starts);
        output.reserve(starts);
        std::vector<const double *> av(starts, a), bv(starts, b), cv(starts), dv(starts), tv(starts);
        std::vector<double *> tout(starts), outputs(starts);
        for (int i = 0; i < starts; ++i) {
            cv[i] = c + i * stride;
            dv[i] = d + i * stride;
            tv[i] = t + i * stride;
            tout[i] = t + i * stride;
            outputs[i] = out + i * stride;
        }
        upload(left.value, av, stream);
        upload(right.value, bv, stream);
        upload(current.value, cv, stream);
        upload(direction.value, dv, stream);
        upload(temporaryInput.value, tv, stream);
        upload(temporary.value, tout, stream);
        upload(output.value, outputs, stream);
        synchronize(stream);
    }
};
void products(cublasHandle_t handle, const ProductBatch &batch, bool useDirection, int n, int m, int starts) {
    const double one = 1, zero = 0;
    auto input = [&](algorithm::ProductBuffer buffer) -> const double *const * {
        switch (buffer) {
        case algorithm::ProductBuffer::Input:
            return useDirection ? batch.direction.value : batch.current.value;
        case algorithm::ProductBuffer::LeftDistances:
            return batch.left.value;
        case algorithm::ProductBuffer::RightDistances:
            return batch.right.value;
        default:
            return batch.temporaryInput.value;
        }
    };
    auto single = [&](algorithm::ProductBuffer buffer) -> const double * {
        switch (buffer) {
        case algorithm::ProductBuffer::Input:
            return useDirection ? batch.delta : batch.p;
        case algorithm::ProductBuffer::LeftDistances:
            return batch.d1;
        case algorithm::ProductBuffer::RightDistances:
            return batch.d2;
        default:
            return batch.temp;
        }
    };
    for (const auto &op : algorithm::matrixProductPlan(n, m)) {
        bool temporary = op.output == algorithm::ProductBuffer::Temporary;
        if (starts == 1)
            check(cublasDgemm(handle, CUBLAS_OP_N, CUBLAS_OP_N, op.rows, op.columns, op.inner, &one,
                              single(op.left), op.leftLeading, single(op.right), op.rightLeading, &zero,
                              temporary ? batch.temp : batch.product, op.outputLeading));
        else
            check(cublasDgemmBatched(handle, CUBLAS_OP_N, CUBLAS_OP_N, op.rows, op.columns, op.inner, &one,
                                     input(op.left), op.leftLeading, input(op.right), op.rightLeading, &zero,
                                     temporary ? batch.temporary.value : batch.output.value, op.outputLeading,
                                     starts));
    }
}
SourceMapping mapping(const Graph &graph) {
    return {graph.productId, graph.instanceId, graph.rootIndex, graph.sources};
}
bool sameGraph(const Graph &a, const Graph &b) {
    if (a.productId != b.productId || a.instanceId != b.instanceId || a.rootIndex != b.rootIndex ||
        a.rowOffsets != b.rowOffsets || a.colIndices != b.colIndices || a.embeddings != b.embeddings ||
        a.sources.size() != b.sources.size())
        return false;
    for (std::size_t i = 0; i < a.sources.size(); ++i) {
        const auto &x = a.sources[i];
        const auto &y = b.sources[i];
        if (x.occurrenceId != y.occurrenceId || x.materialId != y.materialId ||
            x.productionOrderId != y.productionOrderId || x.quantity != y.quantity || x.amount != y.amount ||
            x.issueLineIds != y.issueLineIds || x.fifoAllocationIds != y.fifoAllocationIds)
            return false;
    }
    return true;
}
/** Immutable device baseline. Preparation completes before workers receive it.
 */
struct Baseline {
    Graph graph;
    Buffer<double> embeddings, distances, weights, norm;
    Event ready{cudaEventDisableTiming};
    std::vector<double> hostWeights, hostDistances;
    Baseline(Graph input, const Config &config) : graph(std::move(input)) {
        auto csr = algorithm::validateGraph(graph);
        check(cudaSetDevice(config.device));
        Stream stream;
        int n = graph.nodeCount();
        Buffer<int> offsets(csr.offsets.size()), columns(csr.columns.size()),
            queues(static_cast<std::size_t>(n) * n);
        embeddings.reserve(graph.embeddings.size());
        distances.reserve(static_cast<std::size_t>(n) * n);
        weights.reserve(n);
        norm.reserve(n);
        upload(embeddings.value, graph.embeddings, stream.value);
        upload(offsets.value, csr.offsets, stream.value);
        upload(columns.value, csr.columns, stream.value);
        algorithm::structuralDistanceKernel<<<grid(n, 64), 64, 0, stream.value>>>(
            offsets.value, columns.value, distances.value, queues.value, n);
        algorithm::uniformKernel<<<grid(n), 256, 0, stream.value>>>(weights.value, n);
        algorithm::squaredMarginalKernel<<<grid(n), 256, 0, stream.value>>>(distances.value, weights.value,
                                                                            norm.value, n);
        check(cudaGetLastError());
        check(cudaEventRecord(ready.value, stream.value));
        hostWeights = download(weights.value, n, stream.value);
        if (config.captureTrace)
            hostDistances = download(distances.value, static_cast<std::size_t>(n) * n, stream.value);
    }
};
/** Only the owning host thread invokes this handle. No resources are shared
 * mutably. */
struct Worker {
    Stream stream;
    Buffer<unsigned char> workspace;
    Handle handle;
    Buffer<int> rightOffsets, rightColumns, rightQueues;
    Buffer<double> rightEmb, d2, q, rightNorm, material, fixed, current, candidate, direction;
    Buffer<double> temporary, product, h, gradient, scalars;
    ProductBatch batch;
    DeviceInterval interval;
    explicit Worker(const Config &config)
        : workspace(config.workspaceBytes), handle(stream.value, workspace.value, config.workspaceBytes),
          scalars(2) {}
    ~Worker() { cudaStreamSynchronize(stream.value); }
    std::pair<std::size_t, std::size_t> allocationStatistics() const {
        std::size_t allocations = 0, bytes = 0;
        auto add = [&](const auto &buffer) {
            allocations += buffer.allocations;
            bytes += buffer.capacity * sizeof(*buffer.value);
        };
        add(workspace);
        add(scalars);
        add(rightOffsets);
        add(rightColumns);
        add(rightQueues);
        for (auto *buffer : {&rightEmb, &d2, &q, &rightNorm, &material, &fixed, &current, &candidate,
                             &direction, &temporary, &product, &h, &gradient})
            add(*buffer);
        add(batch.left);
        add(batch.right);
        add(batch.current);
        add(batch.direction);
        add(batch.temporaryInput);
        add(batch.temporary);
        add(batch.output);
        return {allocations, bytes};
    }
    Result compare(const Baseline &baseline, const Graph &target,
                   const std::vector<std::vector<double>> &initialMatrices, const Config &config) {
        const auto begin = Clock::now();
        auto right = algorithm::validateGraph(target);
        const int n = baseline.graph.nodeCount(), m = target.nodeCount();
        if (baseline.graph.productId != target.productId)
            throw std::invalid_argument("FGW compares instances of the same product.");
        for (const auto &initial : initialMatrices)
            algorithm::validateInitial(initial, n, m, config);
        const std::size_t count = static_cast<std::size_t>(n) * m;
        const std::size_t startCount = initialMatrices.empty() ? 1 : initialMatrices.size();
        if (count > std::numeric_limits<int>::max() || startCount > std::numeric_limits<int>::max() ||
            count > std::numeric_limits<std::size_t>::max() / startCount)
            throw std::invalid_argument("FGW dense dimensions exceed cuBLAS/index limits.");
        const int starts = static_cast<int>(startCount);
        check(cudaStreamWaitEvent(stream.value, baseline.ready.value, 0));
        rightOffsets.reserve(right.offsets.size());
        rightColumns.reserve(right.columns.size());
        rightQueues.reserve(static_cast<std::size_t>(m) * m);
        rightEmb.reserve(target.embeddings.size());
        d2.reserve(static_cast<std::size_t>(m) * m);
        q.reserve(m);
        rightNorm.reserve(m);
        material.reserve(count);
        fixed.reserve(count);
        for (auto *buffer : {&current, &candidate, &direction, &temporary, &product, &h, &gradient})
            buffer->reserve(count * starts);
        interval.start(stream.value, config.profileExecution);
        upload(rightOffsets.value, right.offsets, stream.value);
        upload(rightColumns.value, right.columns, stream.value);
        upload(rightEmb.value, target.embeddings, stream.value);
        algorithm::materialDistanceKernel<<<grid(count), 256, 0, stream.value>>>(
            baseline.embeddings.value, rightEmb.value, material.value, n, m);
        algorithm::structuralDistanceKernel<<<grid(m, 64), 64, 0, stream.value>>>(
            rightOffsets.value, rightColumns.value, d2.value, rightQueues.value, m);
        algorithm::uniformKernel<<<grid(m), 256, 0, stream.value>>>(q.value, m);
        algorithm::squaredMarginalKernel<<<grid(m), 256, 0, stream.value>>>(d2.value, q.value,
                                                                            rightNorm.value, m);
        algorithm::fixedTermKernel<<<grid(count), 256, 0, stream.value>>>(baseline.norm.value,
                                                                          rightNorm.value, fixed.value, n, m);
        for (int s = 0; s < starts; ++s) {
            if (initialMatrices.empty())
                algorithm::outerKernel<<<grid(count), 256, 0, stream.value>>>(
                    baseline.weights.value, q.value, current.value + s * count, n, m);
            else
                upload(current.value + s * count, initialMatrices[s], stream.value);
        }
        check(cudaGetLastError());
        batch.bind(baseline.distances.value, d2.value, current.value, direction.value, temporary.value,
                   product.value, count, starts, stream.value);
        Result result;
        result.rows = n;
        result.columns = m;
        result.left = mapping(baseline.graph);
        result.right = mapping(target);
        result.p = baseline.hostWeights;
        result.q = download(q.value, m, stream.value);
        if (config.captureTrace) {
            result.materialDistances = download(material.value, count, stream.value);
            result.leftDistances = baseline.hostDistances;
            result.rightDistances = download(d2.value, static_cast<std::size_t>(m) * m, stream.value);
        }
        interval.finish(stream.value, result.executionTimes);
        result.starts.resize(starts);
        std::vector<bool> active(starts, true);
        std::vector<algorithm::LinearOtWorkspace> otWorkspaces(starts);
        std::vector<double> previous(starts, 0), b(starts, 0);
        std::vector<bool> hasPrevious(starts, false);
        while (std::find(active.begin(), active.end(), true) != active.end()) {
            interval.start(stream.value, config.profileExecution);
            products(handle.value, batch, false, n, m, starts);
            check(cudaMemsetAsync(direction.value, 0, count * starts * sizeof(double), stream.value));
            for (int s = 0; s < starts; ++s)
                if (active[s]) {
                    auto &output = result.starts[s];
                    const auto offset = s * count;
                    if (!interval.enabled)
                        interval.start(stream.value, config.profileExecution);
                    algorithm::costGradientKernel<<<grid(count), 256, 0, stream.value>>>(
                        material.value, fixed.value, product.value + offset, h.value + offset,
                        gradient.value + offset, config.alpha, count);
                    check(cudaGetLastError());
                    Iteration state;
                    state.updates = output.iterations;
                    enqueueDot(handle.value, material.value, current.value + offset, static_cast<int>(count),
                               scalars.value);
                    enqueueDot(handle.value, h.value + offset, current.value + offset,
                               static_cast<int>(count), scalars.value + 1);
                    std::vector<double> matrix(count), g(count);
                    double costs[2]{};
                    check(cudaMemcpyAsync(matrix.data(), current.value + offset, count * sizeof(double),
                                          cudaMemcpyDeviceToHost, stream.value));
                    check(cudaMemcpyAsync(g.data(), gradient.value + offset, count * sizeof(double),
                                          cudaMemcpyDeviceToHost, stream.value));
                    check(cudaMemcpyAsync(costs, scalars.value, sizeof(costs), cudaMemcpyDeviceToHost,
                                          stream.value));
                    synchronize(stream.value, &result.executionTimes);
                    interval.finish(stream.value, result.executionTimes);
                    state.costs = algorithm::combineCosts(costs[0], costs[1], config.alpha);
                    state.marginals = algorithm::marginalErrors(matrix, n, m);
                    const auto otBegin = Clock::now();
                    try {
                        state.ot = algorithm::exactLinearOt(g, n, m, config.otTolerance, &otWorkspaces[s]);
                    } catch (const std::runtime_error &error) {
                        result.executionTimes.cpuOtMilliseconds += elapsed(otBegin);
                        output.status = Status::NUMERICAL_FAILURE;
                        output.diagnostic = error.what();
                        output.correspondence = std::move(matrix);
                        output.costs = state.costs;
                        output.finalGap = std::numeric_limits<double>::quiet_NaN();
                        active[s] = false;
                        continue;
                    }
                    result.executionTimes.cpuOtMilliseconds += elapsed(otBegin);
                    interval.start(stream.value, config.profileExecution);
                    upload(candidate.value + offset, state.ot.correspondence, stream.value);
                    algorithm::directionKernel<<<grid(count), 256, 0, stream.value>>>(
                        candidate.value + offset, current.value + offset, direction.value + offset, count);
                    check(cudaGetLastError());
                    b[s] = dot(handle.value, gradient.value + offset, direction.value + offset,
                               static_cast<int>(count), scalars.value, stream.value, &result.executionTimes);
                    interval.finish(stream.value, result.executionTimes);
                    state.gap = algorithm::fwGap(b[s]);
                    if (hasPrevious[s]) {
                        state.absoluteCostChange = std::abs(previous[s] - state.costs.fgw);
                        state.relativeCostChange =
                            state.absoluteCostChange / std::max(std::abs(previous[s]), config.costScaleFloor);
                    }
                    output.status =
                        algorithm::stoppingStatus(state.gap, state.costs, state.marginals, output.iterations,
                                                  previous[s], hasPrevious[s], config);
                    if (hasPrevious[s] &&
                        algorithm::objectiveIncreased(previous[s], state.costs.fgw, config)) {
                        output.status = Status::NUMERICAL_FAILURE;
                        output.diagnostic = "Exact line search increased the true objective.";
                    }
                    if (output.status == Status::NUMERICAL_FAILURE && output.diagnostic.empty())
                        output.diagnostic = "Nonfinite costs, failed marginals or "
                                            "significantly negative fresh FW gap.";
                    output.costs = state.costs;
                    output.finalGap = state.gap;
                    output.correspondence = matrix;
                    if (config.captureTrace) {
                        state.correspondence = matrix;
                        state.gradient = g;
                        state.direction = download(direction.value + offset, count, stream.value);
                    } else
                        state.ot.correspondence.clear();
                    output.trace.push_back(std::move(state));
                    if (output.status != Status::RUNNING)
                        active[s] = false;
                }
            // Batched products accelerate all active multi-start directions.
            if (std::find(active.begin(), active.end(), true) == active.end())
                break;
            interval.start(stream.value, config.profileExecution);
            products(handle.value, batch, true, n, m, starts);
            for (int s = 0; s < starts; ++s)
                if (active[s]) {
                    auto &output = result.starts[s];
                    const auto offset = s * count;
                    double directionProduct =
                        dot(handle.value, product.value + offset, direction.value + offset,
                            static_cast<int>(count), scalars.value, stream.value, &result.executionTimes);
                    output.trace.back().step = algorithm::quadraticLineSearch(
                        directionProduct, b[s], config.alpha, config.numericalTolerance);
                    previous[s] = output.costs.fgw;
                    hasPrevious[s] = true;
                    algorithm::updateKernel<<<grid(count), 256, 0, stream.value>>>(
                        current.value + offset, direction.value + offset, output.trace.back().step.gamma,
                        count);
                    check(cudaGetLastError());
                    ++output.iterations;
                }
            interval.finish(stream.value, result.executionTimes);
        }
        check(cudaStreamSynchronize(stream.value));
        // Keep all failed starts visible instead of hiding numerical failure in a
        // throw.
        try {
            result.bestStart = algorithm::selectBestStart(result.starts);
        } catch (const std::runtime_error &) {
            result.bestStart = 0;
        }
        result.totalMilliseconds = elapsed(begin);
        return result;
    }
};
} // namespace

struct CudaFgwSession::Impl {
    struct Task {
        Graph target;
        std::vector<std::vector<double>> initialMatrices;
        std::promise<Result> promise;
    };
    Config config;
    std::unique_ptr<Baseline> baseline;
    std::mutex mutex;
    std::condition_variable available, space;
    std::deque<Task> pending;
    std::vector<std::thread> threads;
    SessionStatistics stats;
    bool closed = false;
    std::size_t capacity;
    Impl(Graph graph, const Config &settings)
        : config(settings),
          capacity(settings.queueCapacity ? settings.queueCapacity
                                          : 2 * static_cast<std::size_t>(settings.maxConcurrentComparisons)) {
        algorithm::validateConfig(config);
        check(cudaSetDevice(config.device));
        baseline = std::make_unique<Baseline>(std::move(graph), config);
        stats.baselinePreparations = 1;
        std::vector<std::future<void>> ready;
        try {
            for (int i = 0; i < config.maxConcurrentComparisons; ++i) {
                std::promise<void> initialized;
                ready.push_back(initialized.get_future());
                threads.emplace_back([this, signal = std::move(initialized)]() mutable {
                    bool announced = false;
                    try {
                        check(cudaSetDevice(config.device));
                        Worker worker(config);
                        std::pair<std::size_t, std::size_t> previous{};
                        auto updateStatistics = [&](bool completed, bool failed) {
                            auto current = worker.allocationStatistics();
                            std::lock_guard<std::mutex> lock(mutex);
                            stats.workerDeviceAllocations += current.first - previous.first;
                            stats.workerDeviceBytes += current.second - previous.second;
                            stats.completed += completed;
                            stats.failed += failed;
                            previous = current;
                        };
                        {
                            std::lock_guard<std::mutex> lock(mutex);
                            ++stats.workersCreated;
                        }
                        updateStatistics(false, false);
                        signal.set_value();
                        announced = true;
                        while (true) {
                            Task task;
                            {
                                std::unique_lock<std::mutex> lock(mutex);
                                available.wait(lock, [&] { return closed || !pending.empty(); });
                                if (pending.empty())
                                    break;
                                task = std::move(pending.front());
                                pending.pop_front();
                            }
                            space.notify_one();
                            try {
                                auto result =
                                    worker.compare(*baseline, task.target, task.initialMatrices, config);
                                updateStatistics(true, false);
                                task.promise.set_value(std::move(result));
                            } catch (...) {
                                // Drain this stream before another task can overwrite its buffers.
                                cudaStreamSynchronize(worker.stream.value);
                                updateStatistics(false, true);
                                task.promise.set_exception(std::current_exception());
                            }
                        }
                    } catch (...) {
                        if (!announced)
                            signal.set_exception(std::current_exception());
                        else {
                            auto error = std::current_exception();
                            {
                                std::lock_guard<std::mutex> lock(mutex);
                                closed = true;
                                for (auto &task : pending) {
                                    task.promise.set_exception(error);
                                    ++stats.failed;
                                }
                                pending.clear();
                            }
                            available.notify_all();
                            space.notify_all();
                        }
                    }
                });
            }
            for (auto &future : ready)
                future.get();
        } catch (...) {
            close();
            for (auto &thread : threads)
                if (thread.joinable())
                    thread.join();
            throw;
        }
    }
    void close() {
        {
            std::lock_guard<std::mutex> lock(mutex);
            closed = true;
        }
        available.notify_all();
        space.notify_all();
    }
    ~Impl() {
        close();
        for (auto &thread : threads)
            if (thread.joinable())
                thread.join();
        // CUDA resources must be released on their selected device even if the
        // caller changed it.
        cudaSetDevice(config.device);
        baseline.reset();
    }
};
CudaFgwSession::CudaFgwSession(Graph baseline, const Config &config)
    : impl(std::make_unique<Impl>(std::move(baseline), config)) {}
CudaFgwSession::~CudaFgwSession() = default;
void CudaFgwSession::close() { impl->close(); }
SessionStatistics CudaFgwSession::statistics() const {
    std::lock_guard<std::mutex> lock(impl->mutex);
    return impl->stats;
}
std::future<Result> CudaFgwSession::submit(Graph target, std::vector<std::vector<double>> initialMatrices) {
    Impl::Task task{std::move(target), std::move(initialMatrices), {}};
    auto future = task.promise.get_future();
    {
        std::unique_lock<std::mutex> lock(impl->mutex);
        impl->space.wait(lock, [&] { return impl->closed || impl->pending.size() < impl->capacity; });
        if (impl->closed)
            throw std::runtime_error("FGW session is closed; target was not accepted.");
        impl->pending.push_back(std::move(task));
        ++impl->stats.accepted;
        impl->stats.maximumQueued = std::max(impl->stats.maximumQueued, impl->pending.size());
    }
    impl->available.notify_one();
    return future;
}
Result CudaFgwEngine::compare(const Comparison &input, const Config &config) const {
    algorithm::validateComparison(input, config);
    Config single = config;
    single.maxConcurrentComparisons = 1;
    CudaFgwSession session(input.left, single);
    return session.submit(input.right, input.initialMatrices).get();
}
std::vector<Result> CudaFgwEngine::compareBatch(const std::vector<Comparison> &inputs,
                                                const Config &config) const {
    algorithm::validateConfig(config);
    std::vector<Result> results(inputs.size());
    if (inputs.empty())
        return results;
    if (std::all_of(inputs.begin(), inputs.end(),
                    [&](const Comparison &input) { return sameGraph(input.left, inputs.front().left); })) {
        Config settings = config;
        settings.maxConcurrentComparisons =
            std::min(inputs.size(), static_cast<std::size_t>(config.maxConcurrentComparisons));
        CudaFgwSession session(inputs.front().left, settings);
        std::vector<std::future<Result>> futures;
        for (const auto &input : inputs)
            futures.push_back(session.submit(input.right, input.initialMatrices));
        for (std::size_t i = 0; i < inputs.size(); ++i)
            results[i] = futures[i].get();
        return results;
    }
    // Fixed threads and handles also serve the arbitrary-pair convenience API.
    std::mutex mutex;
    std::size_t next = 0;
    std::exception_ptr failure;
    std::vector<std::thread> threads;
    try {
        for (std::size_t workerIndex = 0;
             workerIndex < std::min(inputs.size(), static_cast<std::size_t>(config.maxConcurrentComparisons));
             ++workerIndex)
            threads.emplace_back([&] {
                try {
                    check(cudaSetDevice(config.device));
                    Worker worker(config);
                    while (true) {
                        std::size_t index;
                        {
                            std::lock_guard<std::mutex> lock(mutex);
                            if (next == inputs.size() || failure)
                                break;
                            index = next++;
                        }
                        algorithm::validateComparison(inputs[index], config);
                        Baseline baseline(inputs[index].left, config);
                        results[index] = worker.compare(baseline, inputs[index].right,
                                                        inputs[index].initialMatrices, config);
                    }
                } catch (...) {
                    std::lock_guard<std::mutex> lock(mutex);
                    if (!failure)
                        failure = std::current_exception();
                }
            });
    } catch (...) {
        for (auto &thread : threads)
            if (thread.joinable())
                thread.join();
        throw;
    }
    for (auto &thread : threads)
        thread.join();
    if (failure)
        std::rethrow_exception(failure);
    return results;
}
} // namespace fgw
