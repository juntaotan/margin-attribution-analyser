# CUDA Agent Prompt: Implement Standard FGW in Five Steps

# Background

Assume that complete production trees with FIFO-restored actual quantities and amounts are already available. Compare different material compositions and production structures of the **same product** using FGW.

# Objective

The feature of finding out the abnormal transactions uses the **standard balanced Fused Gromov-Wasserstein (FGW)**  algorithm in CUDA and OpenCL for comparing manufacturing graphs with different node counts and unordered structures. Each node has one caller-supplied 384-dimensional material content embedding aligned with its CSR index. Use the node's own content vector, not the three-block neighbourhood vector or six-block BOM embedding. Graph topology is stored in CSR format. Embedding generation and model inference are outside this FGW implementation; report missing or misaligned embeddings rather than fabricating them.

Follow these five steps. For each step, specify inputs, outputs, and computation.

# Step 1: Calculate Distance Matrices

**Inputs:**  Two CSR graphs (`G1`​, `G2`​) and material content embeddings `[N, 384]`​ and `[M, 384]` in the same node order as their respective CSR graphs.

**Outputs:**  Material distance matrix `M_attr [N, M]`​and structural distance matrices `D1 [N, N]`​, `D2 [M, M]`.

**Computation:**

(1) Calculate squared Euclidean distances between cross-graph node embeddings.

(2) Calculate all-pairs shortest-path distances within each graph, initially treating edges as undirected.

# Step 2: Initialise Weights

**Inputs:**  Node counts `N`​, `M`.

**Outputs:**  Weight vectors `p`​, `q`​ and correspondence matrix `P`.

**Computation:**

(1) Assign uniform weights: `p[i] = 1/N`​, `q[j] = 1/M`.

(2) Initialise `P = outer(p, q)`. Support an explicitly supplied feasible initial matrix for additional tests or multiple-start comparisons. The fixed numerical test must use its prescribed uniform outer product.

(3) Keep `p`​ and `q` fixed throughout optimisation.

# Step 3: Calculate Initial FGW Cost

**Inputs:**  `M_attr`​, `D1`​, `D2`​, `P`​, `alpha`.

**Outputs:**  `materialCost`​, `structureCost`​, `fgwCost`.

**Computation:**

(1) Calculate material cost using weighted embedding distances.

(2) Calculate structure cost using weighted squared differences between intra-graph distances.

(3) Combine both costs using `alpha`.

(4) Use efficient matrix operations instead of four nested loops.

Use this exact objective and its constraints:

```text
materialCost  = <M_attr, P>
structureCost = sum_{i,k,j,l} (D1[i,k] - D2[j,l])^2 * P[i,j] * P[k,l]
fgwCost       = (1-alpha) * materialCost + alpha * structureCost

P >= 0
P * ones(M) = p
transpose(P) * ones(N) = q
```

For symmetric structural matrices and a feasible transport matrix, precompute the fixed-marginal term and evaluate the structure cost as:

```text
C = outer(elementwise_square(D1) * p, ones(M))
  + outer(ones(N), elementwise_square(D2) * q)
H(P) = C - 2 * D1 * P * transpose(D2)
structureCost = <H(P), P>
```

Here `<A,B>` is the elementwise inner product, `*` denotes matrix/vector multiplication, and `elementwise_square` means elementwise squaring. Report the objective itself without introducing a square root or a factor of one half.


# Step 4: Optimise P Using Frank–Wolfe

**Inputs:**  Current `P`​, fixed `p`​, `q`​, distance matrices, `alpha`.

**Outputs:**  Updated `P`, new FGW cost.

**Computation:**

(1) **Gradient:**  Differentiate the FGW objective with respect to the current `P`. Accelerate matrix operations using CUDA.

(2) **Linear OT:**  Solve the balanced linear transport problem using the gradient to obtain candidate matrix `S`.

(3) **Direction:**  Calculate `deltaP = S - P`.

(4) **Line Search:**  Find the optimal step size `gamma` between 0 and 1.

(5) **Update:**  Calculate `newP = P + gamma * deltaP`, then recalculate FGW cost.


For symmetric structural matrices, evaluate the original objective's gradient at the feasible current matrix using:

```text
G = (1-alpha) * M_attr + 2 * alpha * H(P)
S = argmin <G, S>
    subject to S >= 0, S*ones(M) = p, transpose(S)*ones(N) = q
FW_Gap = <G, P-S>
```

Use an exact balanced transport LP solver to numerical tolerance. Verify feasibility and optimality. Do not replace this problem with one-to-one assignment or entropic Sinkhorn. In additional fixtures with multiple optimal linear solutions, compare linear cost and marginals rather than requiring identical matrices.

Use exact quadratic line search with `deltaP = S-P`:

```text
F(P + gamma*deltaP) = F(P) + b*gamma + a*gamma^2
a = -2 * alpha * <D1 * deltaP * transpose(D2), deltaP>
b = <G, deltaP>
```

