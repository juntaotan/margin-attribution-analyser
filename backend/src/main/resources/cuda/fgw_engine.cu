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
/** Capacity-reused pinned storage, owned until its stream has been drained. */
template <class T> struct Pinned {
    T *value = nullptr;
    std::size_t capacity = 0;
    void reserve(std::size_t count) {
        if (count <= capacity)
            return;
        if (count > std::numeric_limits<std::size_t>::max() / sizeof(T))
            throw std::invalid_argument("FGW pinned allocation size overflow.");
        T *next = nullptr;
        check(cudaHostAlloc(reinterpret_cast<void **>(&next), count * sizeof(T), cudaHostAllocDefault));
        if (value)
            cudaFreeHost(value);
        value = next;
        capacity = count;
    }
    ~Pinned() {
        if (value)
            cudaFreeHost(value);
    }
    Pinned() = default;
    Pinned(const Pinned &) = delete;
    Pinned &operator=(const Pinned &) = delete;
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
    void recordEnd(cudaStream_t stream) {
        if (enabled)
            check(cudaEventRecord(end.value, stream));
    }
    // The dispatcher calls this only after the owning stream is ready.
    void collect(ExecutionTimes &times, bool ot = false) {
        if (!enabled)
            return;
        float milliseconds = 0;
        check(cudaEventElapsedTime(&milliseconds, begin.value, end.value));
        if (ot)
            times.gpuOtMilliseconds += milliseconds;
        else
            times.deviceMilliseconds += milliseconds;
        enabled = false;
    }
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
    void finishOt(cudaStream_t stream, ExecutionTimes &times) {
        if (!enabled)
            return;
        check(cudaEventRecord(end.value, stream));
        check(cudaEventSynchronize(end.value));
        float milliseconds = 0;
        check(cudaEventElapsedTime(&milliseconds, begin.value, end.value));
        times.gpuOtMilliseconds += milliseconds;
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
    std::vector<const double *> av, bv, cv, dv, tv;
    std::vector<double *> tout, outputs;
    std::vector<algorithm::ProductOperation> plan;
    void bind(const double *a, const double *b, const double *c, const double *d, double *t, double *out,
              std::size_t stride, int starts, cudaStream_t stream, int n, int m) {
        plan = algorithm::matrixProductPlan(n, m);
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
        av.assign(starts, a);
        bv.assign(starts, b);
        cv.resize(starts);
        dv.resize(starts);
        tv.resize(starts);
        tout.resize(starts);
        outputs.resize(starts);
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
    for (const auto &op : batch.plan) {
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
struct Task {
    Graph target;
    std::vector<std::vector<double>> initialMatrices;
    std::promise<Result> promise;
    std::unique_ptr<Graph> baselineOverride;
};
/** Only the owning host thread invokes this handle. No resources are shared
 * mutably. */
struct Slot {
    Stream stream;
    Buffer<unsigned char> workspace;
    Handle handle;
    Buffer<int> rightOffsets, rightColumns, rightQueues;
    Buffer<double> rightEmb, d2, q, rightNorm, material, fixed, current, candidate, direction;
    Buffer<double> temporary, product, h, gradient, scalars;
    Buffer<int> otFlow, otPivots;
    Buffer<bool> otBasic;
    ProductBatch batch;
    DeviceInterval interval, otInterval;
    std::vector<double> hostMatrix;
    enum class Phase { IDLE, PREPARING, ITERATING, FINISHED };
    struct FwState {
        bool active = true, hasPrevious = false;
        double previous = 0;
    };
    Config settings;
    Phase phase = Phase::IDLE;
    std::unique_ptr<Task> currentTask;
    std::unique_ptr<Baseline> pairBaseline;
    const Baseline *activeBaseline = nullptr;
    Result result;
    std::vector<FwState> fw;
    std::vector<std::unique_ptr<DeviceInterval>> otIntervals;
    Pinned<double> hostScalars, finalMatrix, hostWeights;
    Pinned<int> hostPivots;
    algorithm::UndirectedCsr targetCsr;
    Clock::time_point started, submitted;
    std::size_t count = 0;
    int rows = 0, columns = 0, starts = 0;
    bool synchronous = false;
    std::pair<std::size_t, std::size_t> accounted{};

    /** Own a target until all uploads and results finish. Only the assigned
     * dispatcher calls this method; no mutable slot state crosses threads. */
    void prepare(std::unique_ptr<Task> task, const Baseline &sharedBaseline) {
        started = Clock::now();
        currentTask = std::move(task);
        phase = Phase::PREPARING;
        if (currentTask->baselineOverride)
            pairBaseline = std::make_unique<Baseline>(std::move(*currentTask->baselineOverride), settings);
        activeBaseline = pairBaseline ? pairBaseline.get() : &sharedBaseline;
        const auto &baseline = *activeBaseline;
        const auto &target = currentTask->target;
        const auto &initials = currentTask->initialMatrices;
        rows = baseline.graph.nodeCount();
        columns = target.nodeCount();
        targetCsr = algorithm::validateGraph(target);
        if (baseline.graph.productId != target.productId)
            throw std::invalid_argument("FGW compares instances of the same product.");
        for (const auto &initial : initials)
            algorithm::validateInitial(initial, rows, columns, settings);
        synchronous = settings.captureTrace || settings.otSolver == LinearOtSolver::CPU_REFERENCE ||
                      rows + columns > 320;
        if (synchronous) {
            // The diagnostic path keeps full numerical evidence and exact CPU
            // OT certificates. It does not claim asynchronous GPU throughput.
            auto diagnostic = settings;
            diagnostic.otSolver = LinearOtSolver::CPU_REFERENCE;
            result = compareSynchronous(baseline, target, initials, diagnostic);
            phase = Phase::FINISHED;
            submitted = Clock::now();
            return;
        }
        count = static_cast<std::size_t>(rows) * columns;
        std::size_t startCount = initials.empty() ? 1 : initials.size();
        if (count > std::numeric_limits<int>::max() || startCount > std::numeric_limits<int>::max() ||
            count > std::numeric_limits<std::size_t>::max() / startCount)
            throw std::invalid_argument("FGW dense dimensions exceed cuBLAS/index limits.");
        starts = static_cast<int>(startCount);
        check(cudaStreamWaitEvent(stream.value, baseline.ready.value, 0));
        rightOffsets.reserve(targetCsr.offsets.size());
        rightColumns.reserve(targetCsr.columns.size());
        rightQueues.reserve(static_cast<std::size_t>(columns) * columns);
        rightEmb.reserve(target.embeddings.size());
        d2.reserve(static_cast<std::size_t>(columns) * columns);
        q.reserve(columns);
        rightNorm.reserve(columns);
        material.reserve(count);
        fixed.reserve(count);
        for (auto *buffer : {&current, &candidate, &direction, &temporary, &product, &h, &gradient})
            buffer->reserve(count * starts);
        otFlow.reserve(count * starts);
        otBasic.reserve(count * starts);
        otPivots.reserve(starts);
        scalars.reserve(static_cast<std::size_t>(algorithm::SummaryCount) * starts);
        hostScalars.reserve(static_cast<std::size_t>(algorithm::SummaryCount) * starts);
        hostPivots.reserve(starts);
        finalMatrix.reserve(count * starts);
        hostWeights.reserve(columns);
        while (otIntervals.size() < startCount)
            otIntervals.push_back(std::make_unique<DeviceInterval>());
        result = {};
        result.rows = rows;
        result.columns = columns;
        result.left = mapping(baseline.graph);
        result.right = mapping(target);
        result.p = baseline.hostWeights;
        result.q.resize(columns);
        result.starts.resize(starts);
        fw.assign(starts, {});
        for (auto &output : result.starts) {
            output.correspondence.resize(count);
            output.trace.reserve(static_cast<std::size_t>(settings.maxIterations) + 1);
        }
        interval.start(stream.value, settings.profileExecution);
        upload(rightOffsets.value, targetCsr.offsets, stream.value);
        upload(rightColumns.value, targetCsr.columns, stream.value);
        upload(rightEmb.value, target.embeddings, stream.value);
        algorithm::materialDistanceKernel<<<grid(count), 256, 0, stream.value>>>(
            baseline.embeddings.value, rightEmb.value, material.value, rows, columns);
        algorithm::structuralDistanceKernel<<<grid(columns, 64), 64, 0, stream.value>>>(
            rightOffsets.value, rightColumns.value, d2.value, rightQueues.value, columns);
        algorithm::uniformKernel<<<grid(columns), 256, 0, stream.value>>>(q.value, columns);
        algorithm::squaredMarginalKernel<<<grid(columns), 256, 0, stream.value>>>(d2.value, q.value,
                                                                                  rightNorm.value, columns);
        algorithm::fixedTermKernel<<<grid(count), 256, 0, stream.value>>>(
            baseline.norm.value, rightNorm.value, fixed.value, rows, columns);
        for (int s = 0; s < starts; ++s) {
            if (initials.empty())
                algorithm::outerKernel<<<grid(count), 256, 0, stream.value>>>(
                    baseline.weights.value, q.value, current.value + s * count, rows, columns);
            else
                upload(current.value + s * count, initials[s], stream.value);
        }
        check(cudaGetLastError());
        batch.bind(baseline.distances.value, d2.value, current.value, direction.value, temporary.value,
                   product.value, count, starts, stream.value, rows, columns);
        check(cudaMemcpyAsync(hostWeights.value, q.value, columns * sizeof(double), cudaMemcpyDeviceToHost,
                              stream.value));
        enqueueIteration();
    }

    /** Submit the complete GPU iteration, including line-search products, then
     * download only pinned scalar summaries. There is no host wait or heap
     * allocation in this submission method. */
    void enqueueIteration() {
        if (!interval.enabled) interval.start(stream.value, settings.profileExecution);
        products(handle.value, batch, false, rows, columns, starts);
        check(cudaMemsetAsync(direction.value, 0, count * starts * sizeof(double), stream.value));
        for (int s = 0; s < starts; ++s)
            if (fw[s].active) {
                using namespace algorithm;
                auto offset = s * count;
                double *summary = scalars.value + s * SummaryCount;
                check(cudaMemsetAsync(summary, 0, SummaryCount * sizeof(double), stream.value));
                costGradientKernel<<<grid(count), 256, 0, stream.value>>>(
                    material.value, fixed.value, product.value + offset, h.value + offset,
                    gradient.value + offset, settings.alpha, count);
                enqueueDot(handle.value, material.value, current.value + offset, static_cast<int>(count),
                           summary + MaterialCost);
                enqueueDot(handle.value, h.value + offset, current.value + offset, static_cast<int>(count),
                           summary + StructureCost);
                marginalSummaryKernel<<<1, 256, 0, stream.value>>>(current.value + offset, summary + RowError,
                                                                   rows, columns);
                otIntervals[s]->start(stream.value, settings.profileExecution);
                transportationSimplexKernel<<<1, 256, 0, stream.value>>>(
                    gradient.value + offset, otFlow.value + offset, otBasic.value + offset,
                    candidate.value + offset, otPivots.value + s, rows, columns, settings.otTolerance,
                    result.starts[s].iterations > 0, count, summary, settings.incrementalOtTree,
                    settings.candidateOtPricing);
                otIntervals[s]->recordEnd(stream.value);
                marginalSummaryKernel<<<1, 256, 0, stream.value>>>(
                    candidate.value + offset, summary + CandidateRowError, rows, columns);
                directionKernel<<<grid(count), 256, 0, stream.value>>>(
                    candidate.value + offset, current.value + offset, direction.value + offset, count);
                enqueueDot(handle.value, gradient.value + offset, direction.value + offset,
                           static_cast<int>(count), summary + DirectionalGradient);
            }
        products(handle.value, batch, true, rows, columns, starts);
        for (int s = 0; s < starts; ++s)
            if (fw[s].active)
                enqueueDot(handle.value, product.value + s * count, direction.value + s * count,
                           static_cast<int>(count),
                           scalars.value + s * algorithm::SummaryCount + algorithm::DirectionProduct);
        check(cudaGetLastError());
        check(cudaMemcpyAsync(hostScalars.value, scalars.value,
                              starts * algorithm::SummaryCount * sizeof(double), cudaMemcpyDeviceToHost,
                              stream.value));
        check(cudaMemcpyAsync(hostPivots.value, otPivots.value, starts * sizeof(int), cudaMemcpyDeviceToHost,
                              stream.value));
        interval.recordEnd(stream.value);
        phase = Phase::ITERATING;
        submitted = Clock::now();
    }

    /** Advance ready work without blocking other slots. A terminal iteration
     * schedules the final matrix download as a separate asynchronous phase. */
    bool poll() {
        if (phase == Phase::IDLE)
            return false;
        auto status = cudaStreamQuery(stream.value);
        if (status == cudaErrorNotReady)
            return false;
        check(status);
        if (synchronous) {
            result.totalMilliseconds = elapsed(started);
            return true;
        }
        interval.collect(result.executionTimes);
        if (phase == Phase::FINISHED) {
            for (int s = 0; s < starts; ++s) {
                auto &output = result.starts[s];
                std::copy_n(finalMatrix.value + s * count, count, output.correspondence.begin());
                auto errors = algorithm::marginalErrors(output.correspondence, rows, columns);
                output.trace.back().marginals = errors;
                if (output.status != Status::NUMERICAL_FAILURE &&
                    algorithm::stoppingStatus(output.finalGap, output.costs, errors, output.iterations,
                                              fw[s].previous, fw[s].hasPrevious,
                                              settings) == Status::NUMERICAL_FAILURE) {
                    output.status = Status::NUMERICAL_FAILURE;
                    output.diagnostic = "Final correspondence failed original marginal/finite checks.";
                }
            }
            try {
                result.bestStart = algorithm::selectBestStart(result.starts);
            } catch (const std::runtime_error &) {
                result.bestStart = 0;
            }
            result.totalMilliseconds = elapsed(started);
            return true;
        }
        std::copy_n(hostWeights.value, columns, result.q.begin());
        bool anyActive = false;
        for (int s = 0; s < starts; ++s)
            if (fw[s].active) {
                using namespace algorithm;
                otIntervals[s]->collect(result.executionTimes, true);
                const double *summary = hostScalars.value + s * SummaryCount;
                auto &output = result.starts[s];
                Iteration state;
                state.updates = output.iterations;
                state.costs = combineCosts(summary[MaterialCost], summary[StructureCost], settings.alpha);
                state.gap = fwGap(summary[DirectionalGradient]);
                state.marginals = {summary[RowError], summary[ColumnError], summary[TotalError],
                                   summary[MinimumWeight], summary[FiniteWeights] == 1};
                MarginalErrors candidateErrors{summary[CandidateRowError], summary[CandidateColumnError],
                                               summary[CandidateTotalError], summary[CandidateMinimumWeight],
                                               summary[CandidateFiniteWeights] == 1};
                state.ot.linearCost = summary[LinearCost];
                state.ot.dualCost = summary[DualCost];
                state.ot.maximumReducedCostViolation = summary[ReducedCostViolation];
                state.ot.pivots = hostPivots.value[s] < 0 ? 0 : hostPivots.value[s];
                state.ot.fullPricingScans = static_cast<std::size_t>(summary[OtFullPricingScans]);
                state.ot.candidatePricingPasses = static_cast<std::size_t>(summary[OtCandidatePricingPasses]);
                state.ot.treeRebuilds = static_cast<std::size_t>(summary[OtTreeRebuilds]);
                state.ot.incrementalTreeUpdates = static_cast<std::size_t>(summary[OtIncrementalTreeUpdates]);
                state.ot.treeNodesVisited = static_cast<std::size_t>(summary[OtTreeNodesVisited]);
                state.ot.cycleEdges = static_cast<std::size_t>(summary[OtCycleEdges]);
                if (fw[s].hasPrevious) {
                    state.absoluteCostChange = std::abs(fw[s].previous - state.costs.fgw);
                    state.relativeCostChange = state.absoluteCostChange /
                                               std::max(std::abs(fw[s].previous), settings.costScaleFloor);
                }
                output.status = stoppingStatus(state.gap, state.costs, state.marginals, output.iterations,
                                               fw[s].previous, fw[s].hasPrevious, settings);
                if (summary[OtSuccess] != 1 ||
                    !linearOtCertified(state.ot, candidateErrors, summary[OtCostEpsilon],
                                       settings.otTolerance)) {
                    output.status = Status::NUMERICAL_FAILURE;
                    output.diagnostic = "GPU exact OT failed its primal/dual certificate or pivot limit.";
                }
                if (fw[s].hasPrevious && objectiveIncreased(fw[s].previous, state.costs.fgw, settings)) {
                    output.status = Status::NUMERICAL_FAILURE;
                    output.diagnostic = "Exact line search increased the true objective.";
                }
                if (output.status == Status::RUNNING) {
                    try {
                        state.step =
                            quadraticLineSearch(summary[DirectionProduct], summary[DirectionalGradient],
                                                settings.alpha, settings.numericalTolerance);
                    } catch (const std::runtime_error &error) {
                        output.status = Status::NUMERICAL_FAILURE;
                        output.diagnostic = error.what();
                    }
                }
                if (output.status == Status::NUMERICAL_FAILURE && output.diagnostic.empty())
                    output.diagnostic = "Nonfinite costs, failed marginals or "
                                        "significantly negative fresh FW gap.";
                output.costs = state.costs;
                output.finalGap = state.gap;
                output.trace.push_back(std::move(state));
                fw[s].active = output.status == Status::RUNNING;
                if (fw[s].active) {
                    fw[s].previous = output.costs.fgw;
                    fw[s].hasPrevious = true;
                    algorithm::updateKernel<<<grid(count), 256, 0, stream.value>>>(
                        current.value + s * count, direction.value + s * count,
                        output.trace.back().step.gamma, count);
                    ++output.iterations;
                    anyActive = true;
                }
            }
        check(cudaGetLastError());
        if (anyActive)
            enqueueIteration();
        else {
            interval.start(stream.value, settings.profileExecution);
            check(cudaMemcpyAsync(finalMatrix.value, current.value, count * starts * sizeof(double),
                                  cudaMemcpyDeviceToHost, stream.value));
            interval.recordEnd(stream.value);
            phase = Phase::FINISHED;
            submitted = Clock::now();
        }
        return false;
    }
    void reset() {
        phase = Phase::IDLE;
        currentTask.reset();
        pairBaseline.reset();
        activeBaseline = nullptr;
    }
    explicit Slot(const Config &config)
        : workspace(config.workspaceBytes), handle(stream.value, workspace.value, config.workspaceBytes),
          scalars(2), settings(config) {}
    ~Slot() { cudaStreamSynchronize(stream.value); }
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
        add(otFlow);
        add(otBasic);
        add(otPivots);
        add(batch.left);
        add(batch.right);
        add(batch.current);
        add(batch.direction);
        add(batch.temporaryInput);
        add(batch.temporary);
        add(batch.output);
        return {allocations, bytes};
    }
    Result compareSynchronous(const Baseline &baseline, const Graph &target,
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
        hostMatrix.resize(count);
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
        otFlow.reserve(count * starts);
        otBasic.reserve(count * starts);
        otPivots.reserve(starts);
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
                   product.value, count, starts, stream.value, n, m);
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
                    const bool useGpuSimplex =
                        (config.otSolver == LinearOtSolver::GPU_SIMPLEX) && (n + m <= 320);
                    const bool needMatrix = !useGpuSimplex || config.captureTrace;
                    std::vector<double> g;
                    double costs[2]{};
                    if (needMatrix) {
                        check(cudaMemcpyAsync(hostMatrix.data(), current.value + offset,
                                              count * sizeof(double), cudaMemcpyDeviceToHost, stream.value));
                    }
                    if (!useGpuSimplex || config.captureTrace) {
                        g.resize(count);
                        check(cudaMemcpyAsync(g.data(), gradient.value + offset, count * sizeof(double),
                                              cudaMemcpyDeviceToHost, stream.value));
                    }
                    check(cudaMemcpyAsync(costs, scalars.value, sizeof(costs), cudaMemcpyDeviceToHost,
                                          stream.value));
                    synchronize(stream.value, &result.executionTimes);
                    interval.finish(stream.value, result.executionTimes);
                    state.costs = algorithm::combineCosts(costs[0], costs[1], config.alpha);
                    if (needMatrix) {
                        state.marginals = algorithm::marginalErrors(hostMatrix, n, m);
                    } else {
                        state.marginals = {0, 0, 0, 0, true};
                    }
                    if (useGpuSimplex) {
                        otInterval.start(stream.value, config.profileExecution);
                        algorithm::transportationSimplexKernel<<<1, 256, 0, stream.value>>>(
                            gradient.value + offset, otFlow.value + offset, otBasic.value + offset,
                            candidate.value + offset, otPivots.value + s, n, m, config.otTolerance,
                            output.iterations > 0, count, nullptr, config.incrementalOtTree,
                            config.candidateOtPricing);
                        check(cudaGetLastError());
                        otInterval.finishOt(stream.value, result.executionTimes);
                        if (config.captureTrace) {
                            state.ot.correspondence = download(candidate.value + offset, count, stream.value);
                            int pivots = 0;
                            check(cudaMemcpyAsync(&pivots, otPivots.value + s, sizeof(int),
                                                  cudaMemcpyDeviceToHost, stream.value));
                            synchronize(stream.value, &result.executionTimes);
                            state.ot.pivots = static_cast<std::size_t>(pivots);
                        }
                    } else {
                        const auto otBegin = Clock::now();
                        try {
                            state.ot =
                                algorithm::exactLinearOt(g, n, m, config.otTolerance, &otWorkspaces[s]);
                        } catch (const std::runtime_error &error) {
                            result.executionTimes.cpuOtMilliseconds += elapsed(otBegin);
                            output.status = Status::NUMERICAL_FAILURE;
                            output.diagnostic = error.what();
                            output.correspondence = hostMatrix;
                            output.costs = state.costs;
                            output.finalGap = std::numeric_limits<double>::quiet_NaN();
                            active[s] = false;
                            continue;
                        }
                        result.executionTimes.cpuOtMilliseconds += elapsed(otBegin);
                        upload(candidate.value + offset, state.ot.correspondence, stream.value);
                    }
                    interval.start(stream.value, config.profileExecution);
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
                    if (output.status != Status::RUNNING || needMatrix) {
                        if (!needMatrix) {
                            check(cudaMemcpyAsync(hostMatrix.data(), current.value + offset,
                                                  count * sizeof(double), cudaMemcpyDeviceToHost,
                                                  stream.value));
                            synchronize(stream.value, &result.executionTimes);
                            state.marginals = algorithm::marginalErrors(hostMatrix, n, m);
                        }
                        output.correspondence = hostMatrix;
                    }
                    if (config.captureTrace) {
                        state.correspondence = hostMatrix;
                        state.gradient = std::move(g);
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
    Config config;
    std::unique_ptr<Baseline> baseline;
    std::mutex mutex;
    std::condition_variable available, space;
    std::deque<std::unique_ptr<Task>> pending;
    std::vector<std::thread> threads;
    SessionStatistics stats;
    bool closed = false;
    std::size_t capacity, inFlight = 0;
    int slotCount, dispatcherCount;

    Impl(Graph graph, const Config &settings) : config(settings) {
        algorithm::validateConfig(config);
        slotCount = std::min(config.gpuSlots, config.maxConcurrentComparisons);
        dispatcherCount = std::min(config.dispatcherThreads, slotCount);
        capacity = config.queueCapacity ? config.queueCapacity : 2 * static_cast<std::size_t>(slotCount);
        check(cudaSetDevice(config.device));
        baseline = std::make_unique<Baseline>(std::move(graph), config);
        stats.baselinePreparations = 1;
        std::vector<std::future<void>> ready;
        try {
            for (int d = 0; d < dispatcherCount; ++d) {
                std::promise<void> initialized;
                ready.push_back(initialized.get_future());
                threads.emplace_back([this, d, signal = std::move(initialized)]() mutable {
                    bool announced = false;
                    std::vector<std::unique_ptr<Slot>> slots;
                    auto account = [&](Slot &slot) {
                        auto current = slot.allocationStatistics();
                        stats.workerDeviceAllocations += current.first - slot.accounted.first;
                        stats.workerDeviceBytes += current.second - slot.accounted.second;
                        slot.accounted = current;
                    };
                    auto fail = [&](Slot &slot, std::exception_ptr error) {
                        // Error isolation requires draining before releasing uploads,
                        // baseline mappings or storage that the next task can reuse.
                        cudaStreamSynchronize(slot.stream.value);
                        auto task = std::move(slot.currentTask);
                        {
                            std::lock_guard<std::mutex> lock(mutex);
                            account(slot);
                            ++stats.failed;
                            --inFlight;
                        }
                        slot.reset();
                        task->promise.set_exception(error);
                    };
                    try {
                        check(cudaSetDevice(config.device));
                        for (int i = d; i < slotCount; i += dispatcherCount)
                            slots.push_back(std::make_unique<Slot>(config));
                        {
                            std::lock_guard<std::mutex> lock(mutex);
                            ++stats.workersCreated; // Retained field now counts host
                                                    // dispatchers.
                            stats.gpuSlotsCreated += slots.size();
                            for (auto &slot : slots)
                                account(*slot);
                        }
                        signal.set_value();
                        announced = true;
                        std::size_t cursor = 0;
                        for (;;) {
                            bool advanced = false;
                            Slot *oldest = nullptr;
                            std::size_t busy = 0;
                            for (std::size_t i = 0; i < slots.size(); ++i) {
                                auto &slot = *slots[(cursor + i) % slots.size()];
                                try {
                                    if (slot.phase == Slot::Phase::IDLE) {
                                        std::unique_ptr<Task> task;
                                        {
                                            std::lock_guard<std::mutex> lock(mutex);
                                            if (!pending.empty()) {
                                                task = std::move(pending.front());
                                                pending.pop_front();
                                                ++inFlight;
                                                stats.maximumInFlight =
                                                    std::max(stats.maximumInFlight, inFlight);
                                                if (task->baselineOverride)
                                                    ++stats.baselinePreparations;
                                            }
                                        }
                                        if (task) {
                                            space.notify_one();
                                            slot.prepare(std::move(task), *baseline);
                                            advanced = true;
                                        }
                                    } else {
                                        auto before = slot.submitted;
                                        if (slot.poll()) {
                                            auto task = std::move(slot.currentTask);
                                            auto result = std::move(slot.result);
                                            {
                                                std::lock_guard<std::mutex> lock(mutex);
                                                account(slot);
                                                ++stats.completed;
                                                --inFlight;
                                            }
                                            slot.reset();
                                            task->promise.set_value(std::move(result));
                                            advanced = true;
                                        } else if (slot.submitted != before)
                                            advanced = true;
                                    }
                                } catch (...) {
                                    if (!slot.currentTask)
                                        throw;
                                    fail(slot, std::current_exception());
                                    advanced = true;
                                }
                                if (slot.phase != Slot::Phase::IDLE) {
                                    ++busy;
                                    if (!oldest || slot.submitted < oldest->submitted)
                                        oldest = &slot;
                                }
                            }
                            cursor = (cursor + 1) % slots.size();
                            {
                                std::unique_lock<std::mutex> lock(mutex);
                                if (!busy && closed && pending.empty())
                                    break;
                                if (!advanced && busy < slots.size()) {
                                    // Idle capacity remains responsive to newly submitted inputs.
                                    if (!busy)
                                        available.wait(lock, [&] { return closed || !pending.empty(); });
                                    else
                                        available.wait_for(lock, std::chrono::microseconds(100),
                                                           [&] { return !pending.empty(); });
                                    continue;
                                }
                            }
                            if (!advanced && oldest) {
                                // A stream synchronisation has no timeout and can hide
                                // other ready slots behind a slow simplex. Query the
                                // oldest submission, then yield for a bounded interval.
                                try {
                                    auto status = cudaStreamQuery(oldest->stream.value);
                                    if (status != cudaSuccess && status != cudaErrorNotReady) check(status);
                                    if (status == cudaErrorNotReady) {
                                        std::unique_lock<std::mutex> lock(mutex);
                                        available.wait_for(lock, std::chrono::microseconds(50));
                                    }
                                } catch (...) {
                                    fail(*oldest, std::current_exception());
                                }
                            }
                        }
                    } catch (...) {
                        auto error = std::current_exception();
                        if (!announced)
                            signal.set_exception(error);
                        {
                            std::lock_guard<std::mutex> lock(mutex);
                            closed = true;
                            for (auto &task : pending) {
                                task->promise.set_exception(error);
                                ++stats.failed;
                            }
                            pending.clear();
                        }
                        for (auto &slot : slots)
                            if (slot->currentTask)
                                fail(*slot, error);
                        available.notify_all();
                        space.notify_all();
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
    std::future<Result> submit(std::unique_ptr<Task> task) {
        auto future = task->promise.get_future();
        {
            std::unique_lock<std::mutex> lock(mutex);
            space.wait(lock, [&] { return closed || pending.size() < capacity; });
            if (closed)
                throw std::runtime_error("FGW session is closed; target was not accepted.");
            pending.push_back(std::move(task));
            ++stats.accepted;
            stats.maximumQueued = std::max(stats.maximumQueued, pending.size());
        }
        available.notify_one();
        return future;
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
    auto task = std::make_unique<Task>();
    task->target = std::move(target);
    task->initialMatrices = std::move(initialMatrices);
    return impl->submit(std::move(task));
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
    Config settings = config;
    settings.maxConcurrentComparisons =
        static_cast<int>(std::min(inputs.size(), static_cast<std::size_t>(config.maxConcurrentComparisons)));
    CudaFgwSession session(inputs.front().left, settings);
    std::vector<std::future<Result>> futures;
    futures.reserve(inputs.size());
    for (const auto &input : inputs) {
        auto task = std::make_unique<Task>();
        task->target = input.right;
        task->initialMatrices = input.initialMatrices;
        if (!sameGraph(input.left, inputs.front().left))
            task->baselineOverride = std::make_unique<Graph>(input.left);
        futures.push_back(session.impl->submit(std::move(task)));
    }
    for (std::size_t i = 0; i < inputs.size(); ++i)
        results[i] = futures[i].get();
    return results;
}
} // namespace fgw
