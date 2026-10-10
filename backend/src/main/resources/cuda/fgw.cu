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
        c.workspaceBytes < 256)
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
#endif
} // namespace algorithm
} // namespace fgw
