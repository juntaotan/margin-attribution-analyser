# FGW port numerical validation

Status: PASS. FP64 acceptance tolerance: `1e-8`.

The final OpenCL device suite passed 26/26 without skips, the unchanged CUDA suite passed 5/5 without skips, and Java 21 Maven tests passed 113/113 without skips (including three FGW tests).

The four-mode numerical harness ran eight independent validation reports, each containing 824 checks. The shared CUDA/reference checks are repeated in those reports; this is not a claim of 6,592 distinct fixtures. Every report passed. Maximum absolute error was `4.444455914409673e-11`.

| Mode | Java serial | OpenCL | Maximum absolute error |
| --- | --- | --- | --- |
| 0 | 824 PASS | 824 PASS | 4.444455914409673e-11 |
| 1 | 824 PASS | 824 PASS | 4.444455914409673e-11 |
| 2 | 824 PASS | 824 PASS | 4.444455914409673e-11 |
| 3 | 824 PASS | 824 PASS | 4.444455914409673e-11 |

Mode 0 disables both optimisations; mode 1 enables incremental trees; mode 2 enables candidate pricing; mode 3 enables both and is the default.

## Fixed intermediate results

The following actual values are from the serial Java and OpenCL final reports. Their full matrices, per-iteration costs, source mappings and diagnostics are retained in the raw evidence. Values below are displayed as JSON with FP64 round-trip precision. The prescribed decimal expectations are unchanged.

| Quantity | Prescribed expectation | Actual Java | Actual OpenCL |
| --- | --- | --- | --- |
| M_attr | `[0,1,4,4,1,0]` | `[0.0, 1.0, 4.0, 4.0, 1.0, 0.0]` | `[0, 1, 4, 4, 1, 0]` |
| D1 | `[0,1,1,0]` | `[0.0, 1.0, 1.0, 0.0]` | `[0, 1, 1, 0]` |
| D2 | `[0,1,2,1,0,1,2,1,0]` | `[0.0, 1.0, 2.0, 1.0, 0.0, 1.0, 2.0, 1.0, 0.0]` | `[0, 1, 2, 1, 0, 1, 2, 1, 0]` |
| p | `[0.5,0.5]` | `[0.5, 0.5]` | `[0.5, 0.5]` |
| q | `[1/3,1/3,1/3]` | `[0.3333333333333333, 0.3333333333333333, 0.3333333333333333]` | `[0.3333333333333333, 0.3333333333333333, 0.3333333333333333]` |
| P0 | `[1/6,1/6,1/6,1/6,1/6,1/6]` | `[0.16666666666666666, 0.16666666666666666, 0.16666666666666666, 0.16666666666666666, 0.16666666666666666, 0.16666666666666666]` | `[0.16666666666666666, 0.16666666666666666, 0.16666666666666666, 0.16666666666666666, 0.16666666666666666, 0.16666666666666666]` |
| Initial costs (material/structure/FGW) | `[1.6666666667,0.9444444444,1.0888888889]` | `[1.6666666666666667, 0.9444444444444442, 1.0888888888888886]` | `[1.6666666666666665, 0.9444444444444443, 1.0888888888888888]` |
| G0 | `[1.8666666667,1,2.6666666667,2.6666666667,1,1.8666666667]` | `[1.8666666666666665, 0.9999999999999998, 2.666666666666666, 2.666666666666666, 0.9999999999999998, 1.8666666666666665]` | `[1.8666666666666665, 0.9999999999999998, 2.666666666666666, 2.666666666666666, 0.9999999999999998, 1.8666666666666665]` |
| S0 | `[1/3,1/6,0,0,1/6,1/3]` | `[0.3333333333333333, 0.16666666666666666, 0.0, 0.0, 0.16666666666666666, 0.3333333333333333]` | `[0.3333333333333333, 0.16666666666666666, 0, 0, 0.16666666666666666, 0.3333333333333333]` |
| Gap0 | `0.2666666667` | `0.26666666666666644` | `0.2666666666666665` |
| Direction | `[1/6,0,-1/6,-1/6,0,1/6]` | `[0.16666666666666666, 0.0, -0.16666666666666666, -0.16666666666666666, 0.0, 0.16666666666666666]` | `[0.16666666666666666, 0, -0.16666666666666666, -0.16666666666666666, 0, 0.16666666666666666]` |
| Gamma | `1.0` | `1.0` | `1` |
| P1 | `[1/3,1/6,0,0,1/6,1/3]` | `[0.3333333333333333, 0.16666666666666666, 0.0, 0.0, 0.16666666666666666, 0.3333333333333333]` | `[0.3333333333333333, 0.16666666666666666, 0, 0, 0.16666666666666666, 0.3333333333333333]` |
| Updated costs (material/structure/FGW) | `[0.3333333333,0.5,0.4666666667]` | `[0.3333333333333333, 0.4999999999999999, 0.46666666666666656]` | `[0.3333333333333333, 0.49999999999999994, 0.4666666666666666]` |
| G1 | `[0.8,1,3.7333333333,3.7333333333,1,0.8]` | `[0.8, 0.9999999999999998, 3.7333333333333334, 3.7333333333333334, 0.9999999999999998, 0.8]` | `[0.8, 0.9999999999999998, 3.7333333333333334, 3.7333333333333334, 0.9999999999999998, 0.8]` |
| S1 | `[1/3,1/6,0,0,1/6,1/3]` | `[0.3333333333333333, 0.16666666666666666, 0.0, 0.0, 0.16666666666666666, 0.3333333333333333]` | `[0.3333333333333333, 0.16666666666666666, 0, 0, 0.16666666666666666, 0.3333333333333333]` |
| Final gap | `0.0` | `-0.0` | `0` |

