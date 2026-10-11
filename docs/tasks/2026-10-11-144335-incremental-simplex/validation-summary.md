# Exact GPU simplex experiment

Result: PASS for numerical validation and completion of all prescribed benchmark tiers.

Both experimental controls remain opt-in and default to false. Changes are uncommitted.

## Complete FGW throughput

NVIDIA RTX 5070 Ti, FP64, a 128-node baseline and 160-node targets, 384-dimensional embeddings, the same seeded 16-target pool. CUDA uses 32 slots, two dispatchers, `CUDA_DEVICE_MAX_CONNECTIONS=32`, and 64 same-session warm-up comparisons plus 16 reference comparisons per tier. CPU uses eight workers and an eight-thread total BLAS budget. Each target request executes complete FGW without cached answers. Mode order is rotated between tiers.

| Complete comparisons | CPU FGW/s | Original FGW/s | Rebuilt reference FGW/s | Tree FGW/s | Pricing FGW/s | Combined FGW/s | Combined/original |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 273.036 | 128.422 | 125.293 | 254.222 | 104.596 | 404.056 | 3.146 |
| 500 | 290.748 | 153.346 | 150.124 | 302.207 | 173.379 | 484.018 | 3.156 |
| 1,000 | 223.188 | 149.297 | 150.279 | 302.583 | 174.884 | 490.563 | 3.286 |
| 2,000 | 291.702 | 155.888 | 152.375 | 307.243 | 176.561 | 495.312 | 3.177 |
| 5,000 | 289.888 | 156.720 | 153.012 | 309.120 | 177.321 | 498.299 | 3.180 |
| 10,000 | 291.179 | 157.188 | 153.461 | 309.690 | 177.761 | 499.392 | 3.177 |

The largest tier completed 10,000 comparisons for every path. Combined throughput was 499.392 FGW/s versus 157.188 for the preserved original binary: 3.177 times the original throughput. The CPU baseline measured 291.179 FGW/s. All tiers passed independent CPU cost, gap, status and iteration comparisons at `1e-8`; every completed native result also checks original marginals and finite/nonnegative weights.

Each of the four rebuilt modes, the preserved original control and the CPU baseline completed 18,600 steady comparisons. This is 93,000 GPU steady comparisons across the five CUDA paths and 18,600 CPU comparisons, excluding warm-up and reference solves. These short synthetic runs do not establish production readiness or endurance.

## Solver work at 10,000 comparisons

The mean number of exact OT solves was 8.6875 per FGW comparison, including fresh terminal gap checks. Work counts below are per OT solve, averaged over the complete tier. The original binary predates the new counters.

| Mode | Pivots | Full pricing scans | Candidate passes | Complete tree rebuilds | Incremental subtree updates | Visited tree nodes | Cycle edges |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Reference | 157.863 | 158.863 | 0.000 | 158.863 | 0.000 | 74527.748 | 5769.259 |
| Tree | 157.863 | 158.863 | 0.000 | 1.000 | 157.863 | 11388.892 | 5769.259 |
| Pricing | 216.820 | 16.036 | 216.820 | 217.820 | 0.000 | 83287.446 | 3355.151 |
| Combined | 216.820 | 16.036 | 216.820 | 1.000 | 216.820 | 14011.223 | 3355.151 |

Compared with the rebuilt reference, combined mode reduced full scans by 89.91% and counted tree visits by 81.20%, while pivots increased by 37.35%. Candidate pricing alone gives a modest sustained improvement and regressed in the 100-comparison tier. Its reduced scans do not guarantee lower total work; incremental tree maintenance makes the additional pivots much cheaper.

Visited nodes count potential BFS, reference cycle BFS and incremental subtree BFS. Cached-parent chain traversal is not counted. Counts are not hardware profiler measurements.

Combined CUDA OT event intervals averaged 60.414 ms/comparison within 63.168 ms of complete device intervals. These intervals can include contention and queueing and overlap between streams; they are not isolated kernel latency or summed wall time. No hardware kernel profile was collected.

## Validation

- Baseline before implementation: prescribed numerical report, 824 PASS checks.
- Four modes after implementation: 824 PASS checks each, maximum absolute error `4.444455914409673e-11`, FP64 tolerance `1e-8` unchanged. Includes intermediate fixed matrices/gradients, line search, fresh gap, original marginals, independent CPU/HiGHS/POT and 16 direct device LP fixtures per mode with changed-cost warm starts.
- CTest: 5/5 passed on the compatible device, with no skipped GPU tests. Includes session/resource/failure/ordering tests for experimental modes.
- Native SM 12.0 CTest and independent mode-3 CPU/POT report: see native validation logs.
- Strict memcheck with all API reporting and full leak checks: full combined-mode session regression and a tied 128-by-160 cold/warm OT fixture, zero errors and zero leaked bytes.
- Racecheck: tree, pricing and combined modes on the 128-by-160 continuous cold/warm fixture, zero hazards/errors/warnings. This checks shared-memory hazards, not every possible host/global-memory race.
- Benchmark evidence gate: mismatched mode rejected before GPU execution; matching mode-3 standalone pilot passed. Measured source/binary digests and all native mode labels checked. Python syntax and `git diff --check` passed.

