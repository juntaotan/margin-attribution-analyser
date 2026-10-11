# CUDA Agent Prompt: Implement Standard FGW in Five Steps

# Background

Assume that eligible actual consumption trees grouped by production ID, with recorded quantities and amounts, are already available. The user-confirmed production identifier is `inventory_usage.order_no`. Compare different production IDs of the **same product** at the same declared topology scope using FGW. Full multilevel lineage requires explicit upstream production links; FIFO allocation is not part of tree preparation.

This is File 2 of [the production analysis workflow](<Production Anomaly Analysis — Agent Workflow.md>). Validate supplied trees through [the production-ID tree specification](<../Agent Prompt_ Restore Product Production Trees Using FIFO.md>), whose historical filename is retained, and validate FGW through [the fixed numerical regression](<FGW CUDA Implementation — Numerical Test Case.md>). These are separate stages; work on this native solver does not establish that production-ID tree construction or backend integration is complete.

# Objective

The feature of finding out the abnormal transactions uses the **standard balanced Fused Gromov-Wasserstein (FGW)**  algorithm in CUDA and OpenCL for comparing manufacturing graphs with different node counts and unordered structures. Each node has one caller-supplied 384-dimensional material content embedding aligned with its CSR index. Use the node's own content vector, not the three-block neighbourhood vector or six-block BOM embedding. Graph topology is stored in CSR format. Embedding generation and model inference are outside this FGW implementation; report missing or misaligned embeddings rather than fabricating them.

Follow these five steps. For each step, specify inputs, outputs, and computation.

# Current Implementation and Scope

The following status describes the repository implementation reviewed on 11 October 2026. Preserve implemented behaviour when extending it; distinguish subsequent requirements from capabilities actually delivered and tested.

- The native CUDA module is implemented in [`fgw.cu`](../../backend/src/main/resources/cuda/fgw.cu), [`fgw_engine.cu`](../../backend/src/main/resources/cuda/fgw_engine.cu) and [`fgw_engine.cuh`](../../backend/src/main/resources/cuda/fgw_engine.cuh). It uses FP64 and cuBLAS. No FP32 execution path is supplied.
- The public interface supplies single-pair comparison, ordered comparisons of arbitrary pairs, and a persistent fixed-baseline session with bounded submission and independent GPU slots. Baseline preparation and execution resources can be reused across successive submissions.
- Exact device transportation simplex is implemented for `N+M <= 320`. Incremental basis-tree updates and candidate-edge pricing are enabled together by default. Each active start currently launches its own one-block simplex kernel on its slot's stream. Cross-target simplex problems are not grouped into a shared multi-block launch by the engine.
- cuBLAS batched matrix products process multiple starts within a comparison. Concurrent comparisons use separate streams. Describe these two implemented forms of parallelism precisely when reporting batching coverage.
- Full tracing, explicit CPU OT and larger transport problems use exact CPU OT inside the CUDA engine. This is an OT fallback, and still requires CUDA for the remaining calculation. The independent CPU FGW reference and benchmark are test tools; no complete production CPU FGW engine is exposed.
- Embedding generation, production-ID tree construction, Java/backend orchestration and an OpenCL implementation of this FGW module remain outside the delivered native CUDA stage. The wider CUDA/OpenCL objective above does not imply OpenCL delivery or test coverage.

The [native module README](../../backend/src/main/resources/cuda/test/fgw/README.md) documents the API, reproducible builds, device requirements, validation and benchmark entry points.

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

The implementation counts applied correspondence updates in `iterations`; the terminal fresh-gap check is retained as a diagnostic iteration without incrementing this count. `converged()` is true only for `FW_GAP_REACHED`. Cost stalling uses the absolute **or** relative change threshold after checking the fresh gap; reaching the update limit takes precedence over cost stalling.

# Input and Output Contract

