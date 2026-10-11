# Optional Java accelerator bindings

The synchronous `/api/v1/analysis/run` and `/run/stream` workflow can invoke the
existing CUDA/OpenCL frontier and FGW engines through JNI. Warehouse access, CSR
construction, production trees, eligibility, contextual embedding preparation and
pair scheduling remain in Java. Existing supplied-vector comparison/reconciliation
endpoints retain their CPU execution contracts. No mathematical algorithm is
reimplemented in the bridge.

## Build and installation

Requirements: Java 21 JDK with JNI headers, CMake 3.24+, C++17 compiler, and the
selected runtime's development tools. OpenCL requires the ICD loader and C/C++
headers. CUDA requires its toolkit and cuBLAS. Execution additionally requires a
compatible GPU, driver and, for OpenCL FGW, FP64 support. A container needs access
to its device and a compatible vendor ICD; the generic OpenCL loader alone does
not supply a GPU implementation.

From the repository root, build only the desired families:

```sh
# OpenCL bridge; CUDA remains optional.
cmake -S backend/src/main/resources/native -B /tmp/margin-native \
  -DMARGIN_OPENCL=ON -DMARGIN_CUDA=OFF -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/margin-native --target margintrace_opencl -j 4
cmake --install /tmp/margin-native --prefix /tmp/margin-native-installed

# CUDA bridge. Select an architecture compatible with your toolkit/device;
# use CUDA_ARCHITECTURES as an environment variable for this example.
cmake -S backend/src/main/resources/native -B /tmp/margin-native-cuda \
  -DMARGIN_OPENCL=OFF -DMARGIN_CUDA=ON -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_CUDA_ARCHITECTURES="$CUDA_ARCHITECTURES"
cmake --build /tmp/margin-native-cuda --target margintrace_cuda -j 4
cmake --install /tmp/margin-native-cuda --prefix /tmp/margin-native-installed
```

Set `JAVA_HOME` when multiple JDKs are installed. Both families can also be enabled
in one build. Install native libraries separately from the application JAR:

- `MARGIN_NATIVE_LIBRARY_DIR`: directory containing `libmargintrace_cuda.so` and/or
  `libmargintrace_opencl.so` on Linux. If unset, standard `System.loadLibrary`
  lookup applies. CPU execution needs neither library.
- `MARGIN_OPENCL_KERNEL_DIR`: directory containing `bom_upward.cl` and `fgw.cl`;
  defaults to the native library directory, or the working directory when no
  directory is configured. CMake installation includes both kernels.
- Runtime dependencies such as cuBLAS must be discoverable by the system loader.
  Configure its search path for your installation rather than copying a
  maintainer-specific toolkit directory.

Configure these variables before starting the backend. Missing libraries are
reported without preventing CPU execution; restart after installing a library
that failed to load. Java system properties `margintrace.native.dir` and
`margintrace.opencl.kernelDir` provide equivalent overrides for isolated tests.

`backend/Dockerfile` retains CPU-only packaging. Optional images are available:

```sh
docker build --target opencl -t margin-backend-opencl \
  -f backend/Dockerfile.acceleration backend
docker build --target cuda -t margin-backend-cuda \
  -f backend/Dockerfile.acceleration \
  --build-arg CUDA_ARCHITECTURES="$CUDA_ARCHITECTURES" backend
```

The CUDA build/runtime image arguments can be overridden together for a different
compatible toolkit. Image packaging skips Java tests and does not establish
runtime/device correctness. Configure device access using the runtime supported
by your vendor. NVIDIA containers require NVIDIA Container Toolkit and GPU
access (for example `--gpus all`); OpenCL also requires a working vendor ICD inside
the container. Do not start a backend against an unverified database merely to
check GPU access.

## Device selection and reports

`useGpu=false` selects Java CPU. With GPU enabled, the supplied `isNvidia` selects
CUDA (`true`) or OpenCL (`false`); it is never inferred or overridden by the bridge.
CUDA device IDs are indices such as `0`. OpenCL IDs are `platform:device`, such as
`0:0`, using the native engine's all-device ordering within each platform. Only
GPU devices are exposed. A legacy OpenCL numeric ID means device N on platform 0.
A null ID selects the first suitable device within the requested family, per stage.

