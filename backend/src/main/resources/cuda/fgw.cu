#include "fgw_engine.cuh"

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

#ifndef FGW_HOST_ONLY
__global__ void materialDistanceKernel(const double *left, const double *right, double *out, int n, int m) {
    std::size_t index = static_cast<std::size_t>(blockIdx.x) * blockDim.x + threadIdx.x;
    if (index >= static_cast<std::size_t>(n) * m)
        return;
    std::size_t i = index / m, j = index % m;
    double value = 0;
    for (int k = 0; k < embeddingDimension; ++k) {
        double delta = left[i * embeddingDimension + k] - right[j * embeddingDimension + k];
        value += delta * delta;
    }
    out[index] = value;
}
__global__ void structuralDistanceKernel(const int *offsets, const int *columns, double *out, int *queues,
                                         int n) {
    int source = blockIdx.x * blockDim.x + threadIdx.x;
    if (source >= n)
        return;
    double *distances = out + static_cast<std::size_t>(source) * n;
    int *queue = queues + static_cast<std::size_t>(source) * n;
    for (int i = 0; i < n; ++i)
        distances[i] = -1;
    int head = 0, tail = 1;
    queue[0] = source;
    distances[source] = 0;
    while (head < tail) {
        int u = queue[head++];
        for (int e = offsets[u]; e < offsets[u + 1]; ++e) {
            int v = columns[e];
            if (distances[v] < 0) {
                distances[v] = distances[u] + 1;
                queue[tail++] = v;
            }
        }
    }
}
__global__ void uniformKernel(double *weights, int n) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i < n)
        weights[i] = 1.0 / n;
}
__global__ void outerKernel(const double *p, const double *q, double *out, int n, int m) {
    std::size_t k = static_cast<std::size_t>(blockIdx.x) * blockDim.x + threadIdx.x;
    if (k < static_cast<std::size_t>(n) * m)
        out[k] = p[k / m] * q[k % m];
}
__global__ void squaredMarginalKernel(const double *distances, const double *weights, double *out, int n) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= n)
        return;
    double sum = 0;
    for (int k = 0; k < n; ++k) {
        double d = distances[static_cast<std::size_t>(i) * n + k];
        sum += d * d * weights[k];
    }
    out[i] = sum;
}
__global__ void fixedTermKernel(const double *left, const double *right, double *out, int n, int m) {
    std::size_t k = static_cast<std::size_t>(blockIdx.x) * blockDim.x + threadIdx.x;
    if (k < static_cast<std::size_t>(n) * m)
        out[k] = left[k / m] + right[k % m];
}
__global__ void costGradientKernel(const double *material, const double *fixed, const double *product,
                                   double *h, double *gradient, double alpha, std::size_t count) {
    std::size_t k = static_cast<std::size_t>(blockIdx.x) * blockDim.x + threadIdx.x;
    if (k >= count)
        return;
    h[k] = fixed[k] - 2 * product[k];
    gradient[k] = (1 - alpha) * material[k] + 2 * alpha * h[k];
}
__global__ void directionKernel(const double *candidate, const double *current, double *direction,
                                std::size_t count) {
    std::size_t k = static_cast<std::size_t>(blockIdx.x) * blockDim.x + threadIdx.x;
    if (k < count)
        direction[k] = candidate[k] - current[k];
}
__global__ void updateKernel(double *current, const double *direction, double gamma, std::size_t count) {
    std::size_t k = static_cast<std::size_t>(blockIdx.x) * blockDim.x + threadIdx.x;
    if (k < count)
        current[k] += gamma * direction[k];
}

constexpr int maxSimplexNodes = 320;
constexpr int maxSimplexEdges = 800;

/** Reduce original uniform-marginal feasibility without downloading a matrix.
 * One block writes row/column/total errors, the minimum weight and finiteness.
 */
