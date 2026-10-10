# Persistent CUDA FGW benchmark

Result: PASS. Every requested comparison completed on both execution paths.

Inputs: fixed 128-node baseline, 160-node targets, 384-dimensional FP64 embeddings; 16 reproducible nearby targets; 8 persistent workers and an 8-thread CPU budget. Every request executes complete FGW; no answer cache.

Uniform initialisation, alpha 0.8, prescribed tolerances and maximum 200 updates are unchanged. POT version: 0.9.7.post1. CPU uses independent SciPy/NumPy/POT; CUDA uses native CPU transportation simplex with a per-start basis retained across FW iterations. This solver difference is an explicit comparison confound.

| Completed comparisons per path | CPU steady seconds | CUDA steady seconds | CPU comparisons/s | CUDA comparisons/s | CUDA/CPU throughput | Result |
| ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 100 | 0.339813 | 0.254023 | 294.280 | 393.665 | 1.338 | PASS |
| 500 | 1.750479 | 1.241840 | 285.636 | 402.628 | 1.410 | PASS |
| 1,000 | 3.184763 | 2.370131 | 313.995 | 421.918 | 1.344 | PASS |
| 2,000 | 6.417989 | 4.743335 | 311.624 | 421.644 | 1.353 | PASS |
| 5,000 | 15.917002 | 11.824337 | 314.130 | 422.857 | 1.346 | PASS |
| 10,000 | 32.135935 | 23.546600 | 311.178 | 424.690 | 1.365 | PASS |

Total completed: 18,600 CPU and 18,600 CUDA comparisons, excluding reference and warm-up requests. These short runs verify throughput and resource reuse, not million-request endurance.

| Tier | CPU initialisation ms | CPU warm-up ms | CPU destruction ms | CPU total ms | CUDA initialisation ms | CUDA warm-up ms | CUDA destruction ms | CUDA total ms |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 1.146857 | 491.357233 | 0.255279 | 832.575129 | 90.290354 | 502.700581 | 0.517160 | 847.532267 |
| 500 | 0.817967 | 494.801479 | 0.245400 | 2246.347327 | 78.686316 | 504.852111 | 0.522261 | 1825.902137 |
| 1000 | 0.773201 | 476.009272 | 0.258405 | 3661.806140 | 99.607582 | 492.864357 | 0.464419 | 2963.067900 |
| 2000 | 0.865979 | 469.795659 | 0.229289 | 6888.882177 | 87.715742 | 502.123672 | 0.498264 | 5333.673846 |
| 5000 | 0.773241 | 474.727252 | 0.229479 | 16392.735556 | 90.883238 | 497.158336 | 0.532290 | 12412.911992 |
| 10000 | 1.081412 | 475.783913 | 0.299955 | 32613.104554 | 87.123650 | 491.764970 | 0.500740 | 24125.989933 |

| Tier | Mean native service ms | Mean CPU OT ms | Mean CUDA event interval ms | Mean explicit synchronisation ms | Worker-owned device bytes | Allocations after warm-up |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 19.178574 | 16.145039 | 2.613410 | 0.009361 | 51776608 | 0 |
| 500 | 19.657949 | 16.666589 | 2.575651 | 0.009637 | 51776608 | 0 |
| 1000 | 18.872870 | 15.823499 | 2.636504 | 0.009017 | 51776608 | 0 |
| 2000 | 18.925054 | 15.919164 | 2.597160 | 0.008805 | 51776608 | 0 |
| 5000 | 18.890514 | 15.875156 | 2.605024 | 0.008836 | 51776608 | 0 |
| 10000 | 18.820526 | 15.786155 | 2.624772 | 0.008655 | 51776608 | 0 |

Every CUDA tier prepared the baseline exactly once, created eight workers, and allocated no new device buffers after warm-up. The maximum queued targets was nine, within the configured capacity of sixteen. Worker-owned bytes exclude the baseline, CUDA context and cuBLAS internal allocations; they are not total device peak memory.

Maximum marginal error: CPU `1.9342166757141399e-16`, CUDA `0`. All pool targets matched the independent CPU material, structure and FGW costs, final gap, status and update count within `1e-8`. Every repeated native result passed finite/nonnegative correspondence, marginal, cost/gap/status and update-count checks. The engine enforces objective monotonicity and checks a fresh final gap.

CUDA event intervals include kernels, transfers and host-submission gaps. Explicit synchronisation waits overlap device work. Worker intervals overlap across tasks and must not be added into batch wall time. File parsing, process launch and device enumeration are excluded; CUDA context/device preparation, baseline preparation and worker creation are included in initialisation. Totals include warm-up and destruction.

The previous long benchmark was interrupted at the user's request. Its last durable checkpoint recorded 26,000 CPU comparisons towards 100,000, with no completed tier and no CUDA measurement; the exact count at interruption was not captured. This checkpoint is not a PASS at 100,000.

Raw values and per-target iteration costs: [benchmark-short.json](benchmark-short.json). Full prescribed matrices and expected values: [numerical-report.md](numerical-report.md).

## Estimate for 100,000 comparisons

The 10,000 tier measured 32.135935 seconds CPU (311.178 comparisons/s) and 23.546600 seconds CUDA (424.690 comparisons/s), with every request completed and checked. At the same throughput, 100,000 complete comparisons would take approximately **321.36 seconds CPU (5 minutes 21 seconds)** or **235.47 seconds CUDA (3 minutes 55 seconds)**. Sequentially benchmarking both paths would take approximately **556.83 seconds (9 minutes 17 seconds)** of steady processing, plus reference/warm-up/initialisation and process/report overhead.

This is a linear extrapolation from the measured 10,000 tier, not an executed 100,000 result. It assumes the same 128-by-160 inputs, 16-target pool, eight workers/eight CPU threads, tolerances, iteration behaviour and available device resources. Other workloads, load and thermal behaviour may change throughput. Startup and warm-up are paid once per execution path, rather than multiplied by ten. Exact OT remains on the CPU and is included in the CUDA time.

Additional measured evidence: [benchmark-10000.json](benchmark-10000.json), [benchmark-10000.log](benchmark-10000.log). Combined six-tier evidence: [benchmark-six-tiers.json](benchmark-six-tiers.json).