Initial SM 7.5/PTX memcheck returned exit 99 for the CUDA loader warning `Selective device code recompilation in progress`; it reported zero leaked bytes. The failed log is preserved as `memcheck-session-before-native-sm.log`. Rebuilding the unchanged production source for the actual SM 12.0 device removed the warning and strict checks passed without suppressions or reduced API reporting. Throughput measurements used the original SM 7.5/PTX build throughout; native sanitizer timings are not benchmark results.

## Implementation and use

Incremental tree mode maintains parents/depths and dual potentials within each LP solve. It finds the unique cycle through cached parents, then reroots/reprices only the detached subtree when the basis changes. Each changed-cost warm solve still performs an initial complete potential traversal.

Candidate pricing retains at most 256 per-thread candidate arcs. It recomputes their reduced costs at each pivot, refreshes with a full scan when none improves, and requires a final complete optimality scan plus the existing primal/dual certificate. It preserves the full balanced transport feasible set. Neither approach changes FGW precision, tolerances, objective, line search or stopping rules.

```cpp
config.incrementalOtTree = true;
config.candidateOtPricing = true;
config.maxConcurrentComparisons = 32;
config.gpuSlots = 32;
config.dispatcherThreads = 2;
```

Use the independent `simplex_experiment.py` driver documented in the native FGW README. `benchmark.py --simplex-mode 3` requires the mode-3 numerical JSON report. Tracing, explicit CPU OT and `N+M>320` retain the existing CPU diagnostic/fallback path and ignore these device controls.

## Reproduction

Run from the repository root with the intentionally available host toolchain and
Python dependencies documented in the native FGW README. Select a CUDA
architecture supported by the toolkit and device; architecture 75 was used for
these throughput measurements, and native architecture 120 for sanitizer checks
on this actual device. Architecture 120 is not a portable default for other GPUs.

```sh
cmake -S backend/src/main/resources/cuda -B /tmp/margin-fgw-cuda \
  -DFGW_ONLY=ON -DCMAKE_BUILD_TYPE=Release -DCMAKE_CUDA_ARCHITECTURES=75
cmake --build /tmp/margin-fgw-cuda -j 4
CUDA_DEVICE_MAX_CONNECTIONS=32 /tmp/margin-fgw-venv/bin/python \
  backend/src/main/resources/cuda/test/fgw/simplex_experiment.py \
  --build-dir /tmp/margin-fgw-cuda --output-dir /tmp/margin-fgw-simplex-repeat \
  --counts 100,500,1000,2000,5000,10000 \
  --workers 8 --threads 8 --gpu-slots 32 --dispatchers 2 --warmup 64
```

The actual paired run also supplied
`--original-run /tmp/fgw-simplex-experiment-baseline/fgw_cuda_run`, preserved before
editing on this host. That optional control requires a separately preserved
pre-experiment binary; without it the four rebuilt modes remain reproducible.

```sh
cmake -S backend/src/main/resources/cuda -B /tmp/margin-fgw-native-sanitize \
  -DFGW_ONLY=ON -DCMAKE_BUILD_TYPE=Release -DCMAKE_CUDA_ARCHITECTURES=120
cmake --build /tmp/margin-fgw-native-sanitize -j 4
ctest --test-dir /tmp/margin-fgw-native-sanitize -R '^fgw_' --output-on-failure
FGW_SIMPLEX_MODE=3 compute-sanitizer --tool memcheck \
  --report-api-errors all --leak-check full --error-exitcode 99 \
  /tmp/margin-fgw-native-sanitize/fgw_cuda_test /tmp/margin-fgw-memcheck.json
```

For the large cold/warm checks, the evidence directory contains the exact
`sanitizer-cost-continuous.txt` and `sanitizer-cost-tied.txt` LP inputs. Run
`fgw_cuda_test --ot INPUT` under `compute-sanitizer --tool racecheck
--error-exitcode 99` with `FGW_SIMPLEX_MODE=1`, `2` and `3` for the continuous
input. Run the tied input in mode 3 under the same strict memcheck options above.
The standalone benchmark pilot used `benchmark.py --simplex-mode 3 --counts 64`
with the mode-3 numerical JSON evidence, 32 slots, two dispatchers and eight CPU
workers/threads. All raw outputs are retained in this directory.

## Evidence and limitations

- `experiment.json` contains all tiers, measurements, configuration, work counters, numerical provenance and hashes. Raw native JSON/logs are retained per tier/mode.
- `numerical-mode-{0,1,2,3}.md/.json` contain the step-by-step PASS/FAIL reports; sanitizer logs and native validation are separate.
- The standalone benchmark mode/provenance gate was strengthened after throughput measurement. The measured driver snapshots are retained and match the captured hashes. Production sources and measured binaries were unchanged.
- Single measurement per mode/tier with rotated ordering; no confidence interval. CPU timing varied between tiers. CPU and GPU use different exact OT implementations.
- Only this available GPU/toolchain and the documented synthetic inputs were tested. Incremental subtree traversal remains serial. Additional workloads can have different pivot counts and speedups.
- No Java/backend workflow integration, deployment, FIFO, OpenCL or FP32 changes; no new production dependencies.

Report finalised: 2026-10-11 15:11:55 Pacific/Auckland (UTC+13:00).
