#include "fgw_engine.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <numeric>
#include <queue>
#include <stdexcept>
#include <unordered_set>
#include <utility>

namespace fgw {
int Graph::nodeCount() const { return static_cast<int>(rowOffsets.size()) - 1; }
bool StartResult::converged() const { return status == Status::FW_GAP_REACHED; }
const char *statusName(Status status) {
    switch (status) {
    case Status::RUNNING:
        return "RUNNING";
    case Status::FW_GAP_REACHED:
        return "FW_GAP_REACHED";
    case Status::COST_STALLED:
        return "COST_STALLED";
    case Status::MAX_ITERATIONS:
        return "MAX_ITERATIONS";
    default:
        return "NUMERICAL_FAILURE";
    }
}
namespace algorithm {
std::vector<ProductOperation> matrixProductPlan(int n, int m) {
    // (D1 * P)^T = P^T * D1^T; structural matrices are symmetric.
    // (D1 * P * D2^T)^T = D2 * (D1 * P)^T.
    return {
        {ProductBuffer::Input, ProductBuffer::LeftDistances, ProductBuffer::Temporary, m, n, n, m, n, m},
        {ProductBuffer::RightDistances, ProductBuffer::Temporary, ProductBuffer::Output, m, n, m, m, m, m}};
}
UndirectedCsr validateGraph(const Graph &graph) {
    if (graph.rowOffsets.size() < 2 ||
        graph.rowOffsets.size() > static_cast<std::size_t>(std::numeric_limits<int>::max()) ||
        graph.colIndices.size() > static_cast<std::size_t>(std::numeric_limits<int>::max()))
        throw std::invalid_argument("FGW requires a nonempty CSR within signed integer dimensions.");
    const int n = graph.nodeCount();
    if (graph.rowOffsets.front() != 0 ||
        graph.rowOffsets.back() != static_cast<int>(graph.colIndices.size()) ||
        graph.embeddings.size() != static_cast<std::size_t>(n) * embeddingDimension ||
        graph.sources.size() != static_cast<std::size_t>(n) || graph.rootIndex < 0 || graph.rootIndex >= n ||
        graph.productId.empty() || graph.instanceId.empty())
        throw std::invalid_argument("FGW CSR, 384-dimensional embeddings or source "
                                    "mapping is missing/misaligned.");
    std::vector<std::vector<int>> adjacency(n);
    for (int i = 0; i < n; ++i) {
        if (graph.rowOffsets[i] < 0 || graph.rowOffsets[i] > graph.rowOffsets[i + 1] ||
            graph.rowOffsets[i + 1] > static_cast<int>(graph.colIndices.size()))
            throw std::invalid_argument("FGW CSR offsets must be monotone and within the edge array.");
        if (graph.sources[i].occurrenceId.empty())
            throw std::invalid_argument("FGW requires a source occurrence identifier for every node.");
        for (int e = graph.rowOffsets[i]; e < graph.rowOffsets[i + 1]; ++e) {
            int j = graph.colIndices[e];
            if (j < 0 || j >= n)
                throw std::invalid_argument("FGW CSR column index is out of range.");
            adjacency[i].push_back(j);
            adjacency[j].push_back(i);
        }
    }
    for (double value : graph.embeddings)
        if (!std::isfinite(value))
            throw std::invalid_argument("FGW embeddings must be finite.");
    std::vector<bool> visited(n, false);
    std::queue<int> queue;
    queue.push(0);
    visited[0] = true;
    while (!queue.empty()) {
        int i = queue.front();
        queue.pop();
        for (int j : adjacency[i])
            if (!visited[j]) {
                visited[j] = true;
                queue.push(j);
            }
    }
    if (std::find(visited.begin(), visited.end(), false) != visited.end())
        throw std::invalid_argument("FGW structural distances require a connected undirected graph.");
    UndirectedCsr result;
    result.offsets.push_back(0);
    for (auto &neighbours : adjacency) {
        std::sort(neighbours.begin(), neighbours.end());
        neighbours.erase(std::unique(neighbours.begin(), neighbours.end()), neighbours.end());
        if (result.columns.size() + neighbours.size() >
            static_cast<std::size_t>(std::numeric_limits<int>::max()))
            throw std::invalid_argument("FGW undirected CSR exceeds signed integer dimensions.");
        result.columns.insert(result.columns.end(), neighbours.begin(), neighbours.end());
        result.offsets.push_back(static_cast<int>(result.columns.size()));
    }
    return result;
}
void validateConfig(const Config &c) {
    for (double v : {c.alpha, c.tolAbs, c.tolRel, c.tolGapAbs, c.tolGapRel, c.costScaleFloor,
                     c.feasibilityTolerance, c.numericalTolerance, c.otTolerance})
        if (!std::isfinite(v) || v < 0)
            throw std::invalid_argument("FGW configuration must be finite and nonnegative.");
    if (c.alpha > 1 || c.costScaleFloor <= 0 || c.otTolerance <= 0 || c.feasibilityTolerance <= 0 ||
        c.numericalTolerance <= 0 || c.maxIterations < 0 || c.device < 0 || c.maxConcurrentComparisons < 1 ||
        c.workspaceBytes < 256 || c.gpuSlots < 1 || c.dispatcherThreads < 1)
        throw std::invalid_argument("FGW alpha, tolerances, iteration limit or "
                                    "device/batch configuration is invalid.");
}
MarginalErrors marginalErrors(const std::vector<double> &matrix, int n, int m) {
    MarginalErrors out;
    if (n < 1 || m < 1 || matrix.size() != static_cast<std::size_t>(n) * m) {
        out.finite = false;
        return out;
    }
    std::vector<double> columns(m, 0);
    double total = 0;
    out.minimum = std::numeric_limits<double>::infinity();
    for (int i = 0; i < n; ++i) {
        double row = 0;
        for (int j = 0; j < m; ++j) {
            double v = matrix[static_cast<std::size_t>(i) * m + j];
            out.finite = out.finite && std::isfinite(v);
            out.minimum = std::min(out.minimum, v);
            row += v;
            columns[j] += v;
        }
        out.row = std::max(out.row, std::abs(row - 1.0 / n));
        total += row;
    }
    for (double v : columns)
        out.column = std::max(out.column, std::abs(v - 1.0 / m));
    out.total = std::abs(total - 1);
    return out;
}
void validateInitial(const std::vector<double> &matrix, int n, int m, const Config &c) {
    auto e = marginalErrors(matrix, n, m);
    if (!e.finite || e.minimum < 0 || e.row > c.feasibilityTolerance || e.column > c.feasibilityTolerance ||
        e.total > c.feasibilityTolerance)
        throw std::invalid_argument("FGW initial correspondence must be finite, "
                                    "nonnegative and have uniform marginals.");
}
void validateComparison(const Comparison &input, const Config &c) {
    validateConfig(c);
    validateGraph(input.left);
    validateGraph(input.right);
    if (input.left.productId != input.right.productId)
        throw std::invalid_argument("FGW compares instances of the same product.");
    for (const auto &initial : input.initialMatrices)
        validateInitial(initial, input.left.nodeCount(), input.right.nodeCount(), c);
}

/** Specialised primal network simplex for the complete bipartite transport LP.
 * Uniform marginals are integral in units of 1/lcm(n,m), so pivot mass is exact
 * even for unequal sizes. A spanning-tree basis supports divisible transport;
 * this is not a one-to-one assignment or an entropic approximation.
 */
LinearOtResult exactLinearOt(const std::vector<double> &cost, int n, int m, double tolerance,
                             LinearOtWorkspace *workspace) {
    if (n < 1 || m < 1 || cost.size() != static_cast<std::size_t>(n) * m ||
        n > std::numeric_limits<int>::max() - m || !std::isfinite(tolerance) || tolerance <= 0)
        throw std::invalid_argument("Invalid exact linear OT dimensions/tolerance.");
    double scale = 1;
    for (double value : cost) {
        if (!std::isfinite(value))
            throw std::runtime_error("Nonfinite exact linear OT cost.");
        scale = std::max(scale, std::abs(value));
    }
    const double costEpsilon = tolerance * scale;
    const int nodes = n + m, divisor = std::gcd(n, m);
    const std::int64_t rowUnits = m / divisor, columnUnits = n / divisor;
    const std::int64_t totalUnits = static_cast<std::int64_t>(n) * rowUnits;
    std::vector<std::int64_t> flow(cost.size(), 0);
    std::vector<bool> basic(cost.size(), false);
    std::vector<std::vector<int>> adjacency(nodes);
    std::size_t basisHash = 0;
    auto arcHash = [](std::size_t index) {
        std::uint64_t value = index + UINT64_C(0x9e3779b97f4a7c15);
        value = (value ^ (value >> 30)) * UINT64_C(0xbf58476d1ce4e5b9);
        value = (value ^ (value >> 27)) * UINT64_C(0x94d049bb133111eb);
        return static_cast<std::size_t>(value ^ (value >> 31));
    };
    auto addBasis = [&](int row, int column) {
        const auto index = static_cast<std::size_t>(row) * m + column;
        basic[index] = true;
        basisHash ^= arcHash(index);
        adjacency[row].push_back(n + column);
        adjacency[n + column].push_back(row);
    };
    if (workspace && workspace->rows == n && workspace->columns == m) {
        if (workspace->flow.size() != cost.size() ||
            workspace->basis.size() != static_cast<std::size_t>(nodes - 1))
            throw std::invalid_argument("Invalid exact OT cached basis dimensions.");
        flow = workspace->flow;
        std::vector<std::int64_t> columns(m, 0);
        for (int i = 0; i < n; ++i) {
            std::int64_t sum = 0;
            for (int j = 0; j < m; ++j) {
                auto mass = flow[static_cast<std::size_t>(i) * m + j];
                if (mass < 0 || mass > totalUnits)
                    throw std::invalid_argument("Invalid exact OT cached mass.");
                sum += mass;
                columns[j] += mass;
            }
            if (sum != rowUnits)
                throw std::invalid_argument("Invalid exact OT cached row marginal.");
        }
        for (auto mass : columns)
            if (mass != columnUnits)
                throw std::invalid_argument("Invalid exact OT cached column marginal.");
        for (auto index : workspace->basis) {
            if (index >= cost.size() || basic[index])
                throw std::invalid_argument("Invalid exact OT cached basis arc.");
            addBasis(static_cast<int>(index / m), static_cast<int>(index % m));
        }
        for (std::size_t index = 0; index < flow.size(); ++index)
            if (flow[index] && !basic[index])
                throw std::invalid_argument("Exact OT cached mass is not basic.");
    } else {
        // North-west corner gives a feasible tree; retain explicit zero basic arcs
        // at simultaneous exhaustion to connect its degenerate components.
        int row = 0, column = 0;
        std::int64_t supply = rowUnits, demand = columnUnits;
        while (row < n && column < m) {
            const auto mass = std::min(supply, demand);
            flow[static_cast<std::size_t>(row) * m + column] = mass;
            addBasis(row, column);
            supply -= mass;
            demand -= mass;
            if (supply == 0 && demand == 0) {
                if (row + 1 < n && column + 1 < m)
                    addBasis(row, column + 1);
                ++row;
                ++column;
                supply = rowUnits;
                demand = columnUnits;
            } else if (supply == 0) {
                ++row;
                supply = rowUnits;
            } else {
                ++column;
                demand = columnUnits;
            }
        }
    }
    std::vector<double> dual(nodes, 0);
    std::vector<int> parent(nodes), queue(nodes);
    std::vector<bool> visited(nodes);
    std::size_t cursor = 0;
    const std::size_t blockSize = std::max<std::size_t>(16, std::sqrt(static_cast<double>(cost.size())));
    bool bland = false;
    std::size_t pivots = 0, degeneratePivots = 0;
    std::unordered_set<std::size_t> degenerateBases;
    auto indexOf = [&](int a, int b) -> std::size_t {
        return a < n ? static_cast<std::size_t>(a) * m + b - n : static_cast<std::size_t>(b) * m + a - n;
    };
    for (;;) {
        // u[row] + v[column] equals each basic cost. Tree traversal computes
        // feasible dual candidates afresh without accumulating pivot rounding.
        std::fill(visited.begin(), visited.end(), false);
        std::size_t head = 0, tail = 1;
        queue[0] = 0;
        visited[0] = true;
        dual[0] = 0;
        while (head < tail) {
            const int node = queue[head++];
            for (int neighbour : adjacency[node])
                if (!visited[neighbour]) {
                    dual[neighbour] = cost[indexOf(node, neighbour)] - dual[node];
                    visited[neighbour] = true;
                    queue[tail++] = neighbour;
                }
        }
        if (tail != static_cast<std::size_t>(nodes))
            throw std::runtime_error("Exact OT basis is disconnected.");
        std::size_t entering = cost.size();
        double bestReduced = -costEpsilon;
        // Block pricing avoids scanning every dense arc at every pivot. No
        // candidate is accepted until its block is examined; absence of a
        // candidate requires a complete pass, preserving exact LP optimality.
        for (std::size_t examined = 0; examined < cost.size(); ++examined) {
            const std::size_t index = bland ? examined : cursor;
            if (!bland)
                cursor = (cursor + 1) % cost.size();
            if (!basic[index]) {
                double reduced = cost[index] - dual[index / m] - dual[n + index % m];
                if (reduced < bestReduced) {
                    bestReduced = reduced;
                    entering = index;
                }
            }
            if (entering != cost.size() && (bland || (examined + 1) % blockSize == 0))
                break;
        }
        if (entering == cost.size())
            break;
        const int enteringRow = static_cast<int>(entering / m),
                  enteringColumn = n + static_cast<int>(entering % m);
        // Adding an arc creates one alternating cycle. Find its tree path;
        // minus arcs bound the feasible pivot mass and choose the leaving arc.
        std::fill(parent.begin(), parent.end(), -1);
        head = 0;
        tail = 1;
        queue[0] = enteringColumn;
        parent[enteringColumn] = enteringColumn;
        while (head < tail && parent[enteringRow] == -1) {
            const int node = queue[head++];
            for (int neighbour : adjacency[node])
                if (parent[neighbour] == -1) {
                    parent[neighbour] = node;
                    queue[tail++] = neighbour;
                }
        }
        if (parent[enteringRow] == -1)
            throw std::runtime_error("Exact OT cannot find its pivot cycle.");
        std::vector<std::size_t> cycle;
        for (int node = enteringRow; node != enteringColumn; node = parent[node])
            cycle.push_back(indexOf(node, parent[node]));
        std::int64_t mass = totalUnits;
        std::size_t leaving = cost.size();
        for (std::size_t k = 0; k < cycle.size(); k += 2) {
            auto index = cycle[k];
            if (flow[index] < mass || (flow[index] == mass && index < leaving)) {
                mass = flow[index];
                leaving = index;
            }
        }
        if (leaving == cost.size() || mass < 0)
            throw std::runtime_error("Exact OT invalid pivot mass.");
        ++pivots;
        degeneratePivots += mass == 0;
        flow[entering] += mass;
        for (std::size_t k = 0; k < cycle.size(); ++k)
            flow[cycle[k]] += k % 2 ? mass : -mass;
        int leavingRow = static_cast<int>(leaving / m), leavingColumn = n + static_cast<int>(leaving % m);
        auto remove = [&](int node, int neighbour) {
            auto &edges = adjacency[node];
            edges.erase(std::find(edges.begin(), edges.end(), neighbour));
        };
        remove(leavingRow, leavingColumn);
        remove(leavingColumn, leavingRow);
        basic[leaving] = false;
        basisHash ^= arcHash(leaving);
        addBasis(enteringRow, enteringColumn - n);
        if (mass > 0)
            degenerateBases.clear();
        else if (!bland) {
            // Repeated degenerate bases trigger Bland's entering/leaving rule.
            // Hash collisions only enable the conservative rule earlier.
            if (!degenerateBases.insert(basisHash).second) {
                bland = true;
                degenerateBases.clear();
            }
        }
    }
    LinearOtResult result;
    result.pivots = pivots;
    result.degeneratePivots = degeneratePivots;
    result.usedBlandRule = bland;
    result.correspondence.resize(cost.size());
    for (std::size_t index = 0; index < cost.size(); ++index) {
        result.correspondence[index] = static_cast<double>(flow[index]) / totalUnits;
        result.linearCost += cost[index] * result.correspondence[index];
        result.maximumReducedCostViolation = std::max(result.maximumReducedCostViolation,
                                                      -(cost[index] - dual[index / m] - dual[n + index % m]));
    }
    for (int i = 0; i < n; ++i)
        result.dualCost += dual[i] / n;
    for (int j = 0; j < m; ++j)
        result.dualCost += dual[n + j] / m;
    auto errors = marginalErrors(result.correspondence, n, m);
    if (!errors.finite || errors.minimum < 0 ||
        std::max({errors.row, errors.column, errors.total}) > tolerance * 16 ||
        result.maximumReducedCostViolation > costEpsilon * 16 ||
        std::abs(result.linearCost - result.dualCost) > costEpsilon * 32)
        throw std::runtime_error("Exact OT failed primal/dual optimality certificate.");
    if (workspace) {
        workspace->rows = n;
        workspace->columns = m;
        workspace->flow = std::move(flow);
        workspace->basis.clear();
        for (std::size_t index = 0; index < basic.size(); ++index)
            if (basic[index])
                workspace->basis.push_back(index);
    }
    return result;
}

bool linearOtCertified(const LinearOtResult &result, const MarginalErrors &errors, double costEpsilon,
                       double tolerance) {
    return std::isfinite(result.linearCost) && std::isfinite(result.dualCost) &&
           std::isfinite(result.maximumReducedCostViolation) && std::isfinite(costEpsilon) &&
           costEpsilon > 0 && tolerance > 0 && errors.finite && errors.minimum >= 0 &&
           std::max({errors.row, errors.column, errors.total}) <= tolerance * 16 &&
           result.maximumReducedCostViolation <= costEpsilon * 16 &&
           std::abs(result.linearCost - result.dualCost) <= costEpsilon * 32;
}
Costs combineCosts(double material, double structure, double alpha) {
    return {material, structure, (1 - alpha) * material + alpha * structure};
}
double fwGap(double directionalGradient) { return -directionalGradient; }
LineSearch quadraticLineSearch(double directionProduct, double directionalGradient, double alpha,
                               double tolerance) {
    LineSearch result{-2 * alpha * directionProduct, directionalGradient, 0};
    if (!std::isfinite(result.a) || !std::isfinite(result.b))
        throw std::runtime_error("Nonfinite FGW line-search coefficients.");
    double best = 0;
    auto consider = [&](double gamma) {
        double change = result.a * gamma * gamma + result.b * gamma;
        if (change < best) {
            best = change;
            result.gamma = gamma;
        }
    };
    consider(1);
    // Near-zero curvature is handled without division; endpoints still use the
    // true coefficients. Positive tiny curvature is considered when b is tiny
    // too.
    if (result.a > 0 && (result.a > tolerance || std::abs(result.b) <= 2 * result.a))
        consider(std::clamp(-result.b / (2 * result.a), 0.0, 1.0));
    return result;
}
Status stoppingStatus(double gap, const Costs &costs, const MarginalErrors &e, int updates, double previous,
                      bool hasPrevious, const Config &c) {
    if (!std::isfinite(gap) || !std::isfinite(costs.fgw) || !std::isfinite(costs.material) ||
        !std::isfinite(costs.structure) || !e.finite || e.minimum < -c.feasibilityTolerance ||
        std::max({e.row, e.column, e.total}) > c.feasibilityTolerance ||
        gap < -c.numericalTolerance * std::max(std::abs(costs.fgw), c.costScaleFloor))
        return Status::NUMERICAL_FAILURE;
    if (gap <= c.tolGapAbs + c.tolGapRel * std::max(std::abs(costs.fgw), c.costScaleFloor))
        return Status::FW_GAP_REACHED;
    if (updates >= c.maxIterations)
        return Status::MAX_ITERATIONS;
    if (hasPrevious) {
        double change = std::abs(previous - costs.fgw);
        if (change <= c.tolAbs || change / std::max(std::abs(previous), c.costScaleFloor) <= c.tolRel)
            return Status::COST_STALLED;
    }
    return Status::RUNNING;
}
bool objectiveIncreased(double previous, double current, const Config &c) {
    return !std::isfinite(current) ||
           current > previous + c.numericalTolerance * std::max(1.0, std::abs(previous));
}
std::size_t selectBestStart(const std::vector<StartResult> &starts) {
    std::size_t best = 0;
    bool found = false;
    for (std::size_t i = 0; i < starts.size(); ++i)
        if (starts[i].status != Status::NUMERICAL_FAILURE && std::isfinite(starts[i].costs.fgw))
            if (!found || starts[i].costs.fgw < starts[best].costs.fgw) {
                best = i;
                found = true;
            }
    if (!found)
        throw std::runtime_error("All FGW starts failed numerical validation.");
    return best;
}

} // namespace algorithm
} // namespace fgw

