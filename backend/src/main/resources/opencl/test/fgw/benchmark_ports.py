#!/usr/bin/env python3
"""Matched complete-FGW benchmark: Java serial, multicore CPU, OpenCL and CUDA.

Run validate_ports.py and Java 21 tests first. Counts are complete comparisons,
not cached answers. All paths receive byte-identical fixtures. Defaults retain
the prescribed six tiers (18,600 requests per path) and 16-target fixed-seed pool.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import subprocess
import time

import numpy as np
from threadpoolctl import threadpool_info, threadpool_limits

ROOT = Path(__file__).resolve().parents[7]
REFERENCE = ROOT / "benchmark/fgw"
spec = importlib.util.spec_from_file_location("fgw_reference_benchmark", REFERENCE / "benchmark.py")
reference = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reference)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--validation-dir", type=Path, required=True)
    parser.add_argument("--opencl-build", type=Path, required=True)
    parser.add_argument("--cuda-build", type=Path, required=True)
    parser.add_argument("--counts", default="100,500,1000,2000,5000,10000")
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--opencl-workers", type=int, default=2)
    parser.add_argument("--cuda-slots", type=int, default=32)
    parser.add_argument("--cuda-dispatchers", type=int, default=2)
    parser.add_argument("--device", type=int, default=0)
    parser.add_argument("--warmup", type=int, default=64)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    counts = [int(x) for x in args.counts.split(",")]
    if min(counts) < 1 or min(args.workers, args.opencl_workers, args.cuda_slots, args.cuda_dispatchers) < 1:
        parser.error("Counts and worker/queue limits must be positive")
    evidence = json.loads((args.validation_dir / "validation-summary.json").read_text())
    if evidence.get("status") != "PASS" or len(evidence["modes"]) != 4:
        parser.error("Require complete passing four-mode Java/OpenCL/CUDA validation")
    for path, digest in evidence["sources"].items():
        if hashlib.sha256((ROOT / path).read_bytes()).hexdigest() != digest:
            parser.error("Source changed since numerical validation: " + path)
    out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=True)
    left = reference.graph(128, 42)
    targets = reference.target_pool(160, 16)
    pool = out / "pool.txt"
    reference.write_pool(pool, left, targets)
    classes = args.validation_dir.resolve() / "java-classes"
    java = ["java", "-Xms64m", "-Xmx512m", "-cp", str(classes), "dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwFixtures"]
    data = dict(status="RUNNING", platform=platform.platform(), cpuCount=os.cpu_count(),
                fixtureSha256=hashlib.sha256(pool.read_bytes()).hexdigest(), nodes=[128, 160], embeddings=384,
                alpha=.8, seed=[42, 43, "1000+targetIndex"], poolSize=16, counts=counts,
                javaExecution="One calling thread; pure Java FP64 and exact integral simplex", javaVm=["-Xms64m", "-Xmx512m"],
                cpuExecution="Independent NumPy/SciPy/POT reference; one BLAS thread per worker", cpuWorkers=args.workers,
                openclExecution="Persistent queues/buffers; device FP64 and exact simplex; synchronous kernel submission per worker",
                openclWorkers=args.opencl_workers, cudaSlots=args.cuda_slots, cudaDispatchers=args.cuda_dispatchers,
                cudaDeviceMaxConnections=os.environ.get("CUDA_DEVICE_MAX_CONNECTIONS", "driver default"),
                timing="Complete target preparation, solve, transfers, synchronisation and result checks; parsing/process launch excluded; one session per tier",
                solverConfound="CPU reference uses POT network simplex; Java/OpenCL/CUDA use their own exact transportation simplex implementations",
                validation=evidence, results=[])
    def save():
        (out / "benchmark.json").write_text(json.dumps(data, indent=2) + "\n")
    def check_pool(label, actual, expected):
        for i, (a, b) in enumerate(zip(actual, expected)):
            s = a["starts"][a["bestStart"]]
            if (any(abs(s["costs"][key] - b["costs"][key]) > 1e-8 for key in ["material", "structure", "fgw"])
                    or abs(s["gap"] - b["gap"]) > 1e-8 or s["status"] != b["status"] or s["iterations"] != b["iterations"]):
                raise RuntimeError(f"{label} differs from independent CPU at target {i}")
        if len(actual) != len(expected):
            raise RuntimeError(label + " target pool dimensions differ")
    with threadpool_limits(limits=1):
        data["blas"] = threadpool_info()
        for count in counts:
            row = dict(requested=count)
            data["results"].append(row)
            print(f"CPU_MULTICORE {count}", flush=True)
            row["CPU_MULTICORE"], expected = reference.steady_cpu(left, targets, count, args.workers, args.warmup, 0)
            save()
            output = out / f"java-{count}.json"
            print(f"JAVA_SERIAL {count}", flush=True)
            subprocess.run(java + ["--batch", str(pool), str(count), str(args.warmup), str(output)], check=True)
            serial = json.loads(output.read_text())
            check_pool("JAVA_SERIAL", serial["poolResults"], expected)
            row["JAVA_SERIAL"] = {k: v for k, v in serial.items() if k != "poolResults"}
            save()
            for label, path, slots, dispatchers in [
                ("OPENCL", args.opencl_build / "fgw_opencl_run", args.opencl_workers, args.opencl_workers),
                ("CUDA", args.cuda_build / "fgw_cuda_run", args.cuda_slots, args.cuda_dispatchers)]:
                output = out / f"{label.lower()}-{count}.json"
                print(f"{label} {count}", flush=True)
                subprocess.run([str(path.resolve()), "--batch", str(pool), str(count), str(args.workers),
                                str(args.device), str(args.warmup), "0", str(output), str(slots), str(dispatchers), "3"], check=True)
                native = json.loads(output.read_text())
                check_pool(label, native["reference"], expected)
                if label == "OPENCL" and native["meanCpuOtMilliseconds"] > 0:
                    raise RuntimeError("OpenCL benchmark used CPU OT; device timing cannot be claimed")
                if native["resources"]["allocationsAfterWarmup"] != 0:
                    raise RuntimeError(label + " allocated device buffers after warm-up")
                row[label] = native
                save()
            row["numericalComparison"] = "PASS"
            for label in ["CPU_MULTICORE", "JAVA_SERIAL", "OPENCL", "CUDA"]:
                if row[label]["status"] != "PASS" or row[label]["completed"] != count:
                    raise RuntimeError(label + " did not complete the prescribed count")
                print(f"{label} {count}: {row[label]['comparisonsPerSecond']:.3f}/s", flush=True)
            save()
    data["status"] = "PASS"
    save()


if __name__ == "__main__":
    main()
