# FGW benchmark and numerical validation

This package contains the shared FGW benchmark, independent CPU/POT validator,
Java/OpenCL/CUDA validation harness. The complete original CUDA FGW regression
package, including its result validator and direct device OT fixtures, remains
at [cuda/test/fgw](../../backend/src/main/resources/cuda/test/fgw/README.md).
CUDA CMake builds those canonical drivers; the shared benchmark remains here.

`benchmark.py` retains the original CUDA benchmark's fixture generation, complete
CPU reference, defaults, CUDA options and JSON fields. Optional `--opencl-run`
and `--java-classes` add results under separate `opencl` and `java` keys; `cpu`
always identifies the multicore NumPy/SciPy/POT reference. Java performs its
numerical calculations serially in one calling thread. The Python launcher does
not calculate on behalf of the Java engine.

## Shared commands

Run from the repository root with intentionally available native toolchains,
Java 21 and the Python dependencies in [requirements.txt](requirements.txt).
Build/test commands for CUDA appear below; OpenCL follows
[its device-test README](../../backend/src/main/resources/opencl/test/README.md).
Before timing either port, run Java 21 backend tests and actual-device CTest,
then the numerical harness:

```sh
(cd backend && ./mvnw --batch-mode --no-transfer-progress test)
python benchmark/fgw/validate_ports.py \
  --opencl-build backend/src/main/resources/opencl/build \
  --cuda-build /tmp/margin-fgw-cuda --output-dir /tmp/margin-fgw-ports

# All four paths on identical inputs; default counts are the six complete tiers.
python benchmark/fgw/benchmark.py \
  --workers 8 --threads 8 --warmup 64 \
  --cuda-run /tmp/margin-fgw-cuda/fgw_cuda_run \
  --cuda-validation-report /tmp/margin-fgw-ports/opencl-validation-mode-3.json \
  --opencl-run backend/src/main/resources/opencl/build/fgw_opencl_run \
  --opencl-workers 2 --java-classes /tmp/margin-fgw-ports/java-classes \
  --validation-dir /tmp/margin-fgw-ports --output /tmp/margin-fgw-all.json
```

Use the Python interpreter whose environment contains the pinned dependencies.
Omit the Java/OpenCL options for the original CPU/CUDA interface; omit all engine
options for an independent CPU benchmark. Ports require complete four-mode
passing numerical evidence with unchanged numerical-source hashes. CUDA also
requires matching-mode CPU/POT/CUDA evidence through the original flag. No
missing execution is reported as passing. OpenCL rejects CPU-OT fallback when
claiming device throughput. Java batch timing uses its default combined simplex
mode; `--simplex-mode` must be `3` when Java is requested. Java runs complete
counts and does not support the optional `--max-seconds` prefix limit.

Fixtures, precision, stopping thresholds and seeds match the original CUDA
benchmark. Reports retain complete native/Java pool results, phase timings,
completed/requested counts and numerical checks. Runtime is measured separately
for each path; multicore CPU timings must not be labelled Java serial.

## Migration parity check

[test_benchmark.py](test_benchmark.py) checks all 16 fixture graphs and the
byte-identical pool, every CPU correspondence/iteration trace, and validation
failure handling. By default it reads the latest Git revision that added or modified the original
CUDA benchmark, including after the relocation is committed.
For a different starting revision, pass `--original-benchmark` with a saved file.
The integration check executes both actual CPU/CUDA CLIs at all six tiers,
checks completion and compares costs, gaps, iteration traces, stopping statuses,
OT-work counters and stable resource counts at `1e-8`. Separate executions have
different timings and scheduling; these are recorded rather than asserted equal.

```sh
python benchmark/fgw/test_benchmark.py
python benchmark/fgw/test_benchmark.py --integration \
  --cuda-run /tmp/margin-fgw-cuda/fgw_cuda_run \
  --cuda-validation-report /tmp/margin-fgw-ports/opencl-validation-mode-3.json \
  --output-dir /tmp/margin-fgw-parity
```

The Git-based default is a migration reference; it requires a revision containing
the original source. A source export supplied explicitly works without Git.