__global__ void marginalSummaryKernel(const double *matrix, double *out, int n, int m) {
    __shared__ double rows[256], columns[256], totals[256], minima[256];
    __shared__ int finite[256];
    int t = threadIdx.x;
    double row = 0, column = 0, total = 0, minimum = 0;
    int valid = 1;
    for (int i = t; i < n; i += blockDim.x) {
        double sum = 0;
        for (int j = 0; j < m; ++j)
            sum += matrix[static_cast<std::size_t>(i) * m + j];
        row = fmax(row, fabs(sum - 1.0 / n));
        valid &= isfinite(sum);
    }
    for (int j = t; j < m; j += blockDim.x) {
        double sum = 0;
        for (int i = 0; i < n; ++i)
            sum += matrix[static_cast<std::size_t>(i) * m + j];
        column = fmax(column, fabs(sum - 1.0 / m));
        valid &= isfinite(sum);
    }
    for (std::size_t k = t; k < static_cast<std::size_t>(n) * m; k += blockDim.x) {
        double value = matrix[k];
        total += value;
        minimum = fmin(minimum, value);
        valid &= isfinite(value);
    }
    rows[t] = row;
    columns[t] = column;
    totals[t] = total;
    minima[t] = minimum;
    finite[t] = valid;
    __syncthreads();
    for (int offset = blockDim.x / 2; offset; offset /= 2) {
        if (t < offset) {
            rows[t] = fmax(rows[t], rows[t + offset]);
            columns[t] = fmax(columns[t], columns[t + offset]);
            totals[t] += totals[t + offset];
            minima[t] = fmin(minima[t], minima[t + offset]);
            finite[t] &= finite[t + offset];
        }
        __syncthreads();
    }
    if (t == 0) {
        out[0] = rows[0];
        out[1] = columns[0];
        out[2] = fabs(totals[0] - 1);
        out[3] = minima[0];
        out[4] = finite[0] && isfinite(totals[0]);
    }
}

__device__ inline int deviceGcd(int a, int b) {
    while (b) {
        int t = b;
        b = a % b;
        a = t;
    }
    return a;
}

__device__ inline void addEdgeDevice(short u, short v, short arc, double cost, short *first, short *head,
                                     short *next, short *edgeArc, double *edgeCost, short &freeHead) {
    short e = freeHead;
    freeHead = next[e];
    head[e] = v;
    edgeArc[e] = arc;
    edgeCost[e] = cost;
    next[e] = first[u];
    first[u] = e;
}

__device__ inline void removeEdgeDevice(short u, short v, short *first, const short *head, short *next,
                                        short &freeHead) {
    short prev = -1;
    short curr = first[u];
    while (curr != -1) {
        if (head[curr] == v) {
            if (prev == -1)
                first[u] = next[curr];
            else
                next[prev] = next[curr];
            next[curr] = freeHead;
            freeHead = curr;
            return;
        }
        prev = curr;
        curr = next[curr];
    }
}