- `Graph` supplies product/instance identifiers, a root index, unweighted CSR, exactly `nodeCount * 384` finite embedding values, and one `NodeSource` per CSR node. Reject empty or invalid graphs, disconnected undirected topology, incompatible product identities, invalid configuration, and dimension/mapping mismatches. Value validation cannot establish whether a caller semantically aligned an embedding with the correct material.
- Preserve occurrence, material, production-order and issue-line identifiers. Map the scoped production ID to `Graph.instanceId` and each occurrence's receiving production ID to `NodeSource.productionOrderId`. Keep additional upstream-source/completeness metadata alongside the native input/output. Leave the existing `NodeSource.fifoAllocationIds` empty for the production-ID workflow; retain the field for compatibility. Copy `quantity` and `amount` decimal text verbatim; neither changes the mathematical objective or node weights.
- Empty `Comparison.initialMatrices` means exactly one uniform outer-product start. Otherwise evaluate every supplied feasible row-major `[N,M]` matrix without appending an implicit uniform start. Keep each start's state independent.
- Return row/column source mappings, fixed weights, all start results, selected `bestStart`, final correspondence, separate material/structure/FGW costs, fresh gap, applied-update count, terminal status and any numerical diagnostic. Select the lowest finite cost amongst numerically valid starts. If every start fails, `bestStart=0` still refers to a failure; callers must inspect its status.
- Keep per-evaluation scalar diagnostics and OT certificates even when `captureTrace=false`. Full intermediate distance matrices and P/G/S/direction matrices are retained when tracing is enabled. Report original marginal errors and primal/dual/reduced-cost checks, rather than assuming correctness from the solver's termination.
- Invalid pair arguments throw `std::invalid_argument`; CUDA/cuBLAS resource errors throw `std::runtime_error`. In a session, target errors arrive through its future, while numerical failures remain explicit result statuses. Constructor failures occur before work is accepted.

# Additional Configuration Defaults

The mathematical defaults above and the following controls are defined by `Config` in `fgw_engine.cuh`:

| Control | Default | Meaning |
| --- | --- | --- |
| `feasibilityTolerance` | `1e-9` | Transport feasibility checks. |
| `numericalTolerance` | `1e-10` | Numerical checks, including objective/line-search handling. |
| `otTolerance` | `1e-12` | Exact linear OT tolerance; retain the solver's scale-aware certificate checks. |
| `device` | `0` | Configurable CUDA device index. |
| `maxConcurrentComparisons` | `1` | Maximum executing comparisons. |
| `gpuSlots` | `32` | Requested persistent stream/handle slots, capped by the comparison limit. |
| `dispatcherThreads` | `2` | Requested host dispatchers, capped by the effective slot count. |
| `queueCapacity` | `0` | Zero selects twice the effective slot count; bounds pending inputs. |
| `workspaceBytes` | `4 * 1024 * 1024` | Dedicated cuBLAS workspace bytes per slot; minimum 256 bytes. |
| `captureTrace` | `false` | Full diagnostic matrices; enabling this selects the synchronous CPU-OT path. |
| `profileExecution` | `false` | Collect overlapping CUDA event intervals and host wait timings. |
| `otSolver` | `LinearOtSolver::GPU_SIMPLEX` | Preferred exact OT execution path, subject to tracing and size limits. |
| `incrementalOtTree` | `true` | Incrementally update the device simplex basis tree. |
| `candidateOtPricing` | `true` | Reprice cached candidate edges before a full device pricing scan. |

With untouched defaults the effective pool contains **one slot and one dispatcher**. To exercise the measured 32-slot/two-dispatcher configuration, explicitly set `maxConcurrentComparisons=32`, `gpuSlots=32` and `dispatcherThreads=2`. The benchmark driver configures its comparison limit from its requested slot count; library defaults remain as shown above.

# Exact GPU Simplex Optimisations

Preserve the exact balanced LP and its certificate when changing solver internals:

- Uniform marginal masses are represented as integral units of `1/lcm(N,M)`. Initialise a feasible basis for every new comparison/start. Retain that basis across its Frank–Wolfe iterations, recompute prices for changed gradient costs, and reset it before another comparison uses the slot. Similar target structure does not currently reuse another target's basis or answer.
- With incremental tree updates enabled, build the rooted basis tree and potentials at the beginning of an OT solve. A pivot removes the leaving edge and reattaches the affected component at the entering edge, updating its parents, depths and potentials. Avoid a complete tree rebuild after every pivot.
- With candidate pricing enabled, a full scan caches one improving nonbasic arc per CUDA thread. Recompute cached reduced costs using current potentials on subsequent pivots. Refresh with a complete scan when the cache contains no improving edge. Require a complete scan with no improving edge before certifying optimality; candidate exhaustion alone is insufficient.
- Preserve device degeneracy and tie handling, including the lowest arc index for tied leaving choices. The CPU reference separately detects repeated degenerate bases and activates Bland's rule; the device kernel does not implement that repeated-basis mechanism. Reject nonfinite costs, disconnected bases and exhaustion of the device's 100,000-pivot safety limit explicitly. Perform primal/dual and original-marginal checks after solving.
- Expose `fullPricingScans`, `candidatePricingPasses`, `treeRebuilds`, `incrementalTreeUpdates`, `treeNodesVisited` and `cycleEdges` with the pivot diagnostics. CPU OT leaves these device counters zero. Compare work counters alongside runtime to distinguish reduced work from scheduling effects.

The pivot sequence within an OT problem remains dependent on preceding pivots. Pricing is parallel, while tree/cycle updates retain serial work. Current cross-target parallelism comes from independent stream submissions; grouping several ready targets into one simplex launch remains a subsequent optimisation requiring implementation and independent numerical/performance validation.

Keep explicit ablation modes available in the test and benchmark tools:

| Mode | `incrementalOtTree` | `candidateOtPricing` | Behaviour |
| --- | --- | --- | --- |
| `0` | `false` | `false` | Reference device solver. |
| `1` | `true` | `false` | Incremental tree updates only. |
| `2` | `false` | `true` | Candidate pricing only. |
| `3` | `true` | `true` | Combined solver, the current default. |

Tests use `FGW_SIMPLEX_MODE`; `benchmark.py` uses `--simplex-mode`. CPU/tracing fallbacks ignore these two device controls. Different exact pivot orders can select different optimal matrices for tied LPs; retain the objective/marginal comparisons specified in Step 4.

# Implementation Requirements

- Use exactly three production CUDA implementation files as specified below. Keep the mathematical algorithm in one file and separate it from CUDA execution management.
- Use **CUDA** for computationally intensive operations, especially batched matrix multiplication and gradient computation.
- Keep intermediate matrices in GPU memory where practical.
- Support unequal graph sizes and batched graph comparisons.
- Preserve standard balanced FGW mathematics; do not silently replace exact Linear OT with Sinkhorn.
- Validate numerical correctness against ​**Python Optimal Transport (POT)** .
- Record the POT version, explicit floating-point inputs, initial matrix, `loss_fun="square_loss"`, `symmetric=True`, `alpha`, `armijo=False`, and tolerances for reference validation.
- Keep root, production-ID, node-occurrence, quantity, amount and issue/source mappings available in the comparison output. Legacy FIFO identifiers remain empty for production-ID trees.
- State which operations run on CPU or GPU, including exact linear OT. Benchmark transfers and synchronization as part of complete runtime.
- Verify gradients using feasible zero-row-sum and zero-column-sum directions against the original quadratic objective.
- Reject invalid CSR or disconnected inputs rather than propagating infinite structural distances.
- State CUDA and OpenCL delivery/test coverage separately.
- Benchmark the complete CUDA implementation against an optimized multicore CPU baseline.

# Fixed-Baseline CUDA Execution

The primary workload compares one fixed baseline product tree with many nearby target trees of the same product. This execution model changes resource management only; retain the five mathematical steps, objective scale, exact balanced OT, uniform initialisation, tolerances, feasible starts and multi-start behaviour above.

## Public Execution Interfaces

- `CudaFgwEngine::compare` creates a session for one pair and executes with a comparison limit of one. Repeated calls recreate resources.
- `CudaFgwEngine::compareBatch` uses a persistent slot pool for the supplied vector, supports unequal pair sizes and returns all results in input order. Distinct baselines are prepared as needed. Inputs and outputs are materialised, so their memory grows with the vector size.
- `CudaFgwSession(baseline, config)` prepares one baseline and retains resources across repeated `submit(target, initialMatrices)` calls, including idle periods. Submit targets and consume futures in bounded groups for large workloads. A full pending queue applies backpressure, but callers must separately bound outstanding futures and retained results.

