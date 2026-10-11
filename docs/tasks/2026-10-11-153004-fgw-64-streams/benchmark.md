# 64-stream / four-dispatcher CUDA FGW experiment

The configuration completed all required comparisons correctly but reduced 10,000-request steady throughput to 290.891/s. An immediate 32-stream / two-dispatcher control achieved 500.569/s on identical inputs: a 41.89% throughput reduction for 64/4. Library and benchmark defaults remain unchanged.

## Method

- Unchanged combined exact GPU simplex, FP64, 128-node baseline, 160-node targets, 384-dimensional embeddings, reproducible 16-target pool. The same seeds, parameters and stopping tolerances as the preceding benchmark were used.
- Six tiers: 100, 500, 1,000, 2,000, 5,000 and 10,000; 18,600 steady comparisons per CPU/CUDA path, excluding 64 warm-up and 16 reference requests per tier. No time limit or result caching.
- Eight CPU workers with an eight-thread total BLAS budget; CUDA 64 slots and four host dispatchers, queue capacity 128, CUDA_DEVICE_MAX_CONNECTIONS=32.
- RTX 5070 Ti, driver 615.78.08, CUDA 13.4.92, Ryzen 7 9700X. Release SM 7.5/forward-PTX executable and production-source hashes match the preceding validated default implementation.
- The numerical prerequisite remains CTest 5/5 plus 824 independent tensor/HiGHS/POT checks for these unchanged sources/executables. This measurement task additionally validates numerical agreement and actual 64/4 resources through every tier.
- After the six-tier run, regenerated the same fixed-seed input pool and ran a 10,000-request 32/2 control immediately, with matching warm-up and connection settings. Its numerical outputs agree with the independently checked 64/4 references.

## Steady throughput and duration

| Comparisons | CPU/s this run | 32/2 CUDA/s earlier | 64/4 CUDA/s | CPU seconds | 64/4 CUDA seconds | Change from earlier 32/2 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 277.644 | 401.098 | 341.768 | 0.360 | 0.293 | -14.79% |
| 500 | 290.021 | 482.570 | 311.640 | 1.724 | 1.604 | -35.42% |
| 1,000 | 296.651 | 488.681 | 287.034 | 3.371 | 3.484 | -41.26% |
| 2,000 | 291.820 | 497.228 | 291.088 | 6.854 | 6.871 | -41.46% |
| 5,000 | 291.419 | 499.282 | 282.131 | 17.157 | 17.722 | -43.49% |
| 10,000 | 290.121 | 500.713 | 290.891 | 34.468 | 34.377 | -41.90% |

Immediate control at 10,000: 32/2 completed in 19.977 seconds (500.569/s), compared with 34.377 seconds (290.891/s) for 64/4. Both completed exactly 10,000 steady comparisons.

## Phase timings

| Comparisons | Path | Initialisation ms | Warm-up ms | Steady ms | Destruction ms | Total ms |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 100 | CPU | 1.200 | 503.553 | 360.173 | 0.379 | 865.310 |
| 100 | CUDA 64/4 | 305.805 | 1859.828 | 292.596 | 22.923 | 2481.153 |
| 500 | CPU | 0.983 | 487.632 | 1724.010 | 0.266 | 2212.897 |
| 500 | CUDA 64/4 | 280.774 | 1980.069 | 1604.415 | 22.958 | 3888.217 |
| 1,000 | CPU | 0.931 | 490.260 | 3370.959 | 0.327 | 3862.479 |
| 1,000 | CUDA 64/4 | 294.773 | 1783.793 | 3483.910 | 23.197 | 5585.674 |
| 2,000 | CPU | 0.919 | 500.059 | 6853.550 | 0.249 | 7354.781 |
| 2,000 | CUDA 64/4 | 283.027 | 1875.260 | 6870.785 | 23.339 | 9052.412 |
| 5,000 | CPU | 0.983 | 483.763 | 17157.422 | 0.287 | 17642.457 |
| 5,000 | CUDA 64/4 | 283.031 | 1935.586 | 17722.255 | 17.216 | 19958.089 |
| 10,000 | CPU | 1.100 | 491.754 | 34468.408 | 0.348 | 34961.614 |
| 10,000 | CUDA 64/4 | 305.175 | 1895.994 | 34377.185 | 22.983 | 36601.337 |
| 10,000 | CUDA 32/2 control | 291.570 | 1546.123 | 19977.251 | 11.377 | 21826.321 |

## Validation and interpretation

- PASS: all requested counts completed; all target references agree with independent CPU costs, gaps, statuses and updates at tolerance 1e-8. Repeated results and returned marginals pass.
- PASS: maximum returned marginal error is 1.9342166757141399e-16; CUDA reports zero.
- PASS: each experiment session created four dispatchers and 64 slots, reached 64 active comparisons, prepared its baseline once, stayed within the queue bound and made zero new allocations after warm-up. OT remained on device.
- PASS: every recorded OT work counter matches the earlier 32/2 run at every tier and the immediate 10,000 control. The slowdown did not arise from extra pivots or changed numerical results.
- Accounted device buffers doubled from 210,389,248 bytes in 32/2 to 420,778,496 bytes in 64/4.
- At 10,000, mean GPU OT event interval is 60.130 ms, complete device interval 89.475 ms and service time 216.971 ms. These overlapping event intervals include scheduling/submission effects and are not isolated kernel profiles.
- The measured slowdown and unchanged work counts support investigating scheduling/queue contention. More streams share the 32 driver work queues, and additional dispatchers may also add overhead. Neither cause is individually established by this run: stream count and dispatcher count changed together. [NVIDIA documents the queue-sharing behaviour](https://docs.nvidia.com/cuda/cuda-programming-guide/05-appendices/environment-variables.html#cuda-device-max-connections).
- No repeated-run confidence intervals or hardware kernel profile were collected. Results concern these short synthetic workloads on one device. CPU uses POT network simplex, while CUDA uses transportation simplex; CPU/GPU ratios include implementation differences.

Raw evidence: `benchmark.json`, `benchmark.log`, `control-32-2.json`, `control-32-2.log`, `validation-checks.json` and `source-sha256.txt`. Earlier six-tier 32/2 results: `../2026-10-11-152002-combined-fgw-benchmark/benchmark.json`.