__global__ void transportationSimplexKernel(const double *cost, int *flow, bool *basic, double *candidate,
                                            int *pivotsOut, int n, int m, double tol, bool warmStart,
                                            std::size_t count, double *summary, bool incrementalTree,
                                            bool candidatePricing) {
    const std::size_t blockOffset = static_cast<std::size_t>(blockIdx.x) * count;
    if (summary) summary += static_cast<std::size_t>(blockIdx.x) * SummaryCount;
    const double *blockCost = cost + blockOffset;
    int *blockFlow = flow + blockOffset;
    bool *blockBasic = basic + blockOffset;
    double *blockCandidate = candidate + blockOffset;

    const int tid = threadIdx.x;
    const int nodes = n + m;
    const int divisor = deviceGcd(n, m);
    const int rowUnits = m / divisor;
    const int colUnits = n / divisor;
    const int totalUnits = n * rowUnits;

    __shared__ short first[maxSimplexNodes];
    __shared__ short head[maxSimplexEdges];
    __shared__ short next[maxSimplexEdges];
    __shared__ short edgeArc[maxSimplexEdges];
    __shared__ double edgeCost[maxSimplexEdges];
    __shared__ short freeHead;

    __shared__ short parent[maxSimplexNodes];
    __shared__ short parentArc[maxSimplexNodes];
    __shared__ short depth[maxSimplexNodes];
    __shared__ int reverseCycle[maxSimplexNodes];
    __shared__ int candidateArcs[256];
    __shared__ bool candidatesReady;
    __shared__ bool refreshPricing;
    __shared__ int fullScans, candidatePasses, treeRebuilds, treeUpdates;
    __shared__ unsigned long long treeVisits, cycleVisits;
    __shared__ short queue[maxSimplexNodes];
    __shared__ double dual[maxSimplexNodes];
    __shared__ int visited[maxSimplexNodes];
    __shared__ int visitedToken;

    __shared__ int enteringArc;
    __shared__ int cycleLen;
    __shared__ int cycleArcs[maxSimplexNodes];
    __shared__ int blockPivots;
    __shared__ bool blockDone;
    __shared__ int invalid;
    __shared__ double costEpsilon;

    __shared__ double warpMinRed[8];
    __shared__ int warpMinArc[8];

    __shared__ int supply[maxSimplexNodes];
    __shared__ int demand[maxSimplexNodes];
    __shared__ bool rowActive[maxSimplexNodes];
    __shared__ bool colActive[maxSimplexNodes];
    __shared__ int activeRowCount;
    __shared__ int activeColCount;

    for (int i = tid; i < nodes; i += blockDim.x) {
        first[i] = -1;
        visited[i] = 0;
        if (i < n) {
            supply[i] = rowUnits;
            rowActive[i] = true;
        }
        if (i < m) {
            demand[i] = colUnits;
            colActive[i] = true;
        }
    }
    for (int i = tid; i < maxSimplexEdges - 1; i += blockDim.x)
        next[i] = static_cast<short>(i + 1);
    if (!warmStart) {
        for (std::size_t i = tid; i < count; i += blockDim.x) {
            blockBasic[i] = false;
            blockFlow[i] = 0;
        }
    }
    if (tid == 0) {
        next[maxSimplexEdges - 1] = -1;
        freeHead = 0;
        blockPivots = 0;
        blockDone = false;
        invalid = 0;
        candidatesReady = false;
        fullScans = candidatePasses = treeRebuilds = treeUpdates = 0;
        treeVisits = cycleVisits = 0;
        activeRowCount = n;
        activeColCount = m;
        visitedToken = 1;
    }
    __syncthreads();

    for (std::size_t i = tid; i < count; i += blockDim.x)
        if (!isfinite(blockCost[i]))
            atomicExch(&invalid, 1);
    __syncthreads();
    if (invalid) {
        for (std::size_t i = tid; i < count; i += blockDim.x)
            blockCandidate[i] = 0;
        if (tid == 0) {
            if (summary)
                summary[OtSuccess] = 0;
            if (pivotsOut)
                pivotsOut[blockIdx.x] = -1;
        }
        return;
    }

    if (!warmStart) {
        while (activeRowCount > 1 && activeColCount > 1) {
            double myMin = HUGE_VAL;
            int myArc = -1;
            for (std::size_t i = tid; i < count; i += blockDim.x) {
                int r = static_cast<int>(i / m), c = static_cast<int>(i % m);
                if (rowActive[r] && colActive[c]) {
                    double val = blockCost[i];
                    if (val < myMin) {
                        myMin = val;
                        myArc = static_cast<int>(i);
                    }
                }
            }
            for (int offset = 16; offset > 0; offset /= 2) {
                double oMin = __shfl_down_sync(0xffffffff, myMin, offset);
                int oArc = __shfl_down_sync(0xffffffff, myArc, offset);
                if (oMin < myMin) {
                    myMin = oMin;
                    myArc = oArc;
                }
            }
            if (tid % 32 == 0) {
                warpMinRed[tid / 32] = myMin;
                warpMinArc[tid / 32] = myArc;
            }
            __syncthreads();

            if (tid == 0) {
                double bestVal = HUGE_VAL;
                int bestArc = -1;
                const int warps = blockDim.x / 32;
                for (int w = 0; w < warps; ++w) {
                    if (warpMinRed[w] < bestVal) {
                        bestVal = warpMinRed[w];
                        bestArc = warpMinArc[w];
                    }
                }
                if (bestArc != -1) {
                    int r = bestArc / m, c = bestArc % m;
                    int mass = supply[r] < demand[c] ? supply[r] : demand[c];
                    blockFlow[bestArc] = mass;
                    blockBasic[bestArc] = true;
                    double arcCost = blockCost[bestArc];
                    addEdgeDevice(static_cast<short>(r), static_cast<short>(n + c),
                                  static_cast<short>(bestArc), arcCost, first, head, next, edgeArc, edgeCost,
                                  freeHead);
                    addEdgeDevice(static_cast<short>(n + c), static_cast<short>(r),
                                  static_cast<short>(bestArc), arcCost, first, head, next, edgeArc, edgeCost,
                                  freeHead);
                    supply[r] -= mass;
                    demand[c] -= mass;
                    if (supply[r] < demand[c]) {
                        rowActive[r] = false;
                        --activeRowCount;
                    } else if (demand[c] < supply[r]) {
                        colActive[c] = false;
                        --activeColCount;
                    } else {
                        rowActive[r] = false;
                        --activeRowCount;
                    }
                }
            }
            __syncthreads();
        }

        // All lanes must finish reading the loop condition before lane zero
        // changes the active counts while completing the remaining basis arcs.
        __syncthreads();
        if (tid == 0) {
            if (activeRowCount == 1) {
                int r = -1;
                for (int i = 0; i < n; ++i)
                    if (rowActive[i]) {
                        r = i;
                        break;
                    }
                for (int c = 0; c < m; ++c) {
                    if (colActive[c]) {
                        int arc = r * m + c;
                        int mass = demand[c];
                        blockFlow[arc] += mass;
                        blockBasic[arc] = true;
                        double arcCost = blockCost[arc];
                        addEdgeDevice(static_cast<short>(r), static_cast<short>(n + c),
                                      static_cast<short>(arc), arcCost, first, head, next, edgeArc, edgeCost,
                                      freeHead);
                        addEdgeDevice(static_cast<short>(n + c), static_cast<short>(r),
                                      static_cast<short>(arc), arcCost, first, head, next, edgeArc, edgeCost,
                                      freeHead);
                        demand[c] = 0;
                        colActive[c] = false;
                    }
                }
                rowActive[r] = false;
                activeRowCount = 0;
            } else if (activeColCount == 1) {
                int c = -1;
                for (int j = 0; j < m; ++j)
                    if (colActive[j]) {
                        c = j;
                        break;
                    }
                for (int r = 0; r < n; ++r) {
                    if (rowActive[r]) {
                        int arc = r * m + c;
                        int mass = supply[r];
                        blockFlow[arc] += mass;
                        blockBasic[arc] = true;
                        double arcCost = blockCost[arc];
                        addEdgeDevice(static_cast<short>(r), static_cast<short>(n + c),
                                      static_cast<short>(arc), arcCost, first, head, next, edgeArc, edgeCost,
                                      freeHead);
                        addEdgeDevice(static_cast<short>(n + c), static_cast<short>(r),
                                      static_cast<short>(arc), arcCost, first, head, next, edgeArc, edgeCost,
                                      freeHead);
                        supply[r] = 0;
                        rowActive[r] = false;
                    }
                }
                colActive[c] = false;
                activeColCount = 0;
            }
        }
        __syncthreads();
    } else {
        if (tid == 0) {
            for (std::size_t i = 0; i < count; ++i) {
                if (blockBasic[i]) {
                    int r = static_cast<int>(i / m), c = n + static_cast<int>(i % m);
                    double arcCost = blockCost[i];
                    addEdgeDevice(static_cast<short>(r), static_cast<short>(c), static_cast<short>(i),
                                  arcCost, first, head, next, edgeArc, edgeCost, freeHead);
                    addEdgeDevice(static_cast<short>(c), static_cast<short>(r), static_cast<short>(i),
                                  arcCost, first, head, next, edgeArc, edgeCost, freeHead);
                }
            }
        }
        __syncthreads();
    }

    double maxCost = 1.0;
    for (std::size_t i = tid; i < count; i += blockDim.x) {
        double v = std::abs(blockCost[i]);
        if (v > maxCost)
            maxCost = v;
    }
    for (int offset = 16; offset > 0; offset /= 2) {
        maxCost = fmax(maxCost, __shfl_down_sync(0xffffffff, maxCost, offset));
    }
    if (tid % 32 == 0)
        warpMinRed[tid / 32] = maxCost;
    __syncthreads();
    if (tid == 0) {
        double globalMax = 1.0;
        const int warps = blockDim.x / 32;
        for (int w = 0; w < warps; ++w)
            if (warpMinRed[w] > globalMax)
                globalMax = warpMinRed[w];
        costEpsilon = tol * globalMax;
    }
    __syncthreads();

    while (!blockDone) {
        // Rooted tree potentials are rebuilt once per solve in incremental mode.
        // Changed-cost warm starts still require this first complete traversal.
        if (tid == 0 && (!incrementalTree || blockPivots == 0)) {
            ++treeRebuilds;
            ++visitedToken;
            int headIdx = 0, tailIdx = 1;
            queue[0] = 0;
            visited[0] = visitedToken;
            dual[0] = 0.0;
            parent[0] = 0;
            depth[0] = 0;
            while (headIdx < tailIdx) {
                short u = queue[headIdx++];
                short e = first[u];
                while (e != -1) {
                    short v = head[e];
                    if (visited[v] != visitedToken) {
                        dual[v] = edgeCost[e] - dual[u];
                        if (incrementalTree) {
                            parent[v] = u;
                            parentArc[v] = edgeArc[e];
                            depth[v] = depth[u] + 1;
                        }
                        visited[v] = visitedToken;
                        queue[tailIdx++] = v;
                    }
                    e = next[e];
                }
            }
            treeVisits += tailIdx;
            if (tailIdx != nodes) {
                invalid = 1;
                blockDone = true;
            }
        }
        if (tid == 0 && blockPivots >= 100000) {
            invalid = 1;
            blockDone = true;
        }
        __syncthreads();
        if (blockDone)
            break;

        double myMinRed = -costEpsilon;
        int myMinArc = -1;
        // Cache one negative arc per thread after a complete scan. Reprice the
        // cache against current potentials, never trust an old reduced cost.
        if (candidatePricing && candidatesReady) {
            if (tid == 0) ++candidatePasses;
            int arc = candidateArcs[tid];
            if (arc >= 0 && !blockBasic[arc]) {
                double red = blockCost[arc] - dual[arc / m] - dual[n + arc % m];
                if (red < myMinRed) {
                    myMinRed = red;
                    myMinArc = arc;
                }
            }
        }
        if (candidatePricing) {
            int anyCandidate = __syncthreads_count(myMinArc >= 0);
            if (tid == 0) {
                refreshPricing = !candidatesReady || !anyCandidate;
                if (refreshPricing) ++fullScans;
            }
            __syncthreads();
        } else if (tid == 0) {
            ++fullScans;
        }
        if (!candidatePricing || refreshPricing) {
            for (std::size_t i = tid; i < count; i += blockDim.x) {
                if (!blockBasic[i]) {
                    int r = static_cast<int>(i / m), c = static_cast<int>(i % m);
                    double red = blockCost[i] - dual[r] - dual[n + c];
                    if (red < myMinRed) {
                        myMinRed = red;
                        myMinArc = static_cast<int>(i);
                    }
                }
            }
            if (candidatePricing) candidateArcs[tid] = myMinArc;
        }
        if (tid == 0 && candidatePricing && refreshPricing) candidatesReady = true;
        for (int offset = 16; offset > 0; offset /= 2) {
            double otherRed = __shfl_down_sync(0xffffffff, myMinRed, offset);
            int otherArc = __shfl_down_sync(0xffffffff, myMinArc, offset);
            if (otherRed < myMinRed) {
                myMinRed = otherRed;
                myMinArc = otherArc;
            }
        }
        if (tid % 32 == 0) {
            warpMinRed[tid / 32] = myMinRed;
            warpMinArc[tid / 32] = myMinArc;
        }
        __syncthreads();
        if (tid == 0) {
            double bestRed = -costEpsilon;
            int bestArc = -1;
            const int warps = blockDim.x / 32;
            for (int w = 0; w < warps; ++w) {
                if (warpMinRed[w] < bestRed) {
                    bestRed = warpMinRed[w];
                    bestArc = warpMinArc[w];
                }
            }
            if (bestArc == -1)
                blockDone = true;
            else
                enteringArc = bestArc;
        }
        __syncthreads();
        if (blockDone)
            break;

        if (tid == 0) {
            int entR = enteringArc / m, entC = n + enteringArc % m;
            cycleLen = 0;
            if (incrementalTree) {
                // The unique tree path is obtained from cached parents/depths;
                // the second endpoint's path is reversed to preserve flow signs.
                int u = entR, v = entC, reverseLen = 0;
                while (u != v) {
                    if (depth[u] >= depth[v]) {
                        cycleArcs[cycleLen++] = parentArc[u];
                        u = parent[u];
                    } else {
                        reverseCycle[reverseLen++] = parentArc[v];
                        v = parent[v];
                    }
                }
                for (int k = reverseLen - 1; k >= 0; --k)
                    cycleArcs[cycleLen++] = reverseCycle[k];
            } else {
                ++visitedToken;
                int headIdx = 0, tailIdx = 1;
                queue[0] = static_cast<short>(entC);
                parent[entC] = static_cast<short>(entC);
                visited[entC] = visitedToken;
                while (headIdx < tailIdx && visited[entR] != visitedToken) {
                    short u = queue[headIdx++];
                    short e = first[u];
                    while (e != -1) {
                        short v = head[e];
                        if (visited[v] != visitedToken) {
                            parent[v] = u;
                            parentArc[v] = edgeArc[e];
                            visited[v] = visitedToken;
                            queue[tailIdx++] = v;
                        }
                        e = next[e];
                    }
                }
                treeVisits += tailIdx;
                for (int curr = entR; curr != entC; curr = parent[curr])
                    cycleArcs[cycleLen++] = parentArc[curr];
            }
            cycleVisits += cycleLen;
            int mass = totalUnits, leaving = -1;
            for (int k = 0; k < cycleLen; k += 2) {
                int arc = cycleArcs[k];
                int fl = blockFlow[arc];
                if (fl < mass || (fl == mass && (leaving == -1 || arc < leaving))) {
                    mass = fl;
                    leaving = arc;
                }
            }
            int movingRoot = -1, fixedRoot = -1;
            if (incrementalTree) {
                int leaveR = leaving / m, leaveC = n + leaving % m;
                int child = parent[leaveR] == leaveC ? leaveR : leaveC;
                int ancestor = entR;
                while (depth[ancestor] > depth[child]) ancestor = parent[ancestor];
                bool rowDetached = ancestor == child;
                movingRoot = rowDetached ? entR : entC;
                fixedRoot = rowDetached ? entC : entR;
            }
            blockFlow[enteringArc] += mass;
            for (int k = 0; k < cycleLen; ++k) {
                if (k % 2 == 0)
                    blockFlow[cycleArcs[k]] -= mass;
                else
                    blockFlow[cycleArcs[k]] += mass;
            }
            removeEdgeDevice(static_cast<short>(leaving / m), static_cast<short>(n + leaving % m), first,
                             head, next, freeHead);
            removeEdgeDevice(static_cast<short>(n + leaving % m), static_cast<short>(leaving / m), first,
                             head, next, freeHead);
            blockBasic[leaving] = false;
            double enteringCost = blockCost[enteringArc];
            if (incrementalTree) {
                // Removing the leaving edge isolates a subtree excluding root 0.
                // Reroot only that component at the entering edge, recomputing
                // parents, depths and potentials there; the other component is
                // unchanged. The entering edge is added after this traversal.
                ++treeUpdates;
                ++visitedToken;
                int headIdx = 0, tailIdx = 1;
                queue[0] = static_cast<short>(movingRoot);
                visited[movingRoot] = visitedToken;
                parent[movingRoot] = static_cast<short>(fixedRoot);
                parentArc[movingRoot] = static_cast<short>(enteringArc);
                depth[movingRoot] = depth[fixedRoot] + 1;
                dual[movingRoot] = enteringCost - dual[fixedRoot];
                while (headIdx < tailIdx) {
                    short u = queue[headIdx++];
                    for (short e = first[u]; e != -1; e = next[e]) {
                        short v = head[e];
                        if (visited[v] != visitedToken) {
                            parent[v] = u;
                            parentArc[v] = edgeArc[e];
                            depth[v] = depth[u] + 1;
                            dual[v] = edgeCost[e] - dual[u];
                            visited[v] = visitedToken;
                            queue[tailIdx++] = v;
                        }
                    }
                }
                treeVisits += tailIdx;
            }
            addEdgeDevice(static_cast<short>(entR), static_cast<short>(entC), static_cast<short>(enteringArc),
                          enteringCost, first, head, next, edgeArc, edgeCost, freeHead);
            addEdgeDevice(static_cast<short>(entC), static_cast<short>(entR), static_cast<short>(enteringArc),
                          enteringCost, first, head, next, edgeArc, edgeCost, freeHead);
            blockBasic[enteringArc] = true;
            ++blockPivots;
        }
        __syncthreads();
    }

    if (summary && tid == 0) {
        summary[OtFullPricingScans] = fullScans;
        summary[OtCandidatePricingPasses] = candidatePasses;
        summary[OtTreeRebuilds] = treeRebuilds;
        summary[OtIncrementalTreeUpdates] = treeUpdates;
        summary[OtTreeNodesVisited] = static_cast<double>(treeVisits);
        summary[OtCycleEdges] = static_cast<double>(cycleVisits);
    }
    if (blockCandidate) {
        for (std::size_t i = tid; i < count; i += blockDim.x) {
            blockCandidate[i] = static_cast<double>(blockFlow[i]) / totalUnits;
        }
    }
    if (pivotsOut && tid == 0)
        pivotsOut[blockIdx.x] = blockPivots;
    if (invalid) {
        if (tid == 0) {
            if (summary) summary[OtSuccess] = 0;
            if (pivotsOut) pivotsOut[blockIdx.x] = -1;
        }
        return;
    }
    if (summary) {
        double primal = 0, violation = 0;
        for (std::size_t i = tid; i < count; i += blockDim.x) {
            primal += blockCost[i] * static_cast<double>(blockFlow[i]) / totalUnits;
            violation = fmax(violation, dual[i / m] + dual[n + i % m] - blockCost[i]);
        }
        __shared__ double primalSums[256], violations[256];
        primalSums[tid] = primal;
        violations[tid] = violation;
        __syncthreads();
        for (int offset = blockDim.x / 2; offset; offset /= 2) {
            if (tid < offset) {
                primalSums[tid] += primalSums[tid + offset];
                violations[tid] = fmax(violations[tid], violations[tid + offset]);
            }
            __syncthreads();
        }
        if (tid == 0) {
            double dualCost = 0;
            for (int i = 0; i < n; ++i)
                dualCost += dual[i] / n;
            for (int j = 0; j < m; ++j)
                dualCost += dual[n + j] / m;
            summary[LinearCost] = primalSums[0];
            summary[DualCost] = dualCost;
            summary[ReducedCostViolation] = violations[0];
            summary[OtSuccess] = !invalid;
            summary[OtCostEpsilon] = costEpsilon;
        }
    }
}
#endif
} // namespace algorithm
} // namespace fgw
