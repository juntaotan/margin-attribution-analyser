#!/usr/bin/env python3
"""Complete fixed-baseline 128-by-160 FP64 FGW throughput benchmark.

Uses bounded persistent CPU/CUDA worker pools and nearby target trees. Every
request executes complete FGW. Time-limited prefixes are explicitly incomplete.
"""
import argparse
import json
import os
import platform
import subprocess
import tempfile
import time
from pathlib import Path

import numpy as np
import ot
import scipy
from scipy.sparse import csr_matrix
from scipy.sparse.csgraph import shortest_path
from scipy.spatial.distance import cdist
from threadpoolctl import threadpool_info, threadpool_limits


def graph(n, seed):
    rng = np.random.default_rng(seed)
    offsets = np.zeros(n + 1, dtype=int)
    children = [[] for _ in range(n)]
    for i in range(1, n):
        children[(i - 1) // 2].append(i)
    columns = []
    for i, values in enumerate(children):
        columns += values
        offsets[i + 1] = len(columns)
    embeddings = rng.normal(0, .01, (n, 384)).astype(np.float64)
    return offsets, np.array(columns, dtype=int), embeddings


def structure(graph):
    offsets, columns, embeddings = graph
    n = len(embeddings)
    topology = csr_matrix((np.ones(len(columns)), columns, offsets), shape=(n, n))
    return shortest_path(topology + topology.T, directed=False, unweighted=True)


def cpu(left, right, threads, baseline=None):
    """Independent optimised reference, retaining the same objective/stopping settings."""
    begin = time.perf_counter()
    d1 = baseline[0] if baseline is not None else structure(left)
    d2 = structure(right)
    material = cdist(left[2], right[2], metric="sqeuclidean")
    n, m = material.shape
    p = baseline[1] if baseline is not None else np.full(n, 1 / n)
    q = np.full(m, 1 / m)
    P = np.outer(p, q)
    left_norm = baseline[2] if baseline is not None else (d1 * d1) @ p
    fixed = left_norm[:, None] + ((d2 * d2) @ q)[None, :]
    previous = None
    trace = []
    for updates in range(201):
        H = fixed - 2 * d1 @ P @ d2.T
        mat_cost, struct_cost = np.sum(material * P), np.sum(H * P)
        cost = .2 * mat_cost + .8 * struct_cost
        G = .2 * material + 1.6 * H
        S = ot.emd(p, q, G, numItermax=100000, check_marginals=True)
        delta = S - P
        b = float(np.sum(G * delta))
        gap = -b
        trace.append(float(cost))
        if (not np.all(np.isfinite(P)) or np.min(P) < -1e-9 or
                max(np.max(abs(P.sum(axis=1) - p)), np.max(abs(P.sum(axis=0) - q))) > 1e-9 or
                not np.isfinite(cost) or gap < -1e-10 * max(abs(cost), 1e-12)):
            status = "NUMERICAL_FAILURE"
            break
        if gap <= 1e-6 + 1e-5 * max(abs(cost), 1e-12):
            status = "FW_GAP_REACHED"
            break
        if updates == 200:
            status = "MAX_ITERATIONS"
            break
        if previous is not None and (abs(previous - cost) <= 1e-6 or abs(previous - cost) / max(abs(previous), 1e-12) <= 1e-5):
            status = "COST_STALLED"
            break
        a = float(-1.6 * np.sum((d1 @ delta @ d2.T) * delta))
        candidates = [0., 1.]
        if a > 0:
            candidates.append(float(np.clip(-b / (2 * a), 0, 1)))
        gamma = min(candidates, key=lambda g: a * g * g + b * g)
        previous = float(cost)
        P += gamma * delta
    elapsed = (time.perf_counter() - begin) * 1000
    if status == "NUMERICAL_FAILURE" or any(a + 1e-10 * max(1., abs(a)) < b for a, b in zip(trace, trace[1:])):
        raise RuntimeError("CPU benchmark failed numerical validation")
    return dict(milliseconds=elapsed, costs=dict(material=float(mat_cost), structure=float(struct_cost), fgw=float(cost)),
                gap=gap, iterations=updates, status=status, trace=trace, P=P)


def write_fixture(path, left, right):
    with path.open("w") as out:
        for offsets, columns, embeddings in [left, right]:
            out.write(f"{len(embeddings)} {len(columns)}\n")
            out.write(" ".join(map(str, offsets)) + "\n")
            out.write(" ".join(map(str, columns)) + "\n")
            np.savetxt(out, embeddings, fmt="%.17g")


def target_pool(n, size=16):
    """Fixed-seed nearby trees: reparent a leaf and perturb material contents."""
    pool = []
    for i in range(size):
        offsets, columns, embeddings = graph(n, 43)
        adjacency = [list(columns[offsets[j]:offsets[j + 1]]) for j in range(n)]
        if i:
            leaf = n - 1 - ((i - 1) % max(1, n // 4))
            parent = (leaf - 1) // 2
            adjacency[parent].remove(leaf)
            adjacency[max(0, parent - 1)].append(leaf)
            embeddings += np.random.default_rng(1000 + i).normal(0, .0001, embeddings.shape)
        columns = np.array([v for row in adjacency for v in row], dtype=int)
        offsets = np.r_[0, np.cumsum([len(row) for row in adjacency])]
        pool.append((offsets, columns, embeddings))
    return pool


def write_pool(path, left, targets):
    with path.open("w") as out:
        def write(g):
            offsets, columns, embeddings = g
            out.write(f"{len(embeddings)} {len(columns)}\n")
            out.write(" ".join(map(str, offsets)) + "\n")
            out.write(" ".join(map(str, columns)) + "\n")
            np.savetxt(out, embeddings, fmt="%.17g")
        write(left)
        out.write(f"{len(targets)}\n")
        for target in targets:
            write(target)


def steady_cpu(left, targets, count, workers, warmup, max_seconds, progress=None):
    """Bounded worker pool; every request prepares its target and solves complete FGW."""
    total_begin = time.perf_counter()
    d1 = structure(left)
    p = np.full(len(d1), 1 / len(d1))
    baseline = (d1, p, (d1 * d1) @ p)
    initialization_ms = (time.perf_counter() - total_begin) * 1000
    begin = time.perf_counter()
    reference = [cpu(left, target, 1, baseline) for target in targets]
    completed = 0
    samples_ms = []
    from collections import deque
    from concurrent.futures import ThreadPoolExecutor
    pending = deque()
    maximum_pending = 0
    with ThreadPoolExecutor(max_workers=workers) as executor:
        for i in range(warmup):
            pending.append(executor.submit(cpu, left, targets[i % len(targets)], 1, baseline))
            if len(pending) >= 2 * workers:
                pending.popleft().result()
        while pending:
            pending.popleft().result()
        warmup_ms = (time.perf_counter() - begin) * 1000
        begin = time.perf_counter()
        service_sum = 0.
        max_marginal_error = 0.
        def consume():
            nonlocal completed, service_sum, max_marginal_error
            i, future = pending.popleft()
            result = future.result()
            expected = reference[i]
            if (abs(result["costs"]["fgw"] - expected["costs"]["fgw"]) > 1e-8 or
                    abs(result["gap"] - expected["gap"]) > 1e-8 or result["status"] != expected["status"]):
                raise RuntimeError("CPU repeated comparison changed numerical result")
            max_marginal_error = max(max_marginal_error,
                float(np.max(abs(result["P"].sum(axis=1) - 1 / len(left[2])))),
                float(np.max(abs(result["P"].sum(axis=0) - 1 / len(targets[i][2])))))
            service_sum += result["milliseconds"]
            if len(samples_ms) < 1000:
                samples_ms.append(result["milliseconds"])
            completed += 1
            if progress and completed % 1000 == 0:
                progress(completed, (time.perf_counter() - begin) * 1000)
        for i in range(count):
            if i and max_seconds and time.perf_counter() - begin >= max_seconds:
                break
            pending.append((i % len(targets), executor.submit(cpu, left, targets[i % len(targets)], 1, baseline)))
            maximum_pending = max(maximum_pending, len(pending))
            if len(pending) >= 2 * workers:
                consume()
        while pending:
            consume()
        steady_ms = (time.perf_counter() - begin) * 1000
        destruction_begin = time.perf_counter()
    destruction_ms = (time.perf_counter() - destruction_begin) * 1000
    return dict(requested=count, completed=completed, status="PASS" if count == completed else "INCOMPLETE_TIME_LIMIT",
                initializationMilliseconds=initialization_ms, warmupMilliseconds=warmup_ms, destructionMilliseconds=destruction_ms,
                steadyMilliseconds=steady_ms, totalIncludingWarmupAndDestructionMilliseconds=(time.perf_counter() - total_begin) * 1000,
                comparisonsPerSecond=1000 * completed / steady_ms, meanServiceMilliseconds=service_sum / completed,
                firstServiceSamplesMilliseconds=samples_ms, maximumPending=maximum_pending,
                maximumMarginalError=max_marginal_error), reference


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--counts", default="100,500,1000,2000,5000,10000")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--gpu-slots", type=int, default=32)
    parser.add_argument("--dispatchers", type=int, default=2)
    parser.add_argument("--simplex-mode", type=int, choices=range(4), default=3,
                        help="Exact OT mode: 0=reference, 1=incremental tree, 2=candidate pricing, 3=combined (default)")
    parser.add_argument("--threads", type=int, default=4, help="Total CPU BLAS thread budget; divided across workers")
    parser.add_argument("--warmup", type=int, default=64)
    parser.add_argument("--pool-size", type=int, default=16)
    parser.add_argument("--max-seconds", type=float, default=0, help="Optional steady-time limit PER execution/count; incomplete runs exit 2")
    parser.add_argument("--cuda-run", type=Path)
    parser.add_argument("--cuda-validation-report", type=Path)
    parser.add_argument("--device", type=int, default=0)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    counts = [int(x) for x in args.counts.split(",")]
    if args.gpu_slots < 1 or args.dispatchers < 1 or min(counts) < 1 or args.workers < 1 or args.threads < args.workers or args.threads < 2 or args.warmup < 0 or not 1 <= args.pool_size <= 1024 or not np.isfinite(args.max_seconds) or args.max_seconds < 0:
        parser.error("Require positive counts/workers/slots/dispatchers, >=2 CPU threads and >=workers, nonnegative warm-up/limit, pool size 1..1024")
    if args.cuda_run:
        if not args.cuda_validation_report:
            parser.error("CUDA benchmarking requires passing CUDA numerical evidence")
        evidence = json.loads(args.cuda_validation_report.read_text())
        if evidence["cpuResult"] != "PASS" or evidence["coverage"]["cuda"] != "CUDA":
            parser.error("CUDA numerical correctness must pass before benchmarking")
        if evidence.get("simplexMode", 0) != args.simplex_mode:
            parser.error("CUDA numerical evidence must match the selected simplex mode")
    left = graph(128, 42)
    targets = target_pool(160, args.pool_size)
    rows = []
    data = dict(platform=platform.platform(), availableCpuCount=os.cpu_count(), python=platform.python_version(),
                numpy=np.__version__, scipy=scipy.__version__, POT=ot.__version__, nodes=[128, 160],
                embeddingDimension=384, poolSize=len(targets), seed=[42, 43, "1000+targetIndex"],
                requestedCounts=counts, workers=args.workers, cpuThreadBudget=args.threads,
                gpuSlots=args.gpu_slots, dispatcherThreads=args.dispatchers, warmupComparisons=args.warmup,
                simplexMode=args.simplex_mode,
                cudaDeviceMaxConnections=os.environ.get("CUDA_DEVICE_MAX_CONNECTIONS", "driver default"),
                maxSecondsPerExecution=args.max_seconds, alpha=.8, tolAbs=1e-6, tolRel=1e-5, tolGapAbs=1e-6,
                tolGapRel=1e-5, maxIterations=200, costScaleFloor=1e-12,
                cpuBaseline="Independent SciPy/NumPy FGW; persistent baseline; fixed workers; multicore BLAS; POT exact network simplex",
                cudaOt="Device exact balanced transportation simplex; CPU fallback above N+M=320; solver differences are a comparison confound",
                timing="One process/session per count, warm-up in that session; complete target preparation/solve/transfers/output; no result cache",
                deviceTiming="CUDA event intervals include kernels, transfers and host submission gaps; synchronisation overlaps these intervals",
                results=rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    def save():
        args.output.write_text(json.dumps(data, indent=2) + "\n")
    with threadpool_limits(limits=max(1, args.threads // args.workers)):
        data["blas"] = threadpool_info()
        with tempfile.TemporaryDirectory(prefix="fgw-steady-") as directory:
            path = Path(directory) / "pool.txt"
            write_pool(path, left, targets)
            for count in counts:
                print(f"CPU requested {count}", flush=True)
                def progress(completed, elapsed_ms):
                    data["progress"] = dict(execution="CPU", requested=count, completed=completed, status="RUNNING", steadyMilliseconds=elapsed_ms)
                    save()
                    print(f"CPU completed {completed}/{count}", flush=True)
                cpu_stats, reference = steady_cpu(left, targets, count, args.workers, args.warmup, args.max_seconds, progress)
                data.pop("progress", None)
                row = dict(cpu=cpu_stats, cudaCoverage="UNVERIFIED: no CUDA evidence supplied")
                rows.append(row)
                save()
                print(f"CPU completed {cpu_stats['completed']}/{count}, {cpu_stats['comparisonsPerSecond']:.3f}/s", flush=True)
                if args.cuda_run:
                    output = Path(directory) / "cuda.json"
                    command = [str(args.cuda_run.resolve()), "--batch", str(path), str(count), str(args.workers),
                               str(args.device), str(args.warmup), str(args.max_seconds), str(output),
                               str(args.gpu_slots), str(args.dispatchers), str(args.simplex_mode)]
                    execution = subprocess.run(command, check=False)
                    if execution.returncode not in (0, 2):
                        raise RuntimeError(f"CUDA benchmark exit {execution.returncode}")
                    cuda = json.loads(output.read_text())
                    for i, result in enumerate(cuda["reference"]):
                        actual = result["starts"][result["bestStart"]]
                        expected = reference[i]
                        if (any(abs(actual["costs"][k] - expected["costs"][k]) > 1e-8 for k in ["material", "structure", "fgw"]) or
                                abs(actual["gap"] - expected["gap"]) > 1e-8 or actual["status"] != expected["status"] or
                                actual["iterations"] != expected["iterations"]):
                            raise RuntimeError(f"CUDA/independent CPU discrepancy at target {i}")
                    row.update(cudaCoverage="CUDA", numericalComparison="PASS", cuda=cuda,
                               throughputRatio=cuda["comparisonsPerSecond"] / cpu_stats["comparisonsPerSecond"])
                    save()
    if any(row["cpu"]["status"] != "PASS" or row.get("cuda", {}).get("status", "PASS") != "PASS" for row in rows):
        print("INCOMPLETE: requested counts not reached; measured prefixes are recorded, never extrapolated as completed runs")
        raise SystemExit(2)


if __name__ == "__main__":
    main()
