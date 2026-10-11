# OpenCL Tests

## LOF engine test

The LOF test first verifies the original seven A–G scores. It then makes 13,999 nearby, distinct normal orders around A–F and adds one G. All 14,000 four-feature orders share one global neighbor search with `k = 2`. It warms up once, times one complete call, checks every score is finite and G has the highest LOF, then prints batch time and points per second. Run it from the repository root:

```sh
cmake -S backend/src/main/resources/opencl -B /tmp/lof-opencl-build -DBUILD_TESTING=OFF
cmake --build /tmp/lof-opencl-build --target lof_engine_test -j
/tmp/lof-opencl-build/lof_engine_test
```

The executable reports an unavailable device and exits without a timing result when no OpenCL device is present.

## BomUpwardEngine tests

The tests run on a real OpenCL device. They require OpenCL headers and runtime libraries, a working ICD and device, CMake, and a C++17 compiler. Initial configuration downloads GoogleTest unless local sources are supplied through `FETCHCONTENT_SOURCE_DIR_GOOGLETEST`. Tests fail when no device is available; they do not skip or report success.

Run from the repository root:

```sh
cmake -S backend/src/main/resources/opencl -B backend/src/main/resources/opencl/build
cmake --build backend/src/main/resources/opencl/build -j 4
ctest --test-dir backend/src/main/resources/opencl/build --output-on-failure
```

CMake supplies the kernel path, so tests do not depend on the working directory used to launch them. Each CTest test has a 60-second timeout.

## Coverage

The suite retains the original propagation test and adds 21 tests covering absolute-value and inclusive threshold comparisons, non-comparable nodes, pruning, shared materials, disconnected components, diamond merges, arrivals across frontier rounds, the initial frontier, empty graphs, dimension validation, repeated calls, 64-bit integer limits, and long chains.

The concurrency test connects 1024 leaves to 64 parents and runs 20 times with a shuffled frontier, checking for missing and duplicate results. The randomized test uses seed `20261001` to generate 100 DAGs and checks three thresholds per graph, for 300 GPU/CPU comparisons. The independent CPU reference scans child states using the original edge list rather than reusing CSR traversal or atomic counters. Failures report the seed, sample, threshold, and graph data. Result comparisons sort vectors while preserving duplicates and make no assumptions about GPU output order.

Current semantics: initial terminal nodes are not evaluated against the threshold. A parent must receive notifications from all its direct children. Selected children stop propagating, which can prevent shared ancestors from being processed. The tests do not accumulate node values.

## Detecting Ignored OpenCL Errors

The engine currently leaves most OpenCL return codes unchecked. An additional build in a separate directory can enable OpenCL C++ exceptions consistently across the entire build, preventing device API failures from being mistaken for valid empty results:

```sh
cmake -S backend/src/main/resources/opencl -B /tmp/bom-upward-strict-tests \
  -DCMAKE_CXX_FLAGS=-DCL_HPP_ENABLE_EXCEPTIONS
cmake --build /tmp/bom-upward-strict-tests -j 4
ctest --test-dir /tmp/bom-upward-strict-tests --output-on-failure
```

Validation on an NVIDIA GeForce RTX 5070 Ti on 2026-10-01:

- Default build: 22/22 passed.
- Exceptions enabled: 22/22 passed.

The caller rejects nonempty graphs without edges. These graphs are outside the method's input contract and are not tested.

Invalid node IDs, cycles, and duplicate terminal nodes do not yet have reliable input validation, so the suite does not submit them to the kernel. Randomized and concurrency tests increase coverage but cannot prove the absence of races under every possible scheduling order.

## Balanced FGW engine

The same CMake/CTest commands now include four actual-device FGW regressions
(default combined simplex and modes 0, 1 and 2). See
[the FGW port README](fgw/README.md) for the OpenCL and serial Java APIs, exact
CPU-OT fallback, independent CPU/POT/CUDA numerical harness and matched-input
benchmark. Device absence is a failure, not a passing skipped test.
