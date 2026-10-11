#!/usr/bin/env python3
"""Validate and compare exact GPU transportation-simplex modes.

Uses the existing independent CPU/POT FGW benchmark without duplicating its
mathematics. Every mode must pass the prescribed numerical report before any
performance measurement. Runs are sequential; rotated mode order reduces a
fixed ordering bias, but does not establish statistical confidence intervals.
"""
import argparse
import hashlib
import json
import os
import platform
import subprocess
import sys
from pathlib import Path

import benchmark
from threadpoolctl import threadpool_info, threadpool_limits

MODES = {0: "reference", 1: "incremental-tree", 2: "candidate-pricing", 3: "combined"}


def run(command, log, env):
    """Run one check, preserve its output and reject skipped/failed device checks."""
    with log.open("w") as output:
        execution = subprocess.run(list(map(str, command)), env=env, stdout=output,
                                   stderr=subprocess.STDOUT, check=False)
    if execution.returncode:
        raise RuntimeError(f"Exit {execution.returncode}: {command}; see {log}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--counts", default="100,500,1000,2000,5000,10000")
    parser.add_argument("--device", type=int, default=0)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--threads", type=int, default=8)
    parser.add_argument("--gpu-slots", type=int, default=32)
    parser.add_argument("--dispatchers", type=int, default=2)
    parser.add_argument("--warmup", type=int, default=64)
    parser.add_argument("--original-run", type=Path,
                        help="Optional pre-experiment binary, measured as an additional paired control")
    args = parser.parse_args()
    counts = [int(x) for x in args.counts.split(",")]
    if (min(counts) < 1 or args.workers < 1 or args.threads < max(2, args.workers) or
            args.gpu_slots < 1 or args.dispatchers < 1 or args.warmup < 0):
        parser.error("Require positive counts/workers/slots/dispatchers and sufficient CPU threads")
    build = args.build_dir.resolve()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    root = Path(__file__).resolve().parent
    env = dict(os.environ, FGW_DEVICE=str(args.device), FGW_SIMPLEX_MODE="0")
    data = dict(status="VALIDATING", modes=MODES, requestedCounts=counts,
                platform=platform.platform(), availableCpuCount=os.cpu_count(),
                versions=dict(python=platform.python_version(), numpy=benchmark.np.__version__,
                              scipy=benchmark.scipy.__version__, POT=benchmark.ot.__version__),
                device=args.device, nodes=[128, 160], embeddingDimension=384, poolSize=16,
                workers=args.workers, cpuThreadBudget=args.threads, gpuSlots=args.gpu_slots,
                dispatchers=args.dispatchers, warmup=args.warmup,
                cudaDeviceMaxConnections=env.get("CUDA_DEVICE_MAX_CONNECTIONS", "driver default"),
                seed=[42, 43, "1000+targetIndex"], alpha=.8, tolAbs=1e-6, tolRel=1e-5,
                tolGapAbs=1e-6, tolGapRel=1e-5, maxIterations=200, costScaleFloor=1e-12,
                results=[], numerical={},
                limitations="CUDA events overlap and can include contention/queuing. CPU uses a different exact OT solver. Short runs do not establish endurance or production readiness.")
    files = [(root.parent.parent / "backend/src/main/resources/cuda") / name for name in ["fgw.cu", "fgw_engine.cu", "fgw_engine.cuh"]]
    files += [Path(__file__).resolve(), root / "benchmark.py", build / "fgw_cuda_run", build / "fgw_cuda_test"]
    if args.original_run:
        files.append(args.original_run.resolve())
    data["sha256"] = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}
    def save():
        (output / "experiment.json").write_text(json.dumps(data, indent=2) + "\n")
    save()
    run(["ctest", "--test-dir", build, "-R", "^fgw_", "--output-on-failure"], output / "ctest.log", env)
    run([build / "fgw_host_test", output / "host.json"], output / "host.log", env)
    for mode in MODES:
        mode_env = dict(env, FGW_SIMPLEX_MODE=str(mode))
        print(f"Numerical validation: {MODES[mode]}", flush=True)
        run([build / "fgw_cuda_test", output / f"cuda-mode-{mode}.json"],
            output / f"cuda-mode-{mode}.log", mode_env)
        run([sys.executable, root / "validate.py", "--native-report", output / "host.json",
             "--cuda-report", output / f"cuda-mode-{mode}.json", "--native-executable", build / "fgw_host_test",
             "--cuda-executable", build / "fgw_cuda_test", "--output", output / f"numerical-mode-{mode}.md"],
            output / f"numerical-mode-{mode}.log", mode_env)
        evidence = json.loads((output / f"numerical-mode-{mode}.json").read_text())
        if evidence["cpuResult"] != "PASS" or evidence["coverage"]["cuda"] != "CUDA" or evidence["simplexMode"] != mode:
            raise RuntimeError(f"Numerical evidence missing/failed for mode {mode}")
        data["numerical"][mode] = dict(result="PASS", checks=len(evidence["checks"]),
            maximumAbsoluteError=max(x["maximumAbsoluteError"] for x in evidence["checks"]),
            device=evidence["cudaDeviceInfo"])
        save()
    data["status"] = "BENCHMARKING"
    left = benchmark.graph(128, 42)
    targets = benchmark.target_pool(160, 16)
    pool = output / "pool.txt"
    benchmark.write_pool(pool, left, targets)
    with threadpool_limits(limits=max(1, args.threads // args.workers)):
        data["blas"] = threadpool_info()
        for tier, count in enumerate(counts):
            print(f"CPU benchmark: {count}", flush=True)
            cpu, reference = benchmark.steady_cpu(left, targets, count, args.workers, args.warmup, 0)
            order = [(tier + offset) % 4 for offset in range(4)]
            if args.original_run:
                order.insert(tier % 5, "original")
            row = dict(requested=count, cpu=cpu, modeOrder=order, cuda={})
            data["results"].append(row)
            save()
            for mode in order:
                print(f"CUDA benchmark: {mode}, {count}", flush=True)
                path = output / f"benchmark-{count}-mode-{mode}.json"
                executable = args.original_run.resolve() if mode == "original" else build / "fgw_cuda_run"
                command = [executable, "--batch", pool, count, args.workers, args.device,
                           args.warmup, 0, path, args.gpu_slots, args.dispatchers]
                if mode != "original":
                    command.append(mode)
                run(command, output / f"benchmark-{count}-mode-{mode}.log", env)
                actual = json.loads(path.read_text())
                row["cuda"][mode] = actual
                save()  # Preserve measurements before numerical comparison, including a failed run.
                if actual["status"] != "PASS" or actual["completed"] != count:
                    raise RuntimeError("Incomplete CUDA benchmark")
                if mode != "original" and actual.get("simplexMode") != mode:
                    raise RuntimeError("Native benchmark did not report the requested simplex mode")
                for i, value in enumerate(actual["reference"]):
                    selected = value["starts"][value["bestStart"]]
                    expected = reference[i]
                    if (any(abs(selected["costs"][k] - expected["costs"][k]) > 1e-8 for k in ["material", "structure", "fgw"]) or
                            abs(selected["gap"] - expected["gap"]) > 1e-8 or selected["status"] != expected["status"] or
                            selected["iterations"] != expected["iterations"]):
                        data["status"] = "FAIL_NUMERICAL_BENCHMARK_COMPARISON"
                        save()
                        raise RuntimeError(f"Independent CPU discrepancy: mode {mode}, target {i}")
                actual["independentCpuComparison"] = "PASS"
                print(f"Completed {count}: mode {mode}, {actual['comparisonsPerSecond']:.3f} FGW/s", flush=True)
                save()
    data["status"] = "PASS"
    save()
    print("PASS: all modes validated and all requested comparisons completed", flush=True)


if __name__ == "__main__":
    main()