If `a > 0`, consider the clipped stationary point and both endpoints. If `a <= 0`, compare the endpoints. Handle near-zero coefficients explicitly. Verify that the selected step does not increase the true objective beyond numerical tolerance.

Repeat using the updated `P`​. Recompute the gradient and `S` every iteration.

# Step 5: Check Convergence

**Inputs:**  Previous/current `P`, previous/current FGW cost, convergence thresholds.

**Outputs:**  Final `P`, FGW cost, iteration count, convergence status.

**Computation:**

(1) Recompute the gradient, exact linear OT result, and FW gap at the current matrix. Verify finite values, nonnegative transport weights to numerical tolerance, and original marginals.

(2) Require `FW_Gap <= tolGapAbs + tolGapRel * max(abs(fgwCost), costScaleFloor)` for first-order convergence. Record absolute and relative cost changes as additional stopping diagnostics. A significantly negative gap is a numerical or OT failure.

(3) Report `FW_GAP_REACHED`, `COST_STALLED`, `MAX_ITERATIONS`, or `NUMERICAL_FAILURE` separately. Reaching the iteration limit alone is not convergence. Check the gap at the returned matrix after the final update.

(4) A zero gap certifies first-order stationarity, not global optimality. Support feasible multiple starts when required by the comparison or node-ordering test. Keep their configuration and results in the report.

Initial parameters:

```
alpha         = 0.8
tolAbs        = 1e-6
tolRel        = 1e-5
maxIterations = 200
tolGapAbs     = 1e-6
tolGapRel     = 1e-5
costScaleFloor = 1e-12
```

# Implementation Requirements

- Use exactly three production CUDA implementation files as specified below. Keep the mathematical algorithm in one file and separate it from CUDA execution management.
- Use **CUDA** for computationally intensive operations, especially batched matrix multiplication and gradient computation.
- Keep intermediate matrices in GPU memory where practical.
- Support unequal graph sizes and batched graph comparisons.
- Preserve standard balanced FGW mathematics; do not silently replace exact Linear OT with Sinkhorn.
- Validate numerical correctness against ​**Python Optimal Transport (POT)** .
- Record the POT version, explicit floating-point inputs, initial matrix, `loss_fun="square_loss"`, `symmetric=True`, `alpha`, `armijo=False`, and tolerances for reference validation.
- Keep root, node-occurrence, quantity, amount, and FIFO source mappings available in the comparison output.
- State which operations run on CPU or GPU, including exact linear OT. Benchmark transfers and synchronization as part of complete runtime.
- Verify gradients using feasible zero-row-sum and zero-column-sum directions against the original quadratic objective.
- Reject invalid CSR or disconnected inputs rather than propagating infinite structural distances.
- State CUDA and OpenCL delivery/test coverage separately.
- Benchmark the complete CUDA implementation against an optimized multicore CPU baseline.

# Fixed-Baseline CUDA Execution

The primary workload compares one fixed baseline product tree with many nearby target trees of the same product. This execution model changes resource management only; retain the five mathematical steps, objective scale, exact balanced OT, uniform initialisation, tolerances, feasible starts and multi-start behaviour above.

## Baseline Lifetime

- Validate and prepare the baseline once per processing session on the selected CUDA device.
- Keep its `[N, 384]` material content embeddings, undirected structural distance matrix `D1`, uniform weights `p` and reusable fixed-marginal structural constants read-only in device memory for all comparisons in that session.
- Share this immutable baseline across the session's execution workers on that device. Establish readiness with a CUDA event before worker streams read it. Do not upload or recompute it for each target or input batch.
- Preserve the baseline's node order and source identifiers, restored quantities and decimal amounts for output mapping. These remain metadata rather than additional objective terms.
- Release the baseline only after all accepted comparisons have finished and their device work has been drained. A new baseline requires a new preparation/session.

## Persistent Execution Resources

