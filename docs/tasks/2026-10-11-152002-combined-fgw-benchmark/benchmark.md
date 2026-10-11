# Default combined CUDA FGW benchmark

PASS: all six tiers completed, totalling 18,600 steady comparisons per execution path. The default combined solver reached 500.713 complete FGW comparisons/s at 10,000 requests, versus 291.883/s for the independent CPU reference (1.715 times the throughput).

## Environment and method

- GPU: NVIDIA GeForce RTX 5070 Ti, driver 615.78.08, compute capability 12.0; CUDA 13.4.92 Release binary built with SM 7.5 and forward PTX.
- CPU: AMD Ryzen 7 9700X, eight cores/16 logical CPUs; eight workers and an eight-thread total BLAS budget (one BLAS thread per worker).
- CUDA: 32 persistent slots, two dispatchers, CUDA_DEVICE_MAX_CONNECTIONS=32, device 0; exact FP64 simplex with both optimisations enabled by default.
- Inputs: fixed 128-node baseline, 160-node targets, 384-dimensional embeddings, reproducible 16-target pool; seeds 42, 43 and 1000+target index. All requests execute complete FGW without result caching.
- Each tier uses a new persistent session, with 64 warm-up comparisons and 16 reference targets before steady measurement. Initialisation, warm-up, steady processing and destruction are recorded separately. File parsing, process launch and device discovery are excluded.
- Correctness prerequisite: the unchanged source and executable hashes match the preceding default-integration report, which passed CTest 5/5 and 824 independent tensor/HiGHS/POT checks. These tests were not needlessly rerun for a measurement-only task.

## Steady processing results

| Comparisons | CPU/s | CUDA/s | CUDA / CPU | CPU seconds | CUDA seconds |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 285.040 | 401.098 | 1.407 | 0.351 | 0.249 |
| 500 | 290.851 | 482.570 | 1.659 | 1.719 | 1.036 |
| 1,000 | 292.168 | 488.681 | 1.673 | 3.423 | 2.046 |
| 2,000 | 291.475 | 497.228 | 1.706 | 6.862 | 4.022 |
| 5,000 | 292.236 | 499.282 | 1.708 | 17.109 | 10.014 |
| 10,000 | 291.883 | 500.713 | 1.715 | 34.260 | 19.972 |

## Phase timings

| Comparisons | Path | Initialisation ms | Warm-up ms | Steady ms | Destruction ms | Total ms |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 100 | CPU | 1.137 | 487.499 | 350.827 | 0.396 | 839.864 |
| 100 | CUDA | 285.361 | 1501.171 | 249.316 | 10.145 | 2045.993 |
| 500 | CPU | 1.013 | 492.917 | 1719.091 | 0.290 | 2213.317 |
| 500 | CUDA | 287.088 | 1497.893 | 1036.119 | 10.537 | 2831.637 |
| 1,000 | CPU | 1.280 | 490.575 | 3422.688 | 0.270 | 3914.817 |
| 1,000 | CUDA | 293.437 | 1608.398 | 2046.326 | 10.510 | 3958.672 |
| 2,000 | CPU | 1.108 | 490.899 | 6861.642 | 0.239 | 7353.891 |
| 2,000 | CUDA | 293.806 | 1521.234 | 4022.301 | 11.894 | 5849.236 |
| 5,000 | CPU | 1.096 | 494.702 | 17109.460 | 0.300 | 17605.561 |
| 5,000 | CUDA | 282.214 | 1578.332 | 10014.383 | 11.253 | 11886.183 |
| 10,000 | CPU | 1.123 | 490.899 | 34260.315 | 0.328 | 34752.669 |
| 10,000 | CUDA | 293.598 | 1524.872 | 19971.531 | 12.920 | 21802.922 |

## Validation and device work

- PASS: completed/requested counts match in both paths for all six tiers; none is time limited.
- PASS: all 16 CUDA reference targets per tier agree with independent CPU costs, gap, convergence status and iteration count at tolerance 1e-8. Every repeated CUDA result is checked against its reference and for marginal feasibility.
- PASS: maximum returned marginal error across both paths is 1.9342166757141399e-16; CUDA reports zero in all tiers.
- PASS: each CUDA session prepared its baseline once, created two dispatchers and 32 slots, reached 32 active comparisons, kept its pending queue within 64, and performed zero new device allocations after warm-up.
- PASS: every tier reports combined mode 3 and device OT placement with zero measured CPU OT time.
- At 10,000 comparisons: 86,875 OT solves, 18,836,250 pivots, 86,875 full tree rebuilds, 18,836,250 incremental updates, 18,836,250 candidate passes and 1,393,125 full scans.
- Mean GPU OT event interval at 10,000: 60.251 ms per comparison; complete device interval 63.006 ms; mean service time 63.757 ms. These intervals overlap across slots and can include contention/submission gaps; they must not be summed into steady wall time.

## Limits

These are short synthetic measurements on one device, without repetitions or statistical confidence intervals. They do not establish performance for arbitrary production graphs or long-term endurance. The independent CPU path uses POT network simplex, whereas CUDA uses transportation simplex; the throughput ratio includes implementation and solver differences. No isolated hardware kernel profile was collected. Algorithm code was not changed.

Raw results: `benchmark.json`. Process output: `benchmark.log`. Source and executable identity: `source-sha256.txt`. Numerical prerequisite: `../2026-10-11-151443-default-combined-simplex/numerical.json`.