#define CL_TARGET_OPENCL_VERSION 120
#include <CL/cl.h>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <fstream>
#include <map>
#include <mutex>
#include <sstream>
#include <thread>

namespace fgw {
namespace {
using Clock = std::chrono::steady_clock;
double elapsed(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}
void check(cl_int error) {
    if (error != CL_SUCCESS)
        throw std::runtime_error("FGW OpenCL status " + std::to_string(error));
}
template <class T, cl_int (*release)(T)> struct Resource {
    T value = nullptr;
    ~Resource() {
        if (value)
            release(value);
    }
    Resource() = default;
    Resource(const Resource &) = delete;
    Resource &operator=(const Resource &) = delete;
};
using Context = Resource<cl_context, clReleaseContext>;
using Program = Resource<cl_program, clReleaseProgram>;
using Queue = Resource<cl_command_queue, clReleaseCommandQueue>;
using Kernel = Resource<cl_kernel, clReleaseKernel>;
struct Device {
    Context context;
    Program program;
    cl_device_id id{};
    bool simplex = false;
    size_t regularGroup = 1, reductionGroup = 1;
    std::string fallback;
    explicit Device(const Config &c) {
        cl_uint count = 0;
        check(clGetPlatformIDs(0, nullptr, &count));
        std::vector<cl_platform_id> platforms(count);
        check(clGetPlatformIDs(count, platforms.data(), nullptr));
        if (c.platform < 0 || static_cast<unsigned>(c.platform) >= count)
            throw std::invalid_argument("FGW OpenCL platform index unavailable.");
        check(clGetDeviceIDs(platforms[c.platform], CL_DEVICE_TYPE_ALL, 0, nullptr, &count));
        std::vector<cl_device_id> devices(count);
        check(clGetDeviceIDs(platforms[c.platform], CL_DEVICE_TYPE_ALL, count, devices.data(), nullptr));
        if (static_cast<unsigned>(c.device) >= count)
            throw std::invalid_argument("FGW OpenCL device index unavailable.");
        id = devices[c.device];
        cl_device_fp_config fp = 0;
        check(clGetDeviceInfo(id, CL_DEVICE_DOUBLE_FP_CONFIG, sizeof(fp), &fp, nullptr));
        if (!fp)
            throw std::runtime_error("FGW requires OpenCL FP64; selected device has no double support.");
        cl_int error;
        context.value = clCreateContext(nullptr, 1, &id, nullptr, nullptr, &error);
        check(error);
        std::ifstream in(c.kernelPath);
        if (!in)
            throw std::runtime_error("Cannot read FGW OpenCL kernel: " + c.kernelPath);
        std::string source((std::istreambuf_iterator<char>(in)), {});
        const char *text = source.c_str();
        size_t length = source.size();
        program.value = clCreateProgramWithSource(context.value, 1, &text, &length, &error);
        check(error);
        error = clBuildProgram(program.value, 1, &id, "-cl-std=CL1.2", nullptr, nullptr);
        if (error != CL_SUCCESS) {
            size_t bytes = 0;
            clGetProgramBuildInfo(program.value, id, CL_PROGRAM_BUILD_LOG, 0, nullptr, &bytes);
            std::string log(bytes, '\0');
            clGetProgramBuildInfo(program.value, id, CL_PROGRAM_BUILD_LOG, bytes, log.data(), nullptr);
            throw std::runtime_error("FGW OpenCL build failed: " + log);
        }
        Kernel kernel;
        kernel.value = clCreateKernel(program.value, "transportationSimplexKernel", &error);
        check(error);
        size_t group = 0;
        cl_ulong local = 0, required = 0;
        check(clGetKernelWorkGroupInfo(kernel.value, id, CL_KERNEL_WORK_GROUP_SIZE, sizeof(group), &group,
                                       nullptr));
        check(clGetDeviceInfo(id, CL_DEVICE_LOCAL_MEM_SIZE, sizeof(local), &local, nullptr));
        check(clGetKernelWorkGroupInfo(kernel.value, id, CL_KERNEL_LOCAL_MEM_SIZE, sizeof(required),
                                       &required, nullptr));
        simplex = group >= 256 && local >= required;
        size_t maxGroup = 0;
        check(clGetDeviceInfo(id, CL_DEVICE_MAX_WORK_GROUP_SIZE, sizeof(maxGroup), &maxGroup, nullptr));
        size_t dimensions = 0;
        cl_uint dim = 0;
        check(clGetDeviceInfo(id, CL_DEVICE_MAX_WORK_ITEM_DIMENSIONS, sizeof(dim), &dim, nullptr));
        std::vector<size_t> sizes(dim);
        check(clGetDeviceInfo(id, CL_DEVICE_MAX_WORK_ITEM_SIZES, sizes.size() * sizeof(size_t), sizes.data(),
                              nullptr));
        dimensions = sizes[0];
        maxGroup = std::min(maxGroup, dimensions);
        simplex = simplex && maxGroup >= 256;
        regularGroup = std::min<size_t>(64, maxGroup);
        while (reductionGroup * 2 <= std::min<size_t>(256, maxGroup))
            reductionGroup *= 2;
        if (!simplex)
            fallback =
                "device simplex requires 256 work-items and " + std::to_string(required) + " local bytes";
    }
};
struct Buffer {
    cl_mem value = nullptr;
    size_t bytes = 0, allocations = 0;
    ~Buffer() {
        if (value)
            clReleaseMemObject(value);
    }
    void reserve(Device &d, size_t count) {
        if (count <= bytes)
            return;
        cl_int error;
        cl_mem next = clCreateBuffer(d.context.value, CL_MEM_READ_WRITE, count, nullptr, &error);
        check(error);
        if (value)
            clReleaseMemObject(value);
        value = next;
        bytes = count;
        ++allocations;
    }
};
struct Executor {
    Device &device;
    Queue queue;
    double deviceMilliseconds = 0, synchronizationMilliseconds = 0, simplexMilliseconds = 0;
    std::map<std::string, std::unique_ptr<Buffer>> buffers;
    std::map<std::string, std::unique_ptr<Kernel>> kernels;
    explicit Executor(Device &d, const Config &c) : device(d) {
        cl_int error;
        queue.value = clCreateCommandQueue(d.context.value, d.id,
                                           c.profileExecution ? CL_QUEUE_PROFILING_ENABLE : 0, &error);
        check(error);
    }
    ~Executor() {
        if (queue.value)
            clFinish(queue.value);
    }
    Buffer &buffer(const std::string &name, size_t bytes) {
        auto &b = buffers[name];
        if (!b)
            b = std::make_unique<Buffer>();
        b->reserve(device, std::max<size_t>(bytes, 1));
        return *b;
    }
    template <class T> void upload(Buffer &b, const std::vector<T> &v) {
        if (!v.empty())
            check(clEnqueueWriteBuffer(queue.value, b.value, CL_TRUE, 0, v.size() * sizeof(T), v.data(), 0,
                                       nullptr, nullptr));
    }
    template <class T = double> std::vector<T> download(Buffer &b, size_t n) {
        std::vector<T> v(n);
        if (n)
            check(clEnqueueReadBuffer(queue.value, b.value, CL_TRUE, 0, n * sizeof(T), v.data(), 0, nullptr,
                                      nullptr));
        return v;
    }
    void arg(cl_kernel k, cl_uint i, Buffer &b) { check(clSetKernelArg(k, i, sizeof(cl_mem), &b.value)); }
    template <class T> void arg(cl_kernel k, cl_uint i, const T &v) {
        check(clSetKernelArg(k, i, sizeof(T), &v));
    }
    template <class... A> double launch(const char *name, size_t count, size_t local, A &...args) {
        auto &entry = kernels[name];
        if (!entry) {
            entry = std::make_unique<Kernel>();
            cl_int error;
            entry->value = clCreateKernel(device.program.value, name, &error);
            check(error);
        }
        cl_uint index = 0;
        (arg(entry->value, index++, args), ...);
        if (local == 1)
            local = device.regularGroup;
        if (std::string(name) == "dotKernel") {
            local = device.reductionGroup;
            count = local;
        }
        size_t kernelLimit = 0;
        check(clGetKernelWorkGroupInfo(entry->value, device.id, CL_KERNEL_WORK_GROUP_SIZE,
                                       sizeof(kernelLimit), &kernelLimit, nullptr));
        if (local > kernelLimit && std::string(name) != "transportationSimplexKernel") {
            local = 1;
            while (local * 2 <= kernelLimit && local * 2 <= device.reductionGroup)
                local *= 2;
            if (std::string(name) == "dotKernel")
                count = local;
        }
        size_t global = ((count + local - 1) / local) * local;
        Resource<cl_event, clReleaseEvent> event;
        check(clEnqueueNDRangeKernel(queue.value, entry->value, 1, nullptr, &global, &local, 0, nullptr,
                                     &event.value));
        auto waitBegin = Clock::now();
        check(clWaitForEvents(1, &event.value));
        synchronizationMilliseconds += elapsed(waitBegin);
        cl_ulong begin = 0, end = 0;
        // Profiling is optional; absent timestamps are deliberately zero.
        if (clGetEventProfilingInfo(event.value, CL_PROFILING_COMMAND_START, sizeof(begin), &begin,
                                    nullptr) == CL_SUCCESS)
            check(clGetEventProfilingInfo(event.value, CL_PROFILING_COMMAND_END, sizeof(end), &end, nullptr));
        double milliseconds = (end - begin) * 1e-6;
        deviceMilliseconds += milliseconds;
        if (std::string(name) == "transportationSimplexKernel")
            simplexMilliseconds += milliseconds;
        return milliseconds;
    }
    void prepare(const Graph &g, const std::string &prefix) {
        auto csr = algorithm::validateGraph(g);
        int n = g.nodeCount();
        auto &offset = buffer(prefix + "offset", csr.offsets.size() * 4),
             &column = buffer(prefix + "column", csr.columns.size() * 4),
             &emb = buffer(prefix + "emb", g.embeddings.size() * 8),
             &dist = buffer(prefix + "dist", size_t(n) * n * 8),
             &bfs = buffer(prefix + "bfs", size_t(n) * n * 4), &weight = buffer(prefix + "weight", n * 8),
             &norm = buffer(prefix + "norm", n * 8);
        upload(offset, csr.offsets);
        upload(column, csr.columns);
        upload(emb, g.embeddings);
        launch("structuralDistanceKernel", n, 1, offset, column, dist, bfs, n);
        launch("uniformKernel", n, 1, weight, n);
        launch("squaredMarginalKernel", n, 1, dist, weight, norm, n);
    }
    std::pair<size_t, size_t> accounting() const {
        size_t count = 0, bytes = 0;
        for (const auto &b : buffers) {
            count += b.second->allocations;
            bytes += b.second->bytes;
        }
        return {count, bytes};
    }
    LinearOtResult ot(Buffer &cost, int n, int m, bool warm, const Config &c) {
        using namespace algorithm;
        cl_ulong count = size_t(n) * m;
        auto &flow = buffer("flow", count * 4), &basic = buffer("basic", count),
             &candidate = buffer("S", count * 8), &pivots = buffer("pivots", 4),
             &summary = buffer("summary", SummaryCount * 8);
        int warmInt = warm, incremental = c.incrementalOtTree, pricing = c.candidateOtPricing;
        // OpenCL bool kernel arguments are represented as int by the kernel
        // contract.
        double tol = c.otTolerance;
        launch("transportationSimplexKernel", 256, 256, cost, flow, basic, candidate, pivots, n, m, tol,
               warmInt, count, summary, incremental, pricing);
        auto values = download(summary, SummaryCount);
        auto pivot = download<int>(pivots, 1)[0];
        if (pivot < 0 || values[OtSuccess] != 1)
            throw std::runtime_error("OpenCL device exact OT failed basis/finite/pivot-limit checks.");
        LinearOtResult out;
        out.correspondence = download(candidate, count);
        out.linearCost = values[LinearCost];
        out.dualCost = values[DualCost];
        out.maximumReducedCostViolation = values[ReducedCostViolation];
        out.pivots = pivot;
        out.fullPricingScans = values[OtFullPricingScans];
        out.candidatePricingPasses = values[OtCandidatePricingPasses];
        out.treeRebuilds = values[OtTreeRebuilds];
        out.incrementalTreeUpdates = values[OtIncrementalTreeUpdates];
        out.treeNodesVisited = values[OtTreeNodesVisited];
        out.cycleEdges = values[OtCycleEdges];
        if (!linearOtCertified(out, marginalErrors(out.correspondence, n, m), values[OtCostEpsilon],
                               c.otTolerance))
            throw std::runtime_error("OpenCL device OT failed original "
                                     "marginals/primal/dual certificate.");
        return out;
    }
};
struct Baseline {
    Graph graph;
    Executor prepared;
    Baseline(Graph g, Device &d, const Config &c) : graph(std::move(g)), prepared(d, c) {
        prepared.prepare(graph, "L");
    }
};
SourceMapping mapping(const Graph &g) { return {g.productId, g.instanceId, g.rootIndex, g.sources}; }
Result comparePrepared(Executor &x, Baseline &base, const Graph &target,
                       const std::vector<std::vector<double>> &initials, const Config &c) {
    using namespace algorithm;
    const auto begin = Clock::now();
    double deviceBegin = x.deviceMilliseconds, waitBegin = x.synchronizationMilliseconds,
           simplexBegin = x.simplexMilliseconds;
    validateGraph(target);
    if (base.graph.productId != target.productId)
        throw std::invalid_argument("FGW compares instances of the same product.");
    int n = base.graph.nodeCount(), m = target.nodeCount();
    cl_ulong count = size_t(n) * m;
    for (const auto &p : initials)
        validateInitial(p, n, m, c);
    x.prepare(target, "R");
    auto &left = base.prepared.buffers;
    auto &emb = *left.at("Lemb"), &d1 = *left.at("Ldist"), &norm = *left.at("Lnorm");
    auto &rightEmb = *x.buffers.at("Remb"), &d2 = *x.buffers.at("Rdist"), &rightNorm = *x.buffers.at("Rnorm");
    auto &material = x.buffer("material", count * 8), &fixed = x.buffer("fixed", count * 8),
         &current = x.buffer("P", count * 8), &candidate = x.buffer("S", count * 8),
         &direction = x.buffer("delta", count * 8), &temporary = x.buffer("temp", count * 8),
         &product = x.buffer("product", count * 8), &h = x.buffer("H", count * 8),
         &gradient = x.buffer("G", count * 8), &scalars = x.buffer("scalar", 4 * 8);
    x.launch("materialDistanceKernel", count, 1, emb, rightEmb, material, n, m);
    x.launch("fixedTermKernel", count, 1, norm, rightNorm, fixed, n, m);
    Result result;
    result.rows = n;
    result.columns = m;
    result.left = mapping(base.graph);
    result.right = mapping(target);
    result.p.assign(n, 1.0 / n);
    result.q.assign(m, 1.0 / m);
    bool gpu =
        x.device.simplex && n + m <= 320 && !c.captureTrace && c.otSolver == LinearOtSolver::GPU_SIMPLEX;
    result.otExecution = gpu ? "OPENCL_DEVICE_EXACT_SIMPLEX"
                             : "EXACT_CPU_OT_FALLBACK: " + (c.captureTrace ? std::string("captureTrace")
                                                            : c.otSolver == LinearOtSolver::CPU_REFERENCE
                                                                ? std::string("explicit CPU_REFERENCE")
                                                            : n + m > 320 ? std::string("N+M exceeds 320")
                                                                          : x.device.fallback);
    if (c.captureTrace) {
        result.materialDistances = x.download(material, count);
        result.leftDistances = base.prepared.download(d1, size_t(n) * n);
        result.rightDistances = x.download(d2, size_t(m) * m);
    }
    auto products = [&](Buffer &input) {
        x.launch("productLeft", count, 1, d1, input, temporary, n, m);
        x.launch("productRight", count, 1, temporary, d2, product, n, m);
    };
    auto dot = [&](Buffer &a, Buffer &b, int index) {
        x.launch("dotKernel", 256, 256, a, b, scalars, count, index);
        return x.download(scalars, 4)[index];
    };
    const size_t starts = initials.empty() ? 1 : initials.size();
    for (size_t s = 0; s < starts; ++s) {
        std::vector<double> p = initials.empty() ? std::vector<double>(count, 1.0 / n / m) : initials[s];
        x.upload(current, p);
        StartResult out;
        LinearOtWorkspace workspace;
        bool previousExists = false;
        double previous = 0;
        for (;;) {
            Iteration state;
            state.updates = out.iterations;
            products(current);
            double alpha = c.alpha;
            x.launch("costGradientKernel", count, 1, material, fixed, product, h, gradient, alpha, count);
            state.costs = combineCosts(dot(material, current, 0), dot(h, current, 1), c.alpha);
            p = x.download(current, count);
            state.marginals = marginalErrors(p, n, m);
            try {
                auto otBegin = Clock::now();
                if (gpu) {
                    state.ot = x.ot(gradient, n, m, previousExists, c);
                } else {
                    state.ot = exactLinearOt(x.download(gradient, count), n, m, c.otTolerance, &workspace);
                    x.upload(candidate, state.ot.correspondence);
                    result.executionTimes.cpuOtMilliseconds += elapsed(otBegin);
                }
                x.launch("directionKernel", count, 1, candidate, current, direction, count);
                double b = dot(gradient, direction, 2);
                state.gap = fwGap(b);
                out.status = stoppingStatus(state.gap, state.costs, state.marginals, out.iterations, previous,
                                            previousExists, c);
                if (previousExists) {
                    state.absoluteCostChange = std::abs(previous - state.costs.fgw);
                    state.relativeCostChange =
                        state.absoluteCostChange / std::max(std::abs(previous), c.costScaleFloor);
                }
                if (previousExists && objectiveIncreased(previous, state.costs.fgw, c)) {
                    out.status = Status::NUMERICAL_FAILURE;
                    out.diagnostic = "Exact line search increased the true objective.";
                }
                if (out.status == Status::RUNNING) {
                    products(direction);
                    state.step =
                        quadraticLineSearch(dot(product, direction, 3), b, c.alpha, c.numericalTolerance);
                }
            } catch (const std::runtime_error &error) {
                out.status = Status::NUMERICAL_FAILURE;
                out.diagnostic = error.what();
                state.gap = std::numeric_limits<double>::quiet_NaN();
            }
            out.correspondence = p;
            out.costs = state.costs;
            out.finalGap = state.gap;
            if (c.captureTrace) {
                state.correspondence = p;
                state.gradient = x.download(gradient, count);
                state.direction = x.download(direction, count);
            } else
                state.ot.correspondence.clear();
            if (out.status == Status::NUMERICAL_FAILURE && out.diagnostic.empty())
                out.diagnostic = "Nonfinite costs, failed marginals or significantly "
                                 "negative fresh FW gap.";
            out.trace.push_back(state);
            if (out.status != Status::RUNNING)
                break;
            previous = state.costs.fgw;
            previousExists = true;
            x.launch("updateKernel", count, 1, current, direction, state.step.gamma, count);
            ++out.iterations;
        }
        result.starts.push_back(std::move(out));
    }
    try {
        result.bestStart = selectBestStart(result.starts);
    } catch (const std::runtime_error &) {
        result.bestStart = 0;
    }
    result.executionTimes.deviceMilliseconds = x.deviceMilliseconds - deviceBegin;
    result.executionTimes.synchronizationMilliseconds = x.synchronizationMilliseconds - waitBegin;
    result.executionTimes.gpuOtMilliseconds = x.simplexMilliseconds - simplexBegin;
    result.totalMilliseconds = elapsed(begin);
    return result;
}
} // namespace
namespace algorithm {
std::pair<LinearOtResult, LinearOtResult> deviceOtFixture(const std::vector<double> &cost, int n, int m,
                                                          const Config &c) {
    validateConfig(c);
    if (n < 1 || m < 1 || n + m > 320 || cost.size() != size_t(n) * m)
        throw std::invalid_argument("Invalid device OT fixture dimensions.");
    Device d(c);
    if (!d.simplex)
        throw std::runtime_error(d.fallback);
    Executor x(d, c);
    auto &g = x.buffer("G", cost.size() * 8);
    x.upload(g, cost);
    auto cold = x.ot(g, n, m, false, c);
    std::vector<double> changed = cost;
    for (auto &v : changed)
        v = -v;
    x.upload(g, changed);
    return {cold, x.ot(g, n, m, true, c)};
}
} // namespace algorithm
struct OpenClFgwSession::Impl {
    struct Task {
        Graph target;
        std::vector<std::vector<double>> initial;
        std::promise<Result> promise;
    };
    Config config;
    Device device;
    Baseline baseline;
    std::mutex mutex;
    std::condition_variable ready, space;
    std::deque<std::unique_ptr<Task>> pending;
    std::vector<std::thread> workers;
    SessionStatistics stats;
    bool closed = false;
    size_t capacity;
    Impl(Graph g, const Config &c) : config(c), device(c), baseline(std::move(g), device, c) {
        const int count = std::min({c.maxConcurrentComparisons, c.gpuSlots, c.dispatcherThreads});
        capacity = c.queueCapacity ? c.queueCapacity : 2 * size_t(count);
        stats.baselinePreparations = 1;
        // Initialise before starting workers, so constructor failures precede
        // acceptance.
        std::vector<std::shared_ptr<Executor>> executors;
        for (int i = 0; i < count; ++i)
            executors.push_back(std::make_shared<Executor>(device, c));
        try {
            for (auto x : executors)
                workers.emplace_back([this, x] {
                    std::pair<size_t, size_t> accounted{};
                    for (;;) {
                        std::unique_ptr<Task> task;
                        {
                            std::unique_lock<std::mutex> lock(mutex);
                            ready.wait(lock, [&] { return closed || !pending.empty(); });
                            if (pending.empty())
                                return;
                            task = std::move(pending.front());
                            pending.pop_front();
                            stats.maximumInFlight = std::max(stats.maximumInFlight, ++inFlight);
                        }
                        space.notify_one();
                        Result result;
                        std::exception_ptr error;
                        try {
                            result = comparePrepared(*x, baseline, task->target, task->initial, config);
                        } catch (...) {
                            clFinish(x->queue.value);
                            error = std::current_exception();
                        }
                        {
                            std::lock_guard<std::mutex> lock(mutex);
                            --inFlight;
                            if (error)
                                ++stats.failed;
                            else
                                ++stats.completed;
                            auto a = x->accounting();
                            stats.workerDeviceAllocations += a.first - accounted.first;
                            stats.workerDeviceBytes += a.second - accounted.second;
                            accounted = a;
                        }
                        // Fulfil only after accounting, so future::get observes completed
                        // work.
                        if (error)
                            task->promise.set_exception(error);
                        else
                            task->promise.set_value(std::move(result));
                    }
                });
        } catch (...) {
            close();
            for (auto &w : workers)
                if (w.joinable())
                    w.join();
            throw;
        }
        stats.workersCreated = count;
        stats.gpuSlotsCreated = count;
    }
    size_t inFlight = 0;
    void close() {
        {
            std::lock_guard<std::mutex> lock(mutex);
            closed = true;
        }
        ready.notify_all();
        space.notify_all();
    }
    ~Impl() {
        close();
        for (auto &w : workers)
            w.join();
    }
};
OpenClFgwSession::OpenClFgwSession(Graph baseline, const Config &config) {
    algorithm::validateConfig(config);
    impl = std::make_unique<Impl>(std::move(baseline), config);
}
OpenClFgwSession::~OpenClFgwSession() = default;
void OpenClFgwSession::close() { impl->close(); }
SessionStatistics OpenClFgwSession::statistics() const {
    std::lock_guard<std::mutex> lock(impl->mutex);
    return impl->stats;
}
std::future<Result> OpenClFgwSession::submit(Graph target, std::vector<std::vector<double>> initial) {
    auto task = std::make_unique<Impl::Task>();
    task->target = std::move(target);
    task->initial = std::move(initial);
    auto future = task->promise.get_future();
    {
        std::unique_lock<std::mutex> lock(impl->mutex);
        impl->space.wait(lock, [&] { return impl->closed || impl->pending.size() < impl->capacity; });
        if (impl->closed)
            throw std::runtime_error("FGW session is closed; target was not accepted.");
        impl->pending.push_back(std::move(task));
        ++impl->stats.accepted;
        impl->stats.maximumQueued = std::max(impl->stats.maximumQueued, impl->pending.size());
    }
    impl->ready.notify_one();
    return future;
}
Result OpenClFgwEngine::compare(const Comparison &input, const Config &c) const {
    algorithm::validateComparison(input, c);
    auto single = c;
    single.maxConcurrentComparisons = 1;
    OpenClFgwSession session(input.left, single);
    return session.submit(input.right, input.initialMatrices).get();
}
std::vector<Result> OpenClFgwEngine::compareBatch(const std::vector<Comparison> &inputs,
                                                  const Config &c) const {
    algorithm::validateConfig(c);
    std::vector<Result> result;
    result.reserve(inputs.size());
    std::unique_ptr<OpenClFgwSession> session;
    const Graph *previous = nullptr;
    auto same = [](const Graph &a, const Graph &b) {
        if (a.productId != b.productId || a.instanceId != b.instanceId || a.rootIndex != b.rootIndex ||
            a.rowOffsets != b.rowOffsets || a.colIndices != b.colIndices || a.embeddings != b.embeddings ||
            a.sources.size() != b.sources.size())
            return false;
        for (size_t i = 0; i < a.sources.size(); ++i) {
            const auto &x = a.sources[i], &y = b.sources[i];
            if (x.occurrenceId != y.occurrenceId || x.materialId != y.materialId ||
                x.productionOrderId != y.productionOrderId || x.quantity != y.quantity ||
                x.amount != y.amount || x.issueLineIds != y.issueLineIds ||
                x.fifoAllocationIds != y.fifoAllocationIds)
                return false;
        }
        return true;
    };
    for (const auto &input : inputs) {
        if (!previous || !same(*previous, input.left)) {
            session = std::make_unique<OpenClFgwSession>(input.left, c);
            previous = &input.left;
        }
        result.push_back(session->submit(input.right, input.initialMatrices).get());
    }
    return result;
}
} // namespace fgw