For example, after supplying validated `baselineTree` and `targetTree` values:

```cpp
fgw::Config config;
config.maxConcurrentComparisons = 32;
config.gpuSlots = 32;
config.dispatcherThreads = 2;
config.queueCapacity = 64;
fgw::CudaFgwSession session(baselineTree, config);
auto pending = session.submit(targetTree);
auto result = pending.get();
session.close();
```

The example submits one comparison. Sustained parallel execution requires several outstanding submissions within a bounded window. `close()` rejects further submissions and starts draining accepted work; session destruction joins dispatchers before releasing the baseline. Do not destroy the session concurrently with `submit()` or `close()`.

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

`SessionStatistics.workersCreated` counts host dispatchers; `gpuSlotsCreated` counts actual GPU slots. Report `maximumInFlight`, `maximumQueued`, baseline preparation count and device allocations/bytes as well. Completed statistics include numerical-failure results, so numerical acceptance requires checking each result's status. `Result.totalMilliseconds` measures comparison service time, excluding queue wait and session initialisation; use end-to-end wall time for throughput.

## Scale and Remaining Limits

Million-comparison workloads mean a large number of separate target requests. The session API supports incremental submission, but its existing short-run evidence does not validate million-request endurance. Do not merge targets into a single tree, reuse cached FGW answers, or reduce graph structure without a separate specification and numerical validation.

The `N+M <= 320` restriction applies to the nodes of each pair. For example, 128-by-160 uses device simplex; 128-by-1,600 exceeds its capacity and uses CPU OT while retaining CUDA for other work. Larger individual graphs also require dense FP64 `D1`, `D2` and correspondence/work matrices, with storage growing as `N^2 + M^2 + N*M` per active problem, plus multiple work buffers and starts. Increasing the request count or stream count does not enlarge the device solver's supported per-pair capacity. Larger device simplex, sparse/approximate formulations and cross-target shared launches are subsequent work; any formulation change needs explicit authorisation and a separate correctness specification.

## Throughput Validation

Complete the prescribed numerical regression, independent CPU comparison and POT validation before performance measurements. Also validate baseline preparation/resource counts, independent dispatcher/slot counts, queue and active-comparison bounds, reuse across batch boundaries, fresh initialisation after different starts, unequal target sizes, failure isolation and draining at session closure.

Fix the benchmark dimensions to a 128-node baseline and 160-node targets, each with 384-dimensional embeddings. Request 100, 500, 1,000, 2,000, 5,000 and 10,000 complete comparisons (18,600 in total per execution path). Use a reproducible pool of nearby target trees, submit them incrementally, and execute every request without caching previously computed FGW answers.

The smaller tiers check startup/queue behaviour; the larger tiers measure sustained throughput and repeated resource reuse within a practical validation run. With a 16-target pool, the largest tier processes each target 625 times. Label these as short-run benchmarks: they do not establish million-request endurance or production readiness. Keep counts configurable for separately requested longer runs.

Use equivalent inputs, precision, parameters, initialisation and stopping settings for an optimised multicore CPU baseline. Prepare its fixed baseline once and reuse its execution resources as well. Perform warm-up inside the same process/session as the measured comparisons; do not launch a fresh CUDA process per comparison.

Report initialisation, warm-up, steady processing, destruction and total elapsed time separately, with completed/requested counts, throughput, resource counts, marginal errors and numerical agreement. Record CPU and GPU exact-OT time, actual dispatcher/slot counts, CUDA event intervals for device work/transfers and host synchronisation waits; explain overlapping intervals rather than summing them into wall time. If CPU and CUDA paths use different exact OT implementations, disclose that comparison confound instead of attributing their performance difference solely to GPU execution.

An explicitly time-limited run is a measured prefix, not a completed benchmark at the requested count. Preserve its actual completed count, mark it incomplete, and never present an extrapolated duration as an actual million-comparison measurement.

# Validation Entry Points and Existing Evidence