`GET /api/v1/analysis/capabilities` preserves the `cpu`, `cuda` and `opencl` reports
and adds `devices` and per-family `stages.frontier`/`stages.fgw` reports. Inventory
queries load libraries and enumerate devices, check FP64 and kernel file presence,
and never allocate FGW sessions. They do not certify successful kernel compilation
or numerical execution. Lazy initialisation failures remain explicit at execution.
The Settings page uses this backend inventory; browser GPU access is irrelevant.

Frontier uses exact signed-long micro-units and preserves unsigned absolute-value
threshold comparison, including `Long.MIN_VALUE`. Boundary validation respects
Java's active-child counts: full reverse edges are retained while only children
reachable from selected leaf seeds may be counted. Selected children still stop
propagation; initial seeds are not tested against the threshold.

FGW retains FP64, uniform marginals, exact balanced OT, initial matrices, source
mappings, all result statuses and trace diagnostics. Decimal evidence remains
text. Each comparator owns one session with one bounded native worker/slot,
matching the current serial all-pairs orchestration. Repeated equivalent baseline
inputs/configuration reuse that session; changed data/configuration recreate it.
Submitted arrays and retained baseline snapshots are owned copies. Native workers
never retain a `JNIEnv` or local Java reference. JNI result construction occurs
only on the calling Java thread after the native future completes.

Comparators close when switching baseline and on normal, exceptional, cancelled
or disconnected workflow exits. Interruption is checked before submission;
already accepted synchronous device work completes/drains before resource release.
This is not immediate mid-kernel cancellation or parallel pair scheduling.

`result.execution` identifies `CUDA_DEVICE_EXACT_SIMPLEX`,
`OPENCL_DEVICE_EXACT_SIMPLEX`, or `EXACT_CPU_OT_FALLBACK` with its reason. Full
tracing and `N+M > 320` use exact CPU OT within a GPU FGW engine; OpenCL can also
report insufficient device simplex resources. This remains GPU FGW execution.
Native execution timings overlap and must not be summed into wall time.

Library/runtime/device failures permit complete Java fallback only when
`allowCpuFallback=true`. Missing input, invalid matrices and numerical-failure
results are never silently converted into successful CPU results. Reports retain
requested family, actual backend/device, fallback reason and numerical placement.
A stage that uses GPU then falls back reports both placements; each pair retains
its own result execution. CUDA never silently switches to OpenCL or vice versa.

## Validation

Build and run the unchanged native suites as prescribed by the OpenCL and CUDA
FGW READMEs, then run Java 21 tests. For actual JNI acceptance of **both** families:

```sh
cd backend
MARGIN_NATIVE_LIBRARY_DIR=/tmp/margin-native-installed MARGIN_TEST_GPU=true \
  ./mvnw --batch-mode --no-transfer-progress test
```

The opt-in tests fail on missing devices rather than declaring a skipped GPU run
a pass. They check frontier/CPU DAG parity, integer limits, filtered-child
workflow parity, all four FGW modes, fixed intermediate matrices, device simplex,
internal CPU OT, multi-starts, changed baselines/configuration, failure recovery,
closure, interruption, explicit fallback, inventory/API contracts and original
record/evidence preservation. The workflow fixture uses deterministic embedding
vectors; it does not establish real-model semantic quality.

`NativeFgwFixtures` in test sources produces the existing fixed numerical report
format through JNI for independent `benchmark/fgw/validate.py` CPU/POT checking.
From the repository root:

```sh
MARGIN_NATIVE_LIBRARY_DIR=/tmp/margin-native-installed \
  java -Xcheck:jni -cp backend/target/classes:backend/target/test-classes \
  dev.margintrace.margin_attribution_backend.algorithm.FGW.NativeFgwFixtures \
  CUDA /tmp/cuda-jni.json
python benchmark/fgw/validate.py --native-report /tmp/cuda-jni.json \
  --output /tmp/cuda-jni-validation.md
```

Repeat with `OPENCL`. Use the requirements in `benchmark/fgw/requirements.txt` for
the independent oracle. Device OT coverage additionally comes from the JNI
non-tracing tests and prescribed direct native device fixtures; a traced CPU-OT
report alone does not establish it. Run `scripts/test_acceleration_settings.mjs`
with Vite, Node and Playwright available for isolated UI behaviour, followed by
the prescribed frontend Docker build. The task record contains actual results
and tested-device/platform scope; successful compilation does not establish
cross-vendor portability or production readiness.