## CUDA engine and native tooling

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
config.maxConcurrentComparisons = 32; // Maximum executing comparisons.
config.gpuSlots = 32;
config.dispatcherThreads = 2;
config.queueCapacity = 64;
fgw::CudaFgwSession session(baselineTree, config);
auto first = session.submit(targetTree); // Owns a copy/moved target and explicit starts.
auto result = first.get();              // Errors for this target arrive here.
// Continue submitting and consuming bounded groups; idle periods retain resources.
session.close();                       // Reject new work; accepted work drains.
```

The immutable baseline embeddings, distances, weights and structural constants
remain on the selected device. Each GPU slot owns a persistent stream, handle,
non-overlapping cuBLAS workspace and capacity-reused device/pinned buffers. Each
host dispatcher exclusively owns several slots. `gpuSlots=32` and
`dispatcherThreads=2` are defaults; the effective slot count is
`min(gpuSlots, maxConcurrentComparisons)` and dispatchers are capped at that count.
The existing default `maxConcurrentComparisons=1` therefore still executes only
one comparison at a time. `queueCapacity=0` selects twice the effective slot count.
`workspaceBytes` defaults to 4 MiB per slot and must be at least 256 bytes.
Callers must also bound outstanding futures/results.

Normal, untraced GPU-simplex iterations submit costs, gradients, OT, directions,
gaps and line-search products together, without intermediate host waits. Each
start downloads 25 FP64 scalar values plus a pivot count into persistent pinned
memory. These include actual original-marginal errors and primal/dual LP
certificates. Dispatchers query streams round-robin and advance ready slots; when
all owned slots are busy and none advances, they query the oldest submission and yield with a requested 50-microsecond timeout
before polling again (actual wake-up also depends on host scheduling). This avoids an unbounded stream wait hiding other ready slots.
Full correspondence matrices are downloaded only after all starts terminate.
Final marginal checks can change a result to `NUMERICAL_FAILURE` before return.

Full tracing, explicit `CPU_REFERENCE` OT and `N+M>320` use the synchronous
exact-CPU-OT diagnostic path on the assigned dispatcher. This preserves full
traces/certificates but may delay other slots on that dispatcher. No separate
production CPU FGW engine is supplied. Device simplex rejects nonfinite costs,
disconnected bases and exhaustion of its 100,000-pivot safety limit explicitly.

Destruction drains all accepted work and joins dispatchers before releasing the
baseline. Target failures reach their futures and do not contaminate subsequent
targets. `statistics()` retains existing field names: `workersCreated` now counts
host dispatchers, and `workerDeviceAllocations`/`workerDeviceBytes` aggregate slot
buffers. `gpuSlotsCreated` and `maximumInFlight` report actual stream capacity and
peak executing comparisons. Queue and active-comparison bounds are independent.

`profileExecution` records event intervals and collects them after a successful
stream query, without adding event waits to asynchronous submission. CPU/GPU OT,
device intervals and explicit host waits overlap; do not add them into wall time.
`compareBatch` preserves pair order and supports distinct baselines using the same
slot pool, preparing pair-specific baselines where required. Multi-start products
use cuBLAS batched DGEMM and shared read-only structures.

## Execution and mathematical contract

- GPU: 384-dimensional squared Euclidean distances, undirected all-pairs BFS,
  fixed uniform weights, cost/gradient kernels, batched FP64 matrix products,
  dot products, directions, correspondence updates, per-iteration marginal
  summaries and exact transportation simplex for untraced `N+M<=320` comparisons.
- CPU: CSR/metadata/config validation, exact divisible-mass balanced linear OT
  using transportation simplex with primal/dual certificates, quadratic
  line-search selection and stopping decisions. Tracing, explicit CPU OT and
  larger problems transfer gradients/S through the synchronous diagnostic path;
  the normal GPU-simplex path retains them on the device.
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
  -f benchmark/fgw/Dockerfile \
  .
docker run --rm margin-fgw-cpu-check

mkdir -p /tmp/margin-fgw-evidence
docker run --rm --user "$(id -u):$(id -g)" \
  -v /tmp/margin-fgw-evidence:/out margin-fgw-cpu-check sh -c \
  '/build/fgw_host_test /out/host.json && python benchmark/fgw/validate.py --native-report /out/host.json --native-executable /build/fgw_host_test --output /out/cpu-report.md'

docker build --target cuda -t margin-fgw-cuda-check \
  --build-arg CUDA_ARCHITECTURES=75 \
  -f benchmark/fgw/Dockerfile \
  .
docker run --rm --gpus all -e FGW_DEVICE=0 \
  --user "$(id -u):$(id -g)" -v /tmp/margin-fgw-evidence:/out \
  margin-fgw-cuda-check sh -c \
  'ctest --test-dir /build --output-on-failure && /build/fgw_host_test /out/host.json && /build/fgw_cuda_test /out/cuda.json && python benchmark/fgw/validate.py --native-report /out/host.json --cuda-report /out/cuda.json --native-executable /build/fgw_host_test --cuda-executable /build/fgw_cuda_test --output /out/cuda-report.md'
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
/tmp/margin-fgw-venv/bin/pip install -r benchmark/fgw/requirements.txt
/tmp/margin-fgw-venv/bin/python benchmark/fgw/validate.py \
  --native-report /tmp/margin-fgw-host.json --cuda-report /tmp/margin-fgw-cuda.json \
  --native-executable /tmp/margin-fgw-cuda/fgw_host_test \
  --cuda-executable /tmp/margin-fgw-cuda/fgw_cuda_test \
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
`validate.py --cuda-executable` runs the direct device LP fixture driver,
including changed-cost basis reuse; it does not substitute the tracing CPU
fallback for device coverage. Sixteen further cases per solver check negative/tied costs, unequal sizes, one-node
marginals and random/degenerate costs up to 128-by-160 against HiGHS/POT. Tied
solutions are compared by objective/marginals. The specialised primal network
simplex uses integral transport mass in units of `1/lcm(N,M)`, tree duals, block
pricing, incremental basis hashing and Bland's rule if degenerate bases repeat.
Within each comparison/start it retains a feasible OT basis across FW iterations;
each solve recomputes prices and validates its primal/dual certificate. Bases are
reset between comparisons. It adds no dependency.
Asynchronous session checks exercise two dispatchers and eight slots, profiling
on/off, mixed terminal starts, fresh final gaps, numerical failure recovery,
unequal sizes, slot/dispatcher caps and shutdown draining. Session checks cover resource reuse across input batches, independent starts,
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
products and POT's compiled exact network simplex. `--workers` controls persistent CPU workers; `--gpu-slots` (default 32) and
`--dispatchers` (default 2) independently configure CUDA streams/host threads; `--threads` is the CPU BLAS thread budget, divided across
CPU workers (at least one thread each). The CPU baseline prepares its fixed
structure, weights and constants once. POT OT is single-threaded per worker;
independent comparisons use multiple workers. The native CUDA path uses device transportation simplex for this shape, with
CPU exact-OT fallback for unsupported sizes and diagnostic tracing. This implementation difference remains an
explicit confound in comparisons of complete runtime.

```sh
# Short complete pilot, including same-session warm-up:
/tmp/margin-fgw-venv/bin/python benchmark/fgw/benchmark.py \
  --counts 64 --workers 4 --threads 4 --warmup 64 \
  --cuda-run /tmp/margin-fgw-cuda/fgw_cuda_run \
  --cuda-validation-report /tmp/margin-fgw-report.json --device 0 \
  --output /tmp/margin-fgw-pilot.json