- Create a configurable pool of persistent GPU slots, each with its own CUDA stream, cuBLAS handle, non-overlapping workspace, algorithm buffers and pinned transfer buffers. A small, independently configured set of host dispatcher threads exclusively owns disjoint groups of slots. Never access a mutable handle concurrently from different dispatchers.
- `maxConcurrentComparisons` remains the maximum number of executing comparisons. The effective slot count is `min(gpuSlots, maxConcurrentComparisons)`; `gpuSlots` defaults to 32 and `dispatcherThreads` to 2, capped at the effective slot count. Increasing stream capacity must not create one host thread per stream.
- Retain streams, handles, workspaces and buffer capacity across comparisons, input-batch boundaries and idle periods. Grow buffers only after previous work has completed. Bind the stream before setting the dedicated cuBLAS workspace because `cublasSetStream()` resets custom workspace configuration.
- Every comparison/start owns independent target data, correspondence matrices, gradients, exact OT state, directions, costs, update counts and stopping statuses. Reusing storage must not implicitly reuse an earlier comparison's state or answer. Preserve feasible explicit starts and unequal graph sizes.
- On the normal GPU-simplex path, enqueue one complete Frank–Wolfe iteration, including cost/gradient, exact OT, direction, fresh gap and line-search products, without intermediate host synchronisation. Download scalar summaries into persistent pinned memory. Dispatchers use non-blocking stream queries, advance ready slots, and query the oldest submission when all their slots are busy and none advanced. Yield for a bounded host interval before polling again; an unbounded stream synchronisation must not hide other ready slots.
- Perform quadratic line-search and stopping decisions on the host after the scalar summaries are ready. Verify original correspondence/candidate marginals and the actual OT primal/dual certificate. Download correspondence matrices only after all starts terminate and verify the returned marginals before reporting convergence.
- Keep exact balanced OT: use the device transportation simplex within its supported `N+M <= 320` capacity. Explicit CPU OT, larger problems and full diagnostic tracing use a synchronous exact-CPU-OT path on the owning dispatcher. Report that placement; these diagnostic/fallback comparisons can delay other slots owned by that dispatcher. Do not replace exact OT with Sinkhorn.
- GPU simplex must reject nonfinite arc costs, disconnected bases and pivot-limit exhaustion explicitly rather than returning a certified FW gap or running indefinitely. Preserve the numerical tolerances and certificate requirements.
- Profiling must not add blocking event waits to asynchronous iteration submission. Collect elapsed CUDA event intervals after the stream is ready. GPU OT intervals overlap the complete device intervals; do not sum them into wall time.
- Bound queued inputs and outstanding results. Keep accepted task/CSR/pointer-array storage alive until its stream has finished. Report failures individually, drain device work before buffer reuse, reject submissions after closure, and finish accepted work before releasing baseline resources.

## Throughput Validation

Complete the prescribed numerical regression, independent CPU comparison and POT validation before performance measurements. Also validate baseline preparation/resource counts, independent dispatcher/slot counts, queue and active-comparison bounds, reuse across batch boundaries, fresh initialisation after different starts, unequal target sizes, failure isolation and draining at session closure.

Fix the benchmark dimensions to a 128-node baseline and 160-node targets, each with 384-dimensional embeddings. Request 100, 500, 1,000, 2,000, 5,000 and 10,000 complete comparisons (18,600 in total per execution path). Use a reproducible pool of nearby target trees, submit them incrementally, and execute every request without caching previously computed FGW answers.

The smaller tiers check startup/queue behaviour; the larger tiers measure sustained throughput and repeated resource reuse within a practical validation run. With a 16-target pool, the largest tier processes each target 625 times. Label these as short-run benchmarks: they do not establish million-request endurance or production readiness. Keep counts configurable for separately requested longer runs.

Use equivalent inputs, precision, parameters, initialisation and stopping settings for an optimised multicore CPU baseline. Prepare its fixed baseline once and reuse its execution resources as well. Perform warm-up inside the same process/session as the measured comparisons; do not launch a fresh CUDA process per comparison.

Report initialisation, warm-up, steady processing, destruction and total elapsed time separately, with completed/requested counts, throughput, resource counts, marginal errors and numerical agreement. Record CPU and GPU exact-OT time, actual dispatcher/slot counts, CUDA event intervals for device work/transfers and host synchronisation waits; explain overlapping intervals rather than summing them into wall time. If CPU and CUDA paths use different exact OT implementations, disclose that comparison confound instead of attributing their performance difference solely to GPU execution.

An explicitly time-limited run is a measured prefix, not a completed benchmark at the requested count. Preserve its actual completed count, mark it incomplete, and never present an extrapolated duration as an actual million-comparison measurement.

# File Organisation

Use these filenames, following the existing CUDA algorithm/engine convention:

| File | Responsibility |
| --- | --- |
| `fgw.cu` | Keep all FGW mathematical routines and computational kernels in this single algorithm file: attribute and structural distances, costs, gradients, the linear OT subproblem logic, line-search calculations, and convergence criteria. |
| `fgw_engine.cu` | Manage CUDA execution: device selection, memory allocation and transfers, thread-block/grid configuration, kernel launches, streams, synchronisation, GPU library handles, and invocation of the algorithm routines. |
| `fgw_engine.cuh` | Declare the engine interface, graph inputs, configuration, results, and required routine/kernel interfaces. Keep implementation bodies in the two `.cu` files. |

Do not split the FGW mathematics across additional implementation files or duplicate its formulas in the engine. The engine may control execution and iteration, but must call the algorithm file's routines for mathematical calculations and convergence decisions. Test sources and build files are separate from this three-file implementation limit.

**Deliverables:** CUDA implementation, actual-product-tree comparison output with source mappings, and CUDA test.
