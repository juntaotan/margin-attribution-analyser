# Native CUDA FGW

This independent FP64 module compares caller-restored trees of the same product.
It does not generate embeddings, restore FIFO allocations, integrate Java, or
provide a production CPU fallback. The test-only CPU reference and baseline are
independent numerical/benchmark tools.

## Input and output

Include `fgw_engine.cuh` and link `cuda_fgw`. Populate `Graph` with unweighted CSR,
exactly `nodeCount * 384` finite doubles in CSR order, product/instance identifiers,
root index and one `NodeSource` per node. Each embedding describes that node's own
material content. Edges are interpreted as undirected; invalid CSR, disconnected
graphs, missing embeddings and misaligned source mappings are rejected.

`quantity` and `amount` are caller-supplied decimal text and are copied verbatim.
Occurrence, material, production-order, issue-line and FIFO-allocation identifiers
are also preserved. The returned row/column indices of `correspondence` refer
straight to `Result.left.nodes` and `Result.right.nodes`. No metadata is an extra
objective term. Callers retain responsibility for completed FIFO restoration and
embedding alignment; this module cannot infer semantic misalignment from values.

```cpp
fgw::Comparison input{leftTree, rightTree, {}};
fgw::Config config;
config.device = selectedDevice;
config.captureTrace = true;
auto result = fgw::CudaFgwEngine().compare(input, config);
const auto& selected = result.starts.at(result.bestStart);
```

An empty `initialMatrices` selects precisely the uniform outer product. Otherwise
all starts are explicit feasible row-major matrices with uniform marginals; no
implicit uniform start is added. Every start and its status is returned, and the
lowest finite cost amongst numerically valid starts is selected. If all fail,
`bestStart` is zero and its status is `NUMERICAL_FAILURE`; always inspect status.
`converged()` means `FW_GAP_REACHED` only, and certifies first-order stationarity.
A fresh gradient, exact OT solution and gap are evaluated after the last update,
including at an iteration limit. `COST_STALLED` and `MAX_ITERATIONS` are separate
non-converged outcomes. `iterations` counts applied updates.

`captureTrace` retains distance matrices, P/G/S/directions and coefficients.
Scalar costs, gap, cost changes, marginal errors and LP certificates are retained
for each evaluated iteration even with capture disabled. Numerical failures carry
a diagnostic. Invalid arguments throw `std::invalid_argument`; CUDA/cuBLAS
resource errors throw `std::runtime_error`, with owned resources released.

For repeated comparisons, construct `CudaFgwSession(baseline, config)` once and
submit targets incrementally:

```cpp
config.maxConcurrentComparisons = workerCount;
config.queueCapacity = 2 * workerCount;
fgw::CudaFgwSession session(baselineTree, config);
auto first = session.submit(targetTree); // Owns a copy/moved target and explicit starts.
auto result = first.get();              // Errors for this target arrive here.
// Continue submitting and consuming bounded groups; idle periods retain resources.
session.close();                       // Reject new work; accepted work drains.
```

The immutable baseline embeddings, distances, weights and structural constants
remain on the selected device. Each fixed worker thread owns one stream, handle
and non-overlapping cuBLAS workspace for its lifetime. Algorithm buffers retain
capacity and grow only when required by a larger target/start count. No previous
correspondence or answer is implicitly reused. `workspaceBytes` defaults to 4 MiB
per worker and must be at least 256 bytes. `queueCapacity=0` selects twice the
worker count. Callers must bound outstanding futures/results as well as inputs.
Destruction joins all workers before releasing baseline memory. Individual input
errors do not terminate the session; closed sessions reject `submit()` immediately.

`statistics()` reports accepted/completed/failed counts, baseline preparations,
workers created, queue high-water mark and worker allocation/capacity accounting.
`profileExecution` enables CUDA event intervals; CPU OT and explicit stream waits
are also timed in `Result.executionTimes`. These worker intervals overlap and
must not be added to produce batch wall time.

`compareBatch` preserves pair order and supports different sizes. It shares a
baseline session when all left graphs/mappings are identical; arbitrary pairs
still use a fixed thread/resource pool, preparing each distinct pair's baseline.
Configure `maxConcurrentComparisons` for independent streams/handles. Multi-start products
within one comparison use cuBLAS batched DGEMM with shared read-only structures.
There is no device-specific node-count threshold or automatic execution switch.

