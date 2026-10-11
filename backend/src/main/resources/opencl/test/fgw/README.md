# OpenCL and serial Java balanced FGW

These are separate reusable engines, ported from the three production CUDA FGW
files. They retain FP64, balanced uniform marginals, squared material distances,
undirected shortest paths, the unnormalised FGW objective, exact transportation
simplex, exact quadratic line search, fresh terminal gaps, stopping statuses and
explicit feasible multi-start semantics. Quantity and amount decimal text and
all occurrence/material/production/issue/source identifiers are preserved.
No embedding generation, FIFO restoration or backend accelerator binding is added.

## Execution and API

`fgw_engine.hpp` exposes `OpenClFgwEngine` and `OpenClFgwSession`, graph/config/result
contracts and an exact device-OT validation hook. Set `Config.kernelPath` to the
installed `fgw.cl`; `platform` and `device` select indices within the available
OpenCL inventory. CMake supplies the kernel path to the test executables.

The session prepares one baseline once. Each worker owns a persistent in-order
command queue, kernels and capacity-reused buffers. The effective number of
workers/queues is `min(maxConcurrentComparisons, gpuSlots, dispatcherThreads)`;
untouched defaults produce one worker/queue. The default pending capacity is
twice that count. This differs from CUDA's independently scheduled stream slots:
OpenCL currently executes a comparison synchronously on each worker, and evaluates
its starts serially. It does not claim CUDA-style asynchronous slot dispatch or
batched BLAS. Arbitrary-pair `compareBatch` retains a session across consecutive
identical baselines and returns results in input order. For concurrent repeated
work, submit bounded groups through a fixed-baseline session and consume futures.
Shutdown drains accepted tasks; target exceptions arrive through futures.
Numerical failures remain result statuses and subsequent requests are isolated.

OpenCL computes distances, the two structural matrix products, gradients, dot
products, directions and updates on the selected device. Host code selects line
search steps and stopping statuses from FP64 diagnostics. The current diagnostic
checks download the correspondence and device OT result every iteration. Matrix
products use native OpenCL kernels; no BLAS dependency is introduced. Buffers grow
only when capacities grow, and target results/distances are never memoised.

The engine checks FP64, work-group/item limits and the simplex kernel's actual
local-memory requirements. Exact device simplex requires `N+M <= 320`, 256
work-items and sufficient local memory. `Result.otExecution` reports either
`OPENCL_DEVICE_EXACT_SIMPLEX` or `EXACT_CPU_OT_FALLBACK` with the precise reason:
full tracing, explicit `CPU_REFERENCE`, an oversized transport, or insufficient
simplex capabilities. CPU OT remains exact; all other dense calculations still
require the OpenCL device. A missing platform, kernel or FP64 capability throws
an execution error; no FP32 or silently approximate fallback exists.

Both incremental tree updates and candidate pricing default to enabled. Candidate
arcs are repriced, and optimality requires a complete scan without an improving
arc plus original-marginal/primal/dual checks. Integral mass is measured in units
of `1/lcm(N,M)` and feasible bases persist only within a comparison/start. OpenCL
retains CUDA's device pivot safety limit and lowest-index leaving tie choice;
portable local-memory reductions replace CUDA warp intrinsics. Scalar traces
retain pricing/tree/cycle counters even without full matrices. CPU-OT fallback
leaves those device counters zero. `profileExecution` enables OpenCL event
profiling: device/OT durations are sums of kernel event durations, whereas host
synchronisation intervals overlap those durations and must not be added to wall
time. These timings differ from CUDA's stream event intervals.

`algorithm/FGW/FgwEngine.java` supplies `compare`, ordered `compareBatch`, and a
synchronous `Session` that reuses prepared baseline distances/constants. The
session is confined to its creating thread. All numerical calculations and graph
preparation run serially in that calling thread, using Java `double`, primitive
arrays and exact integral transportation simplex. There are no worker threads,
parallel streams, GPU operations, Python calls or native numerical libraries in
the engine. Source mappings and caller arrays are copied when preparing the
baseline. Java implements the same incremental tree and candidate pricing modes,
and adds repeated-degenerate-basis detection/Bland's rule from the CUDA host
reference. Its work counters describe serial operations rather than GPU work.

## Reproducible correctness checks

Requirements: Java 21, C++17, CMake, OpenCL headers/ICD and a compatible FP64
device; CUDA toolkit/device for comparison with CUDA; Python packages in the
existing CUDA test `requirements.txt` for independent NumPy/SciPy/HiGHS/POT
validation. No additional production dependency is required. The host toolchains
were intentionally available during this task; Docker remains the project
alternative for Java. Do not assume a Java/Node container supplies an OpenCL ICD
or device. Build native tests in a compatible device-enabled environment.

From the repository root:

```sh
cmake -S backend/src/main/resources/opencl -B backend/src/main/resources/opencl/build
cmake --build backend/src/main/resources/opencl/build -j 4
ctest --test-dir backend/src/main/resources/opencl/build --output-on-failure

cmake -S backend/src/main/resources/cuda -B /tmp/margin-fgw-cuda -DFGW_ONLY=ON
cmake --build /tmp/margin-fgw-cuda -j 4
ctest --test-dir /tmp/margin-fgw-cuda --output-on-failure

(cd backend && ./mvnw --batch-mode --no-transfer-progress test)

python3 -m venv /tmp/margin-fgw-reference
/tmp/margin-fgw-reference/bin/pip install -r benchmark/fgw/requirements.txt
/tmp/margin-fgw-reference/bin/python benchmark/fgw/validate_ports.py \
  --opencl-build backend/src/main/resources/opencl/build \
  --cuda-build /tmp/margin-fgw-cuda --output-dir /tmp/margin-fgw-ports
```

Configure `CMAKE_CUDA_ARCHITECTURES` for the intended device/toolkit when necessary.
`FGW_DEVICE` selects native numerical test devices; `FGW_PLATFORM` selects the
OpenCL test platform. The numerical harness runs modes 0 (reference), 1
(incremental), 2 (candidate) and 3 (combined), rejects failed/skipped executions,
and produces full raw JSON/Markdown reports plus a source-hashed summary.

Checks include every prescribed fixed-fixture matrix and scalar at `1e-8`, tensor
objectives/gradients, feasible directional derivatives, line-search monotonicity,
POT's explicit FP64 `square_loss`, `symmetric=True`, `armijo=False` configuration,
HiGHS/POT cold and changed-cost warm exact-OT problems through 128-by-160,
multiple starts, ordering, stopping, source text, numerical failure recovery,
unequal targets, device fallback, queue bounds, shutdown draining and capacity
reuse. Device tests fail rather than skip when the device is unavailable.

OpenCL device results are tested independently from full tracing's CPU-OT path.
The test hook never substitutes CPU OT. Alternate exact pivot sequences may
select different tied optima; compare objectives and marginals as specified.
The CUDA memory/race tools apply to CUDA and do not certify OpenCL accesses.
A compatible OpenCL memory/race instrumentation tool was unavailable in the
executed environment; OpenCL memory/race instrumentation remains unverified.

## Matched complete-FGW benchmark

Only benchmark after the numerical checks and Java 21 tests pass:

```sh
CUDA_DEVICE_MAX_CONNECTIONS=32 /tmp/margin-fgw-reference/bin/python \
  backend/src/main/resources/opencl/test/fgw/benchmark_ports.py \
  --validation-dir /tmp/margin-fgw-ports \
  --opencl-build backend/src/main/resources/opencl/build \
  --cuda-build /tmp/margin-fgw-cuda --workers 8 --opencl-workers 2 \
  --cuda-slots 32 --cuda-dispatchers 2 --warmup 64 \
  --output-dir /tmp/margin-fgw-port-benchmark
```

The benchmark uses the existing CUDA benchmark's identical 128-node baseline,
160-node targets, 384-dimensional embeddings, seeds, 16 nearby targets and six
complete tiers: 100, 500, 1,000, 2,000, 5,000 and 10,000 comparisons per execution
path. Counts can be overridden through `--counts`. Every request prepares its
target and performs complete FGW, with no result cache. Baseline preparation is
reused. Every target is compared against the independent CPU result for all
three costs, gap, applied-update count and status; repeated results are checked
for marginal feasibility/stability. Native allocation counters must remain
unchanged after capacity warm-up.

Reports separate `JAVA_SERIAL` (one calling thread, bounded JVM heap),
`CPU_MULTICORE` (eight workers by default, one BLAS thread per worker), `OPENCL`
(two workers/queues by default) and `CUDA` (32 slots/two dispatchers by default).
They include fixture/source identities, numerical settings, actual completed
counts, preparation/warm-up/steady phases and complete runtime. Input parsing and
process launch are excluded. Different matrix kernels, exact-simplex pivot
sequences and concurrency settings are explicit comparison confounds. These
synthetic throughput measurements do not establish production readiness,
backend integration, cross-device portability or million-request endurance.

The task record in `docs/tasks/` contains the actual executed validation and
benchmark results, hardware and remaining limitations.

## Shared benchmark location

The common [FGW benchmark package](../../../../../../../benchmark/fgw/README.md)
now supplies `benchmark/fgw/benchmark.py`. It retains the CUDA benchmark options
and accepts optional `--opencl-run`, `--opencl-workers`, `--java-classes` and
`--validation-dir` arguments for separate OpenCL and serial Java measurements.
The older `benchmark_ports.py` command remains compatible and imports the same
shared CPU reference; `validate_ports.py` forwards to the shared harness.
