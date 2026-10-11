# Asynchronous CUDA FGW validation and throughput

Numerical correctness: **PASS** (824 checks with CUDA, independent CPU, HiGHS and POT).
Completed benchmark counts: **PASS** (3,600 CPU and 3,600 CUDA comparisons).
Asynchronous-plan throughput target of at least 200 complete comparisons/s: **FAIL**.

Configuration: NVIDIA GeForce RTX 5070 Ti, native CUDA 13.4, FP64, 128-by-160 nodes, 384-dimensional embeddings, a fixed pool of 16 nearby targets, eight CPU workers/eight CPU BLAS threads, two CUDA dispatchers, 32 GPU slots, `CUDA_DEVICE_MAX_CONNECTIONS=32`, 64 warm-up comparisons plus 16 references per tier. Warm-up ran in each measured session. All results were computed again; no target answers were cached.

| Requested/completed per path | CPU time (s) | CUDA time (s) | CPU comparisons/s | CUDA comparisons/s | Numerical agreement | 200/s target |
| ---: | ---: | ---: | ---: | ---: | --- | --- |
| 100 | 0.367316 | 0.782068 | 272.245 | 127.866 | PASS | FAIL |
| 500 | 1.786976 | 3.255831 | 279.802 | 153.571 | PASS | FAIL |
| 1,000 | 3.520372 | 6.485330 | 284.061 | 154.194 | PASS | FAIL |
| 2,000 | 7.277556 | 12.793446 | 274.818 | 156.330 | PASS | FAIL |

Every tier prepared the fixed baseline once, created exactly two dispatchers and 32 slots, reached 32 executing comparisons, remained within its bounded queue, reported zero marginal error, used no CPU OT, allocated no new device buffers after warm-up, and recorded zero explicit stream-synchronisation time in steady comparisons. Device event intervals and GPU OT timings overlap; they are not summed into wall time.

The 2,000-comparison tier measured 199.472 ms of GPU OT intervals per comparison within 201.931 ms of complete device intervals. Device transportation simplex accounts for approximately 98.8% of the measured device interval. This suggests that further progress towards the throughput target needs work on exact GPU OT or its batching; removing additional host waits alone is unlikely to bridge the remaining gap. This is an inference from timings, not a kernel profiler measurement.

Historical eight-worker GPU-simplex throughput was about 40 comparisons/s on the same synthetic shape/device. The current largest tier is about 3.9 times that rate; the historical implementation was not rerun as a paired control in this task. The independent CPU baseline remains faster (274.818 versus 156.330 comparisons/s). Different exact OT implementations remain a comparison confound.

The available standalone simplex diagnostic counts individual linear OT solves, whereas these benchmark targets require an average of 8.6875 LP evaluations per complete FGW comparison (updates plus the fresh terminal gap). The plan's 1,120-solves/s capacity claim was not reproduced in this task and must not be read as established complete-FGW throughput.

CUDA memcheck on the final source reported zero errors and zero leaked bytes. Racecheck initially detected a missing barrier between the cooperative initial-basis loop exit and its single-lane tail; the same transition existed before this task. Added a block barrier and reran correctness, memory checks and the complete benchmark. Final racecheck reported zero errors, zero warnings and zero shared-memory hazards. It does not certify host/global-memory race freedom.

## Diagnostic measurements before the final basis-barrier repair

- An initial 32-slot run with the default driver queue configuration and only 16 warm-up comparisons completed the 100, 500 and 1,000 CUDA tiers at 18.690, 17.428 and 17.245 comparisons/s. Its 2,000 CUDA tier was interrupted to investigate performance; the completed prefix of that tier is unknown. Its durable evidence is explicitly marked `INTERRUPTED_FOR_PERFORMANCE_INVESTIGATION`, not a completed four-tier PASS.
- A direct 128-comparison probe with 32 driver queues, 32 slots and 64 warm-up comparisons measured 90.759 comparisons/s while waiting on the oldest stream synchronously.
- Replacing that wait with non-blocking queries and a requested 50-microsecond host timeout measured 149.082 comparisons/s in the corresponding 128-comparison probe.
- A direct 256-comparison probe with 64 slots, two dispatchers, 32 driver queues and 128 warm-up comparisons measured 78.483 comparisons/s. More slots were slower on this configuration; 32 remains the default slot setting.
- Probes verify repeated native results; the final four-tier run separately verifies independent CPU numerical agreement. They are short measurements and do not establish long-run performance.

## Reproduction

Use the [FGW README](../../../backend/src/main/resources/cuda/test/fgw/README.md), including `--cuda-executable` for direct GPU LP validation. The benchmark command used in this task is:

```sh
CUDA_DEVICE_MAX_CONNECTIONS=32 /tmp/margin-fgw-venv/bin/python backend/src/main/resources/cuda/test/fgw/benchmark.py \
  --counts 100,500,1000,2000 --workers 8 --threads 8 --warmup 64 \
  --gpu-slots 32 --dispatchers 2 --device 0 \
  --cuda-run /tmp/margin-fgw-cuda/fgw_cuda_run \
  --cuda-validation-report docs/tasks/2026-10-11-140814-async-cuda-fgw/numerical-report.json \
  --output docs/tasks/2026-10-11-140814-async-cuda-fgw/benchmark-32-slots.json
```

These `/tmp` paths identify this run only. Configure local build/runtime paths as described in the README; they are not product defaults.

## Limitations

The minimum throughput target is unmet. Tracing and CPU OT/oversized inputs use synchronous exact-CPU-OT diagnostics and can delay other slots on their dispatcher. GPU simplex supports `N+M<=320` and reports pivot-limit exhaustion as numerical failure. The library does not alter process-wide driver settings: configure work queues before CUDA initialisation. Native execution was validated on one device/toolchain; Docker GPU execution, other devices, million-request endurance, OpenCL, Java integration and production CPU FGW are not certified by these results.