## Execution and mathematical contract

- GPU: 384-dimensional squared Euclidean distances, undirected all-pairs BFS,
  fixed uniform weights, cost/gradient kernels, batched FP64 matrix products,
  dot products, directions and correspondence updates.
- CPU: CSR/metadata/config validation, exact divisible-mass balanced linear OT
  using transportation simplex with primal/dual certificates, quadratic
  line-search selection and stopping decisions. Each iteration transfers the
  gradient for OT, returns S to the GPU and checks P's original marginals.
- All production mathematics and computational kernels are in `fgw.cu`. Its
  matrix-product plan declares the mathematical product order. The engine owns
  buffers, streams, launch grids, handles and library execution. No formulas are
  duplicated in the engine.
- There is no Sinkhorn, assignment restriction, normalisation, square root,
  half-loss scale, quantity penalty or amount penalty.

Defaults are exactly `alpha=0.8`, `tolAbs=1e-6`, `tolRel=1e-5`,
`maxIterations=200`, `tolGapAbs=1e-6`, `tolGapRel=1e-5` and
`costScaleFloor=1e-12`. FP64 validation uses `1e-8`. Additional numerical controls
are documented in `Config`; exact OT defaults to `1e-12` and validates feasibility,
reduced costs and the dual objective. Cost stalling uses the absolute **or**
relative change threshold after testing the fresh gap; the iteration limit takes
precedence over cost stalling. Production matrices require quadratic storage.
No FP32 implementation is supplied.

## Docker builds and tests (default)

Run these commands from the repository root. Docker needs network access for
initial images/packages; Python dependencies are pinned in `requirements.txt`.
The CPU stage uses Python 3.12, GCC and CMake. The CUDA stage adds the NVIDIA toolkit
and cuBLAS. CPU and CUDA images/architectures can be overridden with build args.

```sh
docker build --target cpu -t margin-fgw-cpu-check \
  -f backend/src/main/resources/cuda/test/fgw/Dockerfile \
  backend/src/main/resources/cuda
docker run --rm margin-fgw-cpu-check

mkdir -p /tmp/margin-fgw-evidence
docker run --rm --user "$(id -u):$(id -g)" \
  -v /tmp/margin-fgw-evidence:/out margin-fgw-cpu-check sh -c \
  '/build/fgw_host_test /out/host.json && python test/fgw/validate.py --native-report /out/host.json --native-executable /build/fgw_host_test --output /out/cpu-report.md'

docker build --target cuda -t margin-fgw-cuda-check \
  --build-arg CUDA_ARCHITECTURES=75 \
  -f backend/src/main/resources/cuda/test/fgw/Dockerfile \
  backend/src/main/resources/cuda
docker run --rm --gpus all -e FGW_DEVICE=0 \
  --user "$(id -u):$(id -g)" -v /tmp/margin-fgw-evidence:/out \
  margin-fgw-cuda-check sh -c \
  'ctest --test-dir /build --output-on-failure && /build/fgw_host_test /out/host.json && /build/fgw_cuda_test /out/cuda.json && python test/fgw/validate.py --native-report /out/host.json --cuda-report /out/cuda.json --native-executable /build/fgw_host_test --output /out/cuda-report.md'
```

CUDA execution requires a compatible NVIDIA device/driver and configured NVIDIA
Container Toolkit/CDI passthrough. Architecture `75` includes a PTX image for
forward JIT; choose supported architectures appropriate to the toolkit/device,
for example a CMake semicolon list. Device selection is configurable through
`Config.device`, `FGW_DEVICE` for regression, and `--device` for benchmarking.
A CUDA regression exit code of `77` means **unverified**, and CTest marks it skipped;
this is not GPU validation passing. The direct regression command above preserves
that nonzero outcome.

## Host-toolchain alternative

Use an intentionally available C++17/CMake 3.24+ toolchain and compatible CUDA
compiler/driver. No Java or database service is involved.

