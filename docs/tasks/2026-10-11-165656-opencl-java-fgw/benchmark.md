# Matched complete-FGW benchmark

Status: PASS. Each execution path completed all six tiers, totalling **18,600 comparisons**. Warm-up and reference comparisons are excluded from those counts. All target results matched the independent CPU reference at `1e-8` for separate costs, fresh gap, applied updates and stopping status.

Inputs: the same fixed-seed 128-node baseline, 16 nearby 160-node targets and 384-dimensional FP64 embeddings used by the existing CUDA benchmark. No answers or target distance matrices are memoised. The pool file SHA-256 is `eebd7837ea229076639394bfdb2564a860853cf4e120961f9683b02332231e3f`.

CPU: AMD Ryzen 7 9700X (eight cores/16 logical processors). GPU: NVIDIA GeForce RTX 5070 Ti, driver 615.78.08. Java: OpenJDK 21.0.12.1. The Java VM used `-Xms64m -Xmx512m`; all FGW calculations run in one calling thread. The independent CPU reference uses eight workers and one BLAS thread per worker. OpenCL uses two persistent workers/queues. CUDA uses 32 slots/two dispatchers with `CUDA_DEVICE_MAX_CONNECTIONS=32`. These different concurrency and exact-simplex implementations are explicit comparison confounds.

## Complete steady throughput

| Comparisons | Java serial (one calling thread), /s | CPU reference (eight workers), /s | OpenCL (two queues), /s | CUDA (32 slots), /s |
| --- | --- | --- | --- | --- |
| 100 | 19.605 | 290.300 | 15.369 | 402.220 |
| 500 | 19.933 | 294.517 | 15.067 | 486.321 |
| 1000 | 19.908 | 287.120 | 15.108 | 489.642 |
| 2000 | 19.773 | 288.567 | 15.263 | 497.633 |
| 5000 | 19.775 | 290.403 | 15.212 | 500.824 |
| 10000 | 19.823 | 294.432 | 15.298 | 502.394 |

## Final 10,000-request runtime

| Execution | Initialisation, ms | Warm-up including references, ms | Complete steady processing, s | Total runtime, s |
| --- | --- | --- | --- | --- |
| JAVA_SERIAL | 6.668 | 4569.935 | 504.468 | 509.054 |
| CPU_MULTICORE | 0.961 | 483.403 | 33.964 | 34.448 |
| OPENCL | 291.213 | 5314.139 | 653.699 | 659.519 |
| CUDA | 234.836 | 1469.895 | 19.905 | 21.620 |

One process/session is used per tier with same-session warm-up. Native total runtime includes resource destruction; Java retains ordinary JVM-managed memory with no native execution resources. Parsing/process launch and writing the evidence files are excluded. Steady timing includes complete target preparation, exact OT, transfers, synchronisation, result collection and repeated-result checks.

## Native work and resource evidence

| Final tier | OT solves | Pivots | Full scans | Candidate passes | Tree rebuilds | Incremental updates | Tree nodes visited | Cycle edges |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| OPENCL | 86875 | 18836250 | 1393125 | 18836250 | 86875 | 18836250 | 1217225000 | 291478750 |
| CUDA | 86875 | 18836250 | 1393125 | 18836250 | 86875 | 18836250 | 1217225000 | 291478750 |

| Final tier | Baseline preparations | Effective slots/queues | Maximum queued | Allocations after warm-up | Mean CPU OT, ms | Mean device OT, ms | Maximum marginal error |
| --- | --- | --- | --- | --- | --- | --- | --- |
| OPENCL | 1 | 2 | 3 | 0 | 0.000000 | 69.550330 | 0 |
| CUDA | 1 | 32 | 61 | 0 | 0.000000 | 59.398512 | 0 |

Every native tier used device exact simplex without CPU OT and recorded zero device-buffer allocations after warm-up. OpenCL kernel-event durations and CUDA stream-event intervals have different definitions; overlapping waits/events must not be summed into wall time. All 16 pool targets reached `FW_GAP_REACHED`; this is first-order convergence, not a global-optimality certificate.

The full benchmark is slower on this OpenCL implementation than on Java serial, the multicore CPU reference or CUDA. Correctness and reduced simplex work do not imply a performance improvement. The pilot overlapped CUDA reference instrumentation and is retained separately; the six-tier measurements above ran after that instrumentation finished.

The numerical summary and compressed raw benchmark reports are retained beside this file. Raw Java reports include correspondence/source mappings; native reports retain reference costs, all runtime phases and work/resource counters. Fixture generation and reproduction commands are in the [port README](../../../backend/src/main/resources/opencl/test/fgw/README.md).

These measurements cover the synthetic workload and tested hardware. They do not establish backend integration, performance on arbitrary production data, million-request endurance or cross-device coverage. OpenCL memory/race instrumentation remains unverified because a compatible tool was unavailable.