# Six short benchmark tiers. No time limit is imposed by default.
/tmp/margin-fgw-venv/bin/python benchmark/fgw/benchmark.py \
  --counts 100,500,1000,2000,5000,10000 \
  --workers 4 --threads 4 --warmup 64 \
  --cuda-run /tmp/margin-fgw-cuda/fgw_cuda_run \
  --cuda-validation-report /tmp/margin-fgw-report.json --device 0 \
  --output /tmp/margin-fgw-throughput.json

# Equivalent Docker invocation after generating /out/cuda-report.json:
docker run --rm --gpus all --user "$(id -u):$(id -g)" \
  -v /tmp/margin-fgw-evidence:/out margin-fgw-cuda-check \
  python benchmark/fgw/benchmark.py --workers 4 --threads 4 --warmup 64 \
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
is checked for stable results and marginal feasibility. Reports include CPU/GPU OT, dispatcher/slot counts,
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

## Asynchronous submission acceptance

After rebuilding and completing CUDA/CPU/POT correctness validation above:

```sh
CUDA_DEVICE_MAX_CONNECTIONS=32 \
/tmp/margin-fgw-venv/bin/python benchmark/fgw/benchmark.py \
  --counts 100,500,1000,2000 --workers 8 --threads 8 --warmup 64 \
  --gpu-slots 32 --dispatchers 2 --device 0 \
  --cuda-run /tmp/margin-fgw-cuda/fgw_cuda_run \
  --cuda-validation-report /tmp/margin-fgw-report.json \
  --output /tmp/margin-fgw-async.json
```

