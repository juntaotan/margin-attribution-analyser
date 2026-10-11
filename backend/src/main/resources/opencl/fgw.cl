#pragma OPENCL EXTENSION cl_khr_fp64 : enable
#pragma OPENCL FP_CONTRACT OFF
#define embeddingDimension 384
#define MaterialCost 0
#define StructureCost 1
#define DirectionalGradient 2
#define DirectionProduct 3
#define RowError 4
#define ColumnError 5
#define TotalError 6
#define MinimumWeight 7
#define FiniteWeights 8
#define CandidateRowError 9
#define CandidateColumnError 10
#define CandidateTotalError 11
#define CandidateMinimumWeight 12
#define CandidateFiniteWeights 13
#define LinearCost 14
#define DualCost 15
#define ReducedCostViolation 16
#define OtSuccess 17
#define OtCostEpsilon 18
#define OtFullPricingScans 19
#define OtCandidatePricingPasses 20
#define OtTreeRebuilds 21
#define OtIncrementalTreeUpdates 22
#define OtTreeNodesVisited 23
#define OtCycleEdges 24
#define SummaryCount 25
__kernel void materialDistanceKernel(__global const double *left, __global const double *right,
                                     __global double *out, int n, int m) {
    ulong index = (ulong)(get_group_id(0)) * get_local_size(0) + get_local_id(0);
    if (index >= (ulong)(n)*m)
        return;
    ulong i = index / m, j = index % m;
    double value = 0;
    for (int k = 0; k < embeddingDimension; ++k) {
        double delta = left[i * embeddingDimension + k] - right[j * embeddingDimension + k];
        value += delta * delta;
    }
    out[index] = value;
}
__kernel void structuralDistanceKernel(__global const int *offsets, __global const int *columns,
                                       __global double *out, __global int *queues, int n) {
    int source = get_group_id(0) * get_local_size(0) + get_local_id(0);
    if (source >= n)
        return;
    __global double *distances = out + (ulong)(source)*n;
    __global int *queue = queues + (ulong)(source)*n;
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
__kernel void uniformKernel(__global double *weights, int n) {
    int i = get_group_id(0) * get_local_size(0) + get_local_id(0);
    if (i < n)
        weights[i] = 1.0 / n;
}
__kernel void outerKernel(__global const double *p, __global const double *q, __global double *out, int n,
                          int m) {
    ulong k = (ulong)(get_group_id(0)) * get_local_size(0) + get_local_id(0);
    if (k < (ulong)(n)*m)
        out[k] = p[k / m] * q[k % m];
}
__kernel void squaredMarginalKernel(__global const double *distances, __global const double *weights,
                                    __global double *out, int n) {
    int i = get_group_id(0) * get_local_size(0) + get_local_id(0);
    if (i >= n)
        return;
    double sum = 0;
    for (int k = 0; k < n; ++k) {
        double d = distances[(ulong)(i)*n + k];
        sum += d * d * weights[k];
    }
    out[i] = sum;
}
__kernel void fixedTermKernel(__global const double *left, __global const double *right, __global double *out,
                              int n, int m) {
    ulong k = (ulong)(get_group_id(0)) * get_local_size(0) + get_local_id(0);
    if (k < (ulong)(n)*m)
        out[k] = left[k / m] + right[k % m];
}
__kernel void costGradientKernel(__global const double *material, __global const double *fixed,
                                 __global const double *product, __global double *h,
                                 __global double *gradient, double alpha, ulong count) {
    ulong k = (ulong)(get_group_id(0)) * get_local_size(0) + get_local_id(0);
    if (k >= count)
        return;
    h[k] = fixed[k] - 2 * product[k];
    gradient[k] = (1 - alpha) * material[k] + 2 * alpha * h[k];
}
__kernel void directionKernel(__global const double *candidate, __global const double *current,
                              __global double *direction, ulong count) {
    ulong k = (ulong)(get_group_id(0)) * get_local_size(0) + get_local_id(0);
    if (k < count)
        direction[k] = candidate[k] - current[k];
}
__kernel void updateKernel(__global double *current, __global const double *direction, double gamma,
                           ulong count) {
    ulong k = (ulong)(get_group_id(0)) * get_local_size(0) + get_local_id(0);
    if (k < count)
        current[k] += gamma * direction[k];
}

#define maxSimplexNodes 320
#define maxSimplexEdges 800

/** Reduce original uniform-marginal feasibility without downloading a matrix.
 * One block writes row/column/total errors, the minimum weight and finiteness.
 */
__kernel void marginalSummaryKernel(__global const double *matrix, __global double *out, int n, int m) {
    __local double rows[256], columns[256], totals[256], minima[256];
    __local int finite[256];
    int t = get_local_id(0);
    double row = 0, column = 0, total = 0, minimum = 0;
    int valid = 1;
    for (int i = t; i < n; i += get_local_size(0)) {
        double sum = 0;
        for (int j = 0; j < m; ++j)
            sum += matrix[(ulong)(i)*m + j];
        row = fmax(row, fabs(sum - 1.0 / n));
        valid &= isfinite(sum);
    }
    for (int j = t; j < m; j += get_local_size(0)) {
        double sum = 0;
        for (int i = 0; i < n; ++i)
            sum += matrix[(ulong)(i)*m + j];
        column = fmax(column, fabs(sum - 1.0 / m));
        valid &= isfinite(sum);
    }
    for (ulong k = t; k < (ulong)(n)*m; k += get_local_size(0)) {
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
    barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
    for (int offset = get_local_size(0) / 2; offset; offset /= 2) {
        if (t < offset) {
            rows[t] = fmax(rows[t], rows[t + offset]);
            columns[t] = fmax(columns[t], columns[t + offset]);
            totals[t] += totals[t + offset];
            minima[t] = fmin(minima[t], minima[t + offset]);
            finite[t] &= finite[t + offset];
        }
        barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
    }
    if (t == 0) {
        out[0] = rows[0];
        out[1] = columns[0];
        out[2] = fabs(totals[0] - 1);
        out[3] = minima[0];
        out[4] = finite[0] && isfinite(totals[0]);
    }
}

inline int deviceGcd(int a, int b) {
    while (b) {
        int t = b;
        b = a % b;
        a = t;
    }
    return a;
}

inline void addEdgeDevice(short u, short v, short arc, double cost, __local short *first, __local short *head,
                          __local short *next, __local short *edgeArc, __local double *edgeCost,
                          __local short *freeHead) {
    short e = *freeHead;
    *freeHead = next[e];
    head[e] = v;
    edgeArc[e] = arc;
    edgeCost[e] = cost;
    next[e] = first[u];
    first[u] = e;
}

inline void removeEdgeDevice(short u, short v, __local short *first, __local const short *head,
                             __local short *next, __local short *freeHead) {
    short prev = -1;
    short curr = first[u];
    while (curr != -1) {
        if (head[curr] == v) {
            if (prev == -1)
                first[u] = next[curr];
            else
                next[prev] = next[curr];
            next[curr] = *freeHead;
            *freeHead = curr;
            return;
        }
        prev = curr;
        curr = next[curr];
    }
}

__kernel void transportationSimplexKernel(__global const double *cost, __global int *flow,
                                          __global uchar *basic, __global double *candidate,
                                          __global int *pivotsOut, int n, int m, double tol, int warmStart,
                                          ulong count, __global double *summary, int incrementalTree,
                                          int candidatePricing) {
    const ulong blockOffset = (ulong)(get_group_id(0)) * count;
    if (summary)
        summary += (ulong)(get_group_id(0)) * SummaryCount;
    __global const double *blockCost = cost + blockOffset;
    __global int *blockFlow = flow + blockOffset;
    __global uchar *blockBasic = basic + blockOffset;
    __global double *blockCandidate = candidate + blockOffset;

    const int tid = get_local_id(0);
    const int nodes = n + m;
    const int divisor = deviceGcd(n, m);
    const int rowUnits = m / divisor;
    const int colUnits = n / divisor;
    const int totalUnits = n * rowUnits;

    __local short first[maxSimplexNodes];
    __local short head[maxSimplexEdges];
    __local short next[maxSimplexEdges];
    __local short edgeArc[maxSimplexEdges];
    __local double edgeCost[maxSimplexEdges];
    __local short freeHead;

    __local short parent[maxSimplexNodes];
    __local short parentArc[maxSimplexNodes];
    __local short depth[maxSimplexNodes];
    __local int reverseCycle[maxSimplexNodes];
    __local int candidateArcs[256];
    __local bool candidatesReady;
    __local bool refreshPricing;
    __local int fullScans, candidatePasses, treeRebuilds, treeUpdates;
    __local ulong treeVisits, cycleVisits;
    __local short queue[maxSimplexNodes];
    __local double dual[maxSimplexNodes];
    __local int visited[maxSimplexNodes];
    __local int visitedToken;

    __local int enteringArc;
    __local int cycleLen;
    __local int cycleArcs[maxSimplexNodes];
    __local int blockPivots;
    __local bool blockDone;
    __local int invalid;
    __local double costEpsilon;

    __local double primalSums[256], violations[256];
    __local double warpMinRed[256];
    __local int warpMinArc[256];

    __local int supply[maxSimplexNodes];
    __local int demand[maxSimplexNodes];
    __local bool rowActive[maxSimplexNodes];
    __local bool colActive[maxSimplexNodes];
    __local int activeRowCount;
    __local int activeColCount;

    for (int i = tid; i < nodes; i += get_local_size(0)) {
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
    for (int i = tid; i < maxSimplexEdges - 1; i += get_local_size(0))
        next[i] = (short)(i + 1);
    if (!warmStart) {
        for (ulong i = tid; i < count; i += get_local_size(0)) {
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
    barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);

    for (ulong i = tid; i < count; i += get_local_size(0))
        if (!isfinite(blockCost[i]))
            atomic_xchg(&invalid, 1);
    barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
    if (invalid) {
        for (ulong i = tid; i < count; i += get_local_size(0))
            blockCandidate[i] = 0;
        if (tid == 0) {
            if (summary)
                summary[OtSuccess] = 0;
            if (pivotsOut)
                pivotsOut[get_group_id(0)] = -1;
        }
        return;
    }

    if (!warmStart) {
        while (activeRowCount > 1 && activeColCount > 1) {
            double myMin = HUGE_VAL;
            int myArc = -1;
            for (ulong i = tid; i < count; i += get_local_size(0)) {
                int r = (int)(i / m), c = (int)(i % m);
                if (rowActive[r] && colActive[c]) {
                    double val = blockCost[i];
                    if (val < myMin) {
                        myMin = val;
                        myArc = (int)(i);
                    }
                }
            }
            warpMinRed[tid] = myMin;
            warpMinArc[tid] = myArc;
            barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);

            if (tid == 0) {
                double bestVal = HUGE_VAL;
                int bestArc = -1;
                const int warps = get_local_size(0);
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
                    addEdgeDevice((short)(r), (short)(n + c), (short)(bestArc), arcCost, first, head, next,
                                  edgeArc, edgeCost, &freeHead);
                    addEdgeDevice((short)(n + c), (short)(r), (short)(bestArc), arcCost, first, head, next,
                                  edgeArc, edgeCost, &freeHead);
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
            barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
        }

        // All lanes must finish reading the loop condition before lane zero
        // changes the active counts while completing the remaining basis arcs.
        barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
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
                        addEdgeDevice((short)(r), (short)(n + c), (short)(arc), arcCost, first, head, next,
                                      edgeArc, edgeCost, &freeHead);
                        addEdgeDevice((short)(n + c), (short)(r), (short)(arc), arcCost, first, head, next,
                                      edgeArc, edgeCost, &freeHead);
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
                        addEdgeDevice((short)(r), (short)(n + c), (short)(arc), arcCost, first, head, next,
                                      edgeArc, edgeCost, &freeHead);
                        addEdgeDevice((short)(n + c), (short)(r), (short)(arc), arcCost, first, head, next,
                                      edgeArc, edgeCost, &freeHead);
                        supply[r] = 0;
                        rowActive[r] = false;
                    }
                }
                colActive[c] = false;
                activeColCount = 0;
            }
        }
        barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
    } else {
        if (tid == 0) {
            for (ulong i = 0; i < count; ++i) {
                if (blockBasic[i]) {
                    int r = (int)(i / m), c = n + (int)(i % m);
                    double arcCost = blockCost[i];
                    addEdgeDevice((short)(r), (short)(c), (short)(i), arcCost, first, head, next, edgeArc,
                                  edgeCost, &freeHead);
                    addEdgeDevice((short)(c), (short)(r), (short)(i), arcCost, first, head, next, edgeArc,
                                  edgeCost, &freeHead);
                }
            }
        }
        barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
    }

    double maxCost = 1.0;
    for (ulong i = tid; i < count; i += get_local_size(0)) {
        double v = fabs(blockCost[i]);
        if (v > maxCost)
            maxCost = v;
    }
    warpMinRed[tid] = maxCost;
    barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
    if (tid == 0) {
        double globalMax = 1.0;
        const int warps = get_local_size(0);
        for (int w = 0; w < warps; ++w)
            if (warpMinRed[w] > globalMax)
                globalMax = warpMinRed[w];
        costEpsilon = tol * globalMax;
    }
    barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);

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
        barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
        if (blockDone)
            break;

        double myMinRed = -costEpsilon;
        int myMinArc = -1;
        // Cache one negative arc per thread after a complete scan. Reprice the
        // cache against current potentials, never trust an old reduced cost.
        if (candidatePricing && candidatesReady) {
            if (tid == 0)
                ++candidatePasses;
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
            warpMinArc[tid] = myMinArc;
            barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
            int anyCandidate = 0;
            for (int lane = 0; lane < 256; ++lane)
                anyCandidate |= warpMinArc[lane] >= 0;
            if (tid == 0) {
                refreshPricing = !candidatesReady || !anyCandidate;
                if (refreshPricing)
                    ++fullScans;
            }
            barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
        } else if (tid == 0) {
            ++fullScans;
        }
        if (!candidatePricing || refreshPricing) {
            for (ulong i = tid; i < count; i += get_local_size(0)) {
                if (!blockBasic[i]) {
                    int r = (int)(i / m), c = (int)(i % m);
                    double red = blockCost[i] - dual[r] - dual[n + c];
                    if (red < myMinRed) {
                        myMinRed = red;
                        myMinArc = (int)(i);
                    }
                }
            }
            if (candidatePricing)
                candidateArcs[tid] = myMinArc;
        }
        if (tid == 0 && candidatePricing && refreshPricing)
            candidatesReady = true;
        warpMinRed[tid] = myMinRed;
        warpMinArc[tid] = myMinArc;
        barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
        if (tid == 0) {
            double bestRed = -costEpsilon;
            int bestArc = -1;
            const int warps = get_local_size(0);
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
        barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
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
                queue[0] = (short)(entC);
                parent[entC] = (short)(entC);
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
                while (depth[ancestor] > depth[child])
                    ancestor = parent[ancestor];
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
            removeEdgeDevice((short)(leaving / m), (short)(n + leaving % m), first, head, next, &freeHead);
            removeEdgeDevice((short)(n + leaving % m), (short)(leaving / m), first, head, next, &freeHead);
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
                queue[0] = (short)(movingRoot);
                visited[movingRoot] = visitedToken;
                parent[movingRoot] = (short)(fixedRoot);
                parentArc[movingRoot] = (short)(enteringArc);
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
            addEdgeDevice((short)(entR), (short)(entC), (short)(enteringArc), enteringCost, first, head, next,
                          edgeArc, edgeCost, &freeHead);
            addEdgeDevice((short)(entC), (short)(entR), (short)(enteringArc), enteringCost, first, head, next,
                          edgeArc, edgeCost, &freeHead);
            blockBasic[enteringArc] = true;
            ++blockPivots;
        }
        barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
    }

    if (summary && tid == 0) {
        summary[OtFullPricingScans] = fullScans;
        summary[OtCandidatePricingPasses] = candidatePasses;
        summary[OtTreeRebuilds] = treeRebuilds;
        summary[OtIncrementalTreeUpdates] = treeUpdates;
        summary[OtTreeNodesVisited] = (double)(treeVisits);
        summary[OtCycleEdges] = (double)(cycleVisits);
    }
    if (blockCandidate) {
        for (ulong i = tid; i < count; i += get_local_size(0)) {
            blockCandidate[i] = (double)(blockFlow[i]) / totalUnits;
        }
    }
    if (pivotsOut && tid == 0)
        pivotsOut[get_group_id(0)] = blockPivots;
    if (invalid) {
        if (tid == 0) {
            if (summary)
                summary[OtSuccess] = 0;
            if (pivotsOut)
                pivotsOut[get_group_id(0)] = -1;
        }
        return;
    }
    if (summary) {
        double primal = 0, violation = 0;
        for (ulong i = tid; i < count; i += get_local_size(0)) {
            primal += blockCost[i] * (double)(blockFlow[i]) / totalUnits;
            violation = fmax(violation, dual[i / m] + dual[n + i % m] - blockCost[i]);
        }

        primalSums[tid] = primal;
        violations[tid] = violation;
        barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
        for (int offset = get_local_size(0) / 2; offset; offset /= 2) {
            if (tid < offset) {
                primalSums[tid] += primalSums[tid + offset];
                violations[tid] = fmax(violations[tid], violations[tid + offset]);
            }
            barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
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
__kernel void productLeft(__global const double *d, __global const double *p, __global double *out, int n,
                          int m) {
    ulong z = get_global_id(0);
    if (z >= (ulong)n * m)
        return;
    int i = z / m, j = z % m;
    double v = 0;
    for (int k = 0; k < n; ++k)
        v += d[(ulong)i * n + k] * p[(ulong)k * m + j];
    out[z] = v;
}
__kernel void productRight(__global const double *p, __global const double *d, __global double *out, int n,
                           int m) {
    ulong z = get_global_id(0);
    if (z >= (ulong)n * m)
        return;
    int i = z / m, j = z % m;
    double v = 0;
    for (int k = 0; k < m; ++k)
        v += p[(ulong)i * m + k] * d[(ulong)j * m + k];
    out[z] = v;
}
__kernel void dotKernel(__global const double *a, __global const double *b, __global double *out, ulong count,
                        int index) {
    __local double sums[256];
    int t = get_local_id(0);
    double v = 0;
    for (ulong k = t; k < count; k += get_local_size(0))
        v += a[k] * b[k];
    sums[t] = v;
    barrier(CLK_LOCAL_MEM_FENCE);
    for (int stride = get_local_size(0) / 2; stride; stride /= 2) {
        if (t < stride)
            sums[t] += sums[t + stride];
        barrier(CLK_LOCAL_MEM_FENCE);
    }
    if (t == 0)
        out[index] = sums[0];
}