Both engines return `FW_GAP_REACHED`, `converged=true`, one applied update and two fresh evaluations. Every fixed-fixture marginal error is zero within `1e-8`. The line-search update decreases the objective. The terminal gap is recomputed at the returned matrix. The suitable explicit sibling-permutation start reaches zero FGW cost.

## Independent checks and actual execution

- Independent tensor objectives and gradients, feasible numerical directional derivatives, exact HiGHS LP objectives and POT objectives passed. Cold/warm tied, negative, one-node, unequal and seeded random/degenerate transport fixtures include 128-by-160. Tied LPs are compared by objective and marginals.
- OpenCL untraced default calls and direct OT hooks executed device simplex. The direct hook does not fall back. Changed-cost warm solves verify incremental tree rebuild/update and candidate repricing counters. Full-scan certification and primal/dual/original-marginal checks passed in all four modes.
- OpenCL persistent sessions exercised bounded queues, two independent workers/queues, immutable baseline preparation once, unequal targets/capacity growth, supplied starts, target/numerical error recovery, post-warm-up allocation stability and shutdown draining. Tracing, explicit CPU OT and oversized transport produce an explicitly labelled exact CPU-OT fallback.
- Java calculations execute serially in the calling thread. Java sessions reuse baseline preparation; fresh per-comparison/start bases and changed-cost warm solves are covered. No GPU/Python/native library is used by the Java engine.
- Source mappings retain production/occurrence/issue identifiers and decimal text exceeding binary floating-point precision; legacy FIFO identifiers remain caller metadata, with empty values in the Java production-style fixture.

## Environment and limitations

- CPU: AMD Ryzen 7 9700X, eight cores/16 logical processors. Java: OpenJDK 21.0.12.1. C++ compiler: GCC 16.2.1. CUDA toolkit: 13.4.92.
- Actual OpenCL/CUDA device: NVIDIA GeForce RTX 5070 Ti, driver 615.78.08, OpenCL 3.0 CUDA; `cl_khr_fp64`, maximum work-group size 1024, 49,152 local-memory bytes. Only this OpenCL implementation/device was tested.
- Independent references: Python 3.14.7, NumPy 2.5.3, SciPy 1.18.1 and POT 0.9.7.post1. POT configuration is FP64, uniform outer-product initialisation, `square_loss`, `symmetric=True`, `alpha=0.8`, `armijo=False`, `max_iter=200`, `tol_abs=1e-6`, `tol_rel=1e-5`.
- The first sandboxed Maven run had two unrelated existing socket-test errors (`Operation not permitted`). The same Java 21 command outside the sandbox passed all 113 tests. The initial sandbox device inventory was unavailable; actual device checks ran outside the sandbox.
- CUDA comparison-reference memcheck reported zero errors and zero bytes leaked; racecheck reported zero errors/warnings. These instrument CUDA, not OpenCL. A compatible OpenCL memory/race instrumentation tool was unavailable; OpenCL memory/race instrumentation remains unverified. Device numerical/resource-reuse tests do not replace it.
- No real production inputs were supplied. Evidence is synthetic; FIFO restoration and backend accelerator binding remain outside scope. No production readiness or global FGW optimum is implied by first-order convergence.

Generic `validate.py` names the supplied port input `host`/`CPU`; its `coverage.host` field identifies `JAVA_SERIAL` or `OPENCL`. OpenCL direct OT fixtures under that generic label executed the actual device. Full tracing uses explicitly reported exact CPU OT, as in CUDA.

Compressed raw reports retain the complete actual/expected matrices, per-iteration checks, OT certificates and source/executable identities. Reproduce them with the port README commands.