```sh
cmake -S backend/src/main/resources/cuda -B /tmp/margin-fgw-host \
  -DFGW_CPU_ONLY=ON -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/margin-fgw-host -j 4
ctest --test-dir /tmp/margin-fgw-host --output-on-failure

cmake -S backend/src/main/resources/cuda -B /tmp/margin-fgw-cuda \
  -DFGW_ONLY=ON -DCMAKE_BUILD_TYPE=Release -DCMAKE_CUDA_ARCHITECTURES=75
cmake --build /tmp/margin-fgw-cuda -j 4
ctest --test-dir /tmp/margin-fgw-cuda -R '^fgw_' --output-on-failure
/tmp/margin-fgw-cuda/fgw_host_test /tmp/margin-fgw-host.json
FGW_DEVICE=0 /tmp/margin-fgw-cuda/fgw_cuda_test /tmp/margin-fgw-cuda.json

python3 -m venv /tmp/margin-fgw-venv
/tmp/margin-fgw-venv/bin/pip install -r backend/src/main/resources/cuda/test/fgw/requirements.txt
/tmp/margin-fgw-venv/bin/python backend/src/main/resources/cuda/test/fgw/validate.py \
  --native-report /tmp/margin-fgw-host.json --cuda-report /tmp/margin-fgw-cuda.json \
  --native-executable /tmp/margin-fgw-cuda/fgw_host_test \
  --output /tmp/margin-fgw-report.md
```

Omit `FGW_ONLY` to build the existing BOM/LOF targets as well. Installed GTest is
preferred, retaining the previous FetchContent fallback. FGW itself adds no new
production dependency beyond cuBLAS from the existing CUDA toolkit.

## Validation and report

The fixed 2-by-3 fixture has only coordinate zero populated (`A=0`, `X=1`, `B=2`).
Uniform initialisation is unchanged. The native regression checks every specified
matrix/cost, exact OT certificate, gap, direction, step, final gradient,
convergence, marginal feasibility, finite/nonnegative weights, feasible numerical
directional derivatives, objective monotonicity, ordering invariance, unequal-size
feasible multi-starts, fresh stopping statuses and source preservation. CUDA also
executes concurrent batches of unequal comparisons.

`validate.py` independently constructs shortest paths, evaluates the original
four-index objective and gradient, solves balanced OT with SciPy HiGHS, and fits
the line-search quadratic from three original-objective values. It validates
POT's initial/intermediate/final values with explicit FP64 `G0=outer(p,q)`,
`loss_fun="square_loss"`, `symmetric=True`, `alpha=0.8`, `armijo=False`,
`max_iter=200`, `tol_abs=1e-6`, `tol_rel=1e-5`. It records installed versions.
Sixteen further exact-OT cases check negative/tied costs, unequal sizes, one-node
marginals and random/degenerate costs up to 128-by-160 against HiGHS/POT. Tied
solutions are compared by objective/marginals. The specialised primal network
simplex uses integral transport mass in units of `1/lcm(N,M)`, tree duals, block
pricing, incremental basis hashing and Bland's rule if degenerate bases repeat.
Within each comparison/start it retains a feasible OT basis across FW iterations;
each solve recomputes prices and validates its primal/dual certificate. Bases are
reset between comparisons. It adds no dependency.
Session checks cover resource reuse across input batches, independent starts,
capacity growth, target-error recovery, source preservation and shutdown draining.