The async plan's minimum target is 200 complete comparisons/s; higher forecasts
are targets, not measured guarantees. Retain complete numerical agreement and
record an unmet performance target explicitly. Performance reports identify the
synthetic fixture, device and actual configuration. They do not establish
production integration or readiness.

For many streams, configure `CUDA_DEVICE_MAX_CONNECTIONS` before the process
initialises CUDA. NVIDIA documents a default of eight driver work queues and a
range of 1–32; streams sharing a queue can acquire false dependencies. See the
[CUDA environment-variable reference](https://docs.nvidia.com/cuda/cuda-programming-guide/05-appendices/environment-variables.html#cuda-device-max-connections).
The library does not change process-wide environment settings. Record the chosen
value in performance evidence. Use enough warm-up requests to exercise every
slot (64 for the 32-slot command above), and inspect `allocationsAfterWarmup`.
The driver queue count, not just stream count, materially affects performance.


## Default exact simplex optimisation and comparison modes

`Config.incrementalOtTree` and `Config.candidateOtPricing` both default to `true`,
so ordinary `CudaFgwEngine` and `CudaFgwSession` calls use the combined optimisation
without extra configuration. Set both to `false` for the reference implementation,
or disable one to compare the individual optimisations. They affect the untraced
device simplex path only.
Tracing, explicit CPU OT and oversized problems keep their existing exact CPU
behaviour. No precision, objective, marginal or stopping tolerance is changed.

Incremental tree mode builds a rooted basis tree and potentials once per LP
solve, including a changed-cost warm start. It finds the unique pivot cycle from
cached parents/depths. Removing the leaving edge detaches a subtree that excludes
the fixed root; only this component is rerooted at the entering edge, updating
its parents, depths and potentials. This removes the repeated complete potential
traversal and cycle BFS. Subtree traversal remains serial.

Candidate pricing caches one negative reduced-cost arc per thread after a full
scan (at most 256 candidates). Every candidate is repriced against the current
potentials and excluded if basic. When no cached candidate is improving, the
solver scans all arcs and refreshes the cache. Successful termination requires a
complete scan with no improving arc and the original primal/dual certificate.
This is a pricing heuristic, not a restriction of the feasible transport set.
It can change the pivot sequence, including increasing the number of pivots.

`LinearOtResult` retains device work counters in each scalar iteration trace:
full pricing scans, candidate passes, complete tree rebuilds, incremental subtree
updates, visited tree nodes and pivot-cycle edges. CPU counter fields remain
zero. Visited tree nodes include potential traversal, cycle-search BFS in
reference mode and subtree traversal in incremental mode; parent-chain work is
not counted. These are work counts, not hardware profiler measurements.

The native batch driver accepts an optional final mode after slots/dispatchers:
`0` = reference, `1` = incremental tree, `2` = candidate pricing, `3` = combined.
Omitting the mode selects `3`, matching the library defaults.
`benchmark.py --simplex-mode` selects the same modes and also defaults to `3`.
Regression/direct OT fixtures use the test-only `FGW_SIMPLEX_MODE` environment
variable; leaving it unset tests the library defaults. CTest covers default
combined execution and the three alternate modes, alongside the host tests.
Device regressions additionally check ordinary engine and session calls with
untouched solver defaults: their numerical results, LP certificates and work
counters must demonstrate both optimisations.

The following driver performs the prescribed numerical checks independently for
all four modes before measuring performance. It uses the existing independent
CPU/POT complete-FGW baseline once per tier, rotates CUDA mode order and records
all raw reports/counters. Counts remain configurable; defaults are the six
specified tiers, totalling 18,600 complete comparisons per mode. It imposes no
time limit and rejects unverified device checks or failed numerical comparisons.

```sh
CUDA_DEVICE_MAX_CONNECTIONS=32 /tmp/margin-fgw-venv/bin/python \
  benchmark/fgw/simplex_experiment.py \
  --build-dir /tmp/margin-fgw-cuda --output-dir /tmp/margin-fgw-simplex \
  --workers 8 --threads 8 --gpu-slots 32 --dispatchers 2 --warmup 64
```

Choose device/worker/slot settings appropriate to the environment. The driver
records the connection setting rather than changing it. Its optional
`--original-run` accepts a preserved pre-experiment batch executable as an
additional paired control; the reference mode in the rebuilt binary includes the
new common scalar counters and shared workspace. This makes common overhead
visible instead of attributing every difference to the experimental strategies.
No performance improvement is implied by passing numerical tests.