Use the [native README's Docker or intentionally available host-toolchain commands](../../backend/src/main/resources/cuda/test/fgw/README.md). Configure the native module with `FGW_ONLY=ON` to build only FGW CUDA targets; `FGW_CPU_ONLY=ON` builds host mathematical validation without CUDA. The existing CMake configuration defines `cuda_fgw`, `fgw_host_test`, `fgw_cuda_test` and `fgw_cuda_run`.

For solver changes, complete this checklist before declaring numerical correctness or measuring performance:

1. Run `fgw_host_test` and actual-device `fgw_cuda_test`, retaining JSON output. CUDA CTest includes the default combined solver and explicit modes 0, 1 and 2, plus the host test: five tests in total. Device exit code 77 or a CTest skip means CUDA coverage is unverified.
2. Run [`validate.py`](../../backend/src/main/resources/cuda/test/fgw/validate.py) with native/device reports and executable paths. Complete every fixed-fixture intermediate check, independent original-objective/directional-derivative check, SciPy HiGHS exact-OT comparison and POT comparison at FP64 tolerance `1e-8`. Direct device OT fixtures and cold/warm solves are required; a tracing run through CPU OT alone does not establish device simplex correctness.
3. Verify feasible multiple starts, fresh terminal gaps, ordering invariance, unequal target sizes, error recovery, baseline preparation once, slot/dispatcher/queue bounds, resource reuse and shutdown draining. Retain source mappings and unchanged fixture expectations.
4. For changes to simplex memory or concurrency, run the README's compatible-device memory/leak checks and applicable shared-memory race checks. Report their actual scope and any unavailable prerequisite; racecheck alone does not establish host/global-memory race freedom.
5. Only after correctness passes, run [`benchmark.py`](../../backend/src/main/resources/cuda/test/fgw/benchmark.py) with matching CUDA numerical provenance and the six complete tiers above. Use [`simplex_experiment.py`](../../backend/src/main/resources/cuda/test/fgw/simplex_experiment.py) when comparing all four exact-solver modes. Preserve counts, precision, parameters, seeds and source/executable identity in evidence.

Existing evidence is historical validation of the documented native implementation, rather than tests performed by updating this document:

- [Default combined-solver validation](../tasks/2026-10-11-151443-default-combined-simplex/validation-summary.md) records CTest 5/5 without skips and 824 independent numerical checks, with maximum absolute error `4.444455914409673e-11` against `1e-8`.
- [The six-tier combined benchmark](../tasks/2026-10-11-152002-combined-fgw-benchmark/benchmark.md) completed 18,600 comparisons per path. At 10,000 requests, 32 slots/two dispatchers achieved 500.713 comparisons/s and 19.972 seconds of steady CUDA processing; the eight-worker CPU reference achieved 291.883/s and 34.260 seconds. The measured throughput ratio was 1.715 on that synthetic workload and device.
- [The 64-slot/four-dispatcher experiment](../tasks/2026-10-11-153004-fgw-64-streams/benchmark.md) achieved 290.891/s; its immediate 32-slot/two-dispatcher control achieved 500.569/s. More streams/dispatchers did not improve this workload, and defaults remain unchanged.

These records identify the tested hardware, solver differences, runtime phases and limitations. They do not predict performance for larger individual graphs, arbitrary production trees or million-request runs, and do not establish backend integration or production readiness.

# File Organisation

Use these filenames, following the existing CUDA algorithm/engine convention:

| File | Responsibility |
| --- | --- |
| `fgw.cu` | Keep all FGW mathematical routines and computational kernels in this single algorithm file: attribute and structural distances, costs, gradients, the linear OT subproblem logic, line-search calculations, and convergence criteria. |
| `fgw_engine.cu` | Manage CUDA execution: device selection, memory allocation and transfers, thread-block/grid configuration, kernel launches, streams, synchronisation, GPU library handles, and invocation of the algorithm routines. |
| `fgw_engine.cuh` | Declare the engine interface, graph inputs, configuration, results, and required routine/kernel interfaces. Keep implementation bodies in the two `.cu` files. |

Do not split the FGW mathematics across additional implementation files or duplicate its formulas in the engine. The engine may control execution and iteration, but must call the algorithm file's routines for mathematical calculations and convergence decisions. Test sources and build files are separate from this three-file implementation limit.

**Deliverables:** CUDA implementation, actual-product-tree comparison output with source mappings, and CUDA test.