Reports contain actual/expected matrices, maximum errors, iteration costs,
marginal errors, LP certificates, starts, stopping statuses and PASS/FAIL results.
Missing CUDA evidence is explicitly unverified. POT's configuration follows its
[official FGW API](https://pythonot.github.io/gen_modules/ot.gromov.html).

## Benchmark after correctness

CUDA timing requires a passing numerical JSON report with CUDA provenance.
The benchmark fixes a 128-node baseline and 160-node targets with 384-dimensional
embeddings. A fixed-seed pool of 16 nearby targets perturbs embeddings and
reparents leaves. Every submitted request prepares its target and executes
complete FGW; no results or target distance matrices are memoised. Memory is
bounded by the pool, worker count, queue and consumed results.

The independent baseline uses SciPy shortest paths/squared distances, NumPy BLAS
products and POT's compiled exact network simplex. `--workers` controls persistent
CPU and CUDA workers; `--threads` is the CPU BLAS thread budget, divided across
CPU workers (at least one thread each). The CPU baseline prepares its fixed
structure, weights and constants once. POT OT is single-threaded per worker;
independent comparisons use multiple workers. The native CUDA path uses its own
exact CPU transportation simplex. This implementation difference remains an
explicit confound in comparisons of complete runtime.

```sh
# Short complete pilot, including same-session warm-up:
/tmp/margin-fgw-venv/bin/python backend/src/main/resources/cuda/test/fgw/benchmark.py \
  --counts 64 --workers 4 --threads 4 --warmup 64 \
  --cuda-run /tmp/margin-fgw-cuda/fgw_cuda_run \
  --cuda-validation-report /tmp/margin-fgw-report.json --device 0 \
  --output /tmp/margin-fgw-pilot.json

# Six short benchmark tiers. No time limit is imposed by default.
/tmp/margin-fgw-venv/bin/python backend/src/main/resources/cuda/test/fgw/benchmark.py \
  --counts 100,500,1000,2000,5000,10000 \
  --workers 4 --threads 4 --warmup 64 \
  --cuda-run /tmp/margin-fgw-cuda/fgw_cuda_run \
  --cuda-validation-report /tmp/margin-fgw-report.json --device 0 \
  --output /tmp/margin-fgw-throughput.json

# Equivalent Docker invocation after generating /out/cuda-report.json:
docker run --rm --gpus all --user "$(id -u):$(id -g)" \
  -v /tmp/margin-fgw-evidence:/out margin-fgw-cuda-check \
  python test/fgw/benchmark.py --workers 4 --threads 4 --warmup 64 \
  --counts 100,500,1000,2000,5000,10000 \
  --cuda-run /build/fgw_cuda_run --cuda-validation-report /out/cuda-report.json \
  --output /out/throughput.json
```

The standard tiers are 100, 500, 1,000, 2,000, 5,000 and 10,000 complete comparisons
(18,600 in total per execution path). The first tiers provide smoke/overhead checks;
the larger tiers measure sustained throughput and repeated resource reuse without
requiring a multi-hour run. With the default 16-target pool, the 10,000 tier processes
each target 625 times. These are short-run measurements, not evidence of
million-request endurance. Counts remain configurable through `--counts`.

The runner uses one process/session per count and warms up that same session.
Reports distinguish initialisation, warm-up, steady processing and total time
through resource destruction. Input-file parsing/process launch/device discovery
are excluded. Steady timing includes complete preparation, validation, transfers,
exact OT, synchronisation, result collection and per-result checks. Counts refer
to completed comparisons, excluding all reference/warm-up requests.

Each pool target is compared against the independent CPU result for separate
material/structure/FGW costs, gap, status and updates. Every repeated comparison
is checked for stable results and marginal feasibility. Reports include CPU OT,
CUDA event intervals, explicit synchronisation waits, queue/resource counts and
new device allocations after warm-up. Event intervals include kernels/transfers
and gaps in host submission; they are not isolated kernel-only timings.

`--max-seconds 30` optionally bounds steady submission time **per count and
execution path**, draining accepted work afterwards. It records actual completed
prefixes as `INCOMPLETE_TIME_LIMIT` and exits 2; such prefixes are never a PASS at
the requested count. Reports are saved after each execution so completed evidence
survives interruption. Omit CUDA arguments for CPU-only coverage.

Optional CUDA memory/concurrency checks after a CUDA build:

```sh
compute-sanitizer --tool memcheck --report-api-errors explicit --leak-check full --error-exitcode 1 /tmp/margin-fgw-cuda/fgw_cuda_test
compute-sanitizer --tool racecheck --error-exitcode 1 /tmp/margin-fgw-cuda/fgw_cuda_test
```

The memcheck command checks explicit API failures and device accesses/leaks.
CUDA 13.4's default extended API reporting also reports a selective-recompilation
warning when loading forward PTX on the tested device; that warning is recorded
separately and is not a device memory-access failure. Racecheck checks shared
memory hazards, and does not by itself certify host/global-memory race freedom.
These checks require compatible hardware/toolkit. Measurements describe these
synthetic trees and the tested device, and do not establish production integration.
