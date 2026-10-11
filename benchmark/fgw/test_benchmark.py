#!/usr/bin/env python3
"""Compare the relocated benchmark with the original CUDA benchmark.

Run with the pinned FGW Python dependencies. By default the original source is
read from the latest Git revision that added or modified it, so the test remains
usable after removal of its old package and subsequent migration commits.
--original-benchmark accepts a captured source when the original differs from
HEAD. --integration runs both real CLIs at all six tiers, requiring passing CUDA
numerical evidence and an actual device. Timing and scheduling are measured,
but are not expected to be identical between separate executions.
"""
import argparse
import ast
import hashlib
import importlib.util
import inspect
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import numpy as np
from threadpoolctl import threadpool_limits

import benchmark

ROOT = Path(__file__).resolve().parents[2]
ORIGINAL_PATH = "backend/src/main/resources/cuda/test/fgw/benchmark.py"
original = None


def load_original(path):
    spec = importlib.util.spec_from_file_location("original_cuda_benchmark", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def compare_values(actual, expected, path="result"):
    """Compare the full numerical schema, requiring finite FP64 values at 1e-8."""
    if isinstance(expected, dict):
        if actual.keys() != expected.keys():
            raise AssertionError(f"{path}: schema differs")
        for key in expected:
            compare_values(actual[key], expected[key], f"{path}.{key}")
    elif isinstance(expected, list):
        if len(actual) != len(expected):
            raise AssertionError(f"{path}: length differs")
        for i, (a, b) in enumerate(zip(actual, expected)):
            compare_values(a, b, f"{path}[{i}]")
    elif isinstance(expected, (float, np.floating)):
        if not np.isfinite(actual) or not np.isfinite(expected) or abs(actual - expected) > 1e-8:
            raise AssertionError(f"{path}: {actual} != {expected}")
    elif actual != expected:
        raise AssertionError(f"{path}: {actual} != {expected}")


class BenchmarkParityTest(unittest.TestCase):
    """Exercise unchanged fixtures, mathematics and validation failure handling."""

    def test_original_numerical_functions_unchanged(self):
        for name in ["graph", "structure", "cpu", "write_fixture", "target_pool", "write_pool", "steady_cpu"]:
            self.assertEqual(ast.dump(ast.parse(inspect.getsource(getattr(benchmark, name)))),
                             ast.dump(ast.parse(inspect.getsource(getattr(original, name)))), name)

    def test_all_pool_inputs_byte_identical(self):
        left, old_left = benchmark.graph(128, 42), original.graph(128, 42)
        targets, old_targets = benchmark.target_pool(160, 16), original.target_pool(160, 16)
        for actual, expected in zip([left] + targets, [old_left] + old_targets):
            for a, b in zip(actual, expected):
                np.testing.assert_array_equal(a, b)
        with tempfile.TemporaryDirectory(prefix="fgw-parity-fixture-") as directory:
            new_path, old_path = Path(directory) / "new.txt", Path(directory) / "old.txt"
            benchmark.write_pool(new_path, left, targets)
            original.write_pool(old_path, old_left, old_targets)
            self.assertEqual(new_path.read_bytes(), old_path.read_bytes())
            self.assertEqual(hashlib.sha256(new_path.read_bytes()).hexdigest(),
                             "eebd7837ea229076639394bfdb2564a860853cf4e120961f9683b02332231e3f")

    def test_all_pool_cpu_results(self):
        left = benchmark.graph(128, 42)
        for i, target in enumerate(benchmark.target_pool(160, 16)):
            actual, expected = benchmark.cpu(left, target, 1), original.cpu(left, target, 1)
            actual.pop("milliseconds")
            expected.pop("milliseconds")
            np.testing.assert_allclose(actual.pop("P"), expected.pop("P"), rtol=0, atol=1e-8)
            compare_values(actual, expected, f"target[{i}]")

    def test_missing_and_nonfinite_results_rejected(self):
        expected = [{"costs": dict(material=1., structure=2., fgw=1.8), "gap": 0.,
                     "status": "FW_GAP_REACHED", "iterations": 2}]
        with self.assertRaisesRegex(RuntimeError, "dimensions"):
            benchmark.check_pool("TEST", [], expected)
        for key in ["material", "structure", "fgw"]:
            start = dict(expected[0], costs=dict(expected[0]["costs"], **{key: float("nan")}))
            with self.assertRaisesRegex(RuntimeError, "discrepancy"):
                benchmark.check_pool("TEST", [{"bestStart": 0, "starts": [start]}], expected)

    def test_stale_or_incomplete_validation_rejected(self):
        with tempfile.TemporaryDirectory(prefix="fgw-parity-evidence-") as directory:
            path = Path(directory)
            source = "backend/src/main/resources/cuda/fgw.cu"
            evidence = dict(status="PASS", tolerance=1e-8,
                            modes=[dict(mode=i, checks=dict(java=dict(status="PASS"))) for i in range(4)],
                            sources={source: hashlib.sha256((ROOT / source).read_bytes()).hexdigest()})
            report = path / "validation-summary.json"
            report.write_text(json.dumps(evidence))
            self.assertEqual(benchmark.port_evidence(path, 3, ["java"]), evidence)
            evidence["modes"][0]["checks"]["java"]["status"] = "FAIL"
            report.write_text(json.dumps(evidence))
            with self.assertRaisesRegex(ValueError, "mode 0"):
                benchmark.port_evidence(path, 3, ["java"])
            evidence["modes"][0]["checks"]["java"]["status"] = "PASS"
            evidence["sources"][source] = "stale"
            report.write_text(json.dumps(evidence))
            with self.assertRaisesRegex(ValueError, "Source changed"):
                benchmark.port_evidence(path, 3, ["java"])
            evidence["modes"].pop()
            report.write_text(json.dumps(evidence))
            with self.assertRaisesRegex(ValueError, "four-mode"):
                benchmark.port_evidence(path, 3, ["java"])


def integration(args, source):
    """Execute original/new CUDA CLIs and compare complete numerical evidence."""
    out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=True)
    common = ["--counts", args.counts, "--workers", str(args.workers), "--threads", str(args.workers),
              "--warmup", "64", "--cuda-run", str(args.cuda_run.resolve()),
              "--cuda-validation-report", str(args.cuda_validation_report.resolve())]
    for label, script in [("original", source), ("relocated", Path(benchmark.__file__))]:
        with (out / f"{label}.log").open("w") as log:
            subprocess.run([sys.executable, str(script), *common, "--output", str(out / f"{label}.json")],
                           check=True, stdout=log, stderr=subprocess.STDOUT)
    old = json.loads((out / "original.json").read_text())
    new = json.loads((out / "relocated.json").read_text())
    for key in ["nodes", "embeddingDimension", "poolSize", "seed", "requestedCounts", "workers",
                "cpuThreadBudget", "gpuSlots", "dispatcherThreads", "simplexMode", "warmupComparisons",
                "alpha", "tolAbs", "tolRel", "tolGapAbs", "tolGapRel", "maxIterations", "costScaleFloor"]:
        compare_values(new[key], old[key], key)
    if len(old["results"]) != len(new["results"]):
        raise AssertionError("Tier count differs")
    tiers = []
    for a, b in zip(new["results"], old["results"]):
        for label in ["cpu", "cuda"]:
            for key in ["requested", "completed", "status", "maximumMarginalError"]:
                compare_values(a[label][key], b[label][key], f"{label}.{key}")
            if a[label]["status"] != "PASS" or a[label]["completed"] != a[label]["requested"]:
                raise AssertionError("Incomplete execution")
        compare_values(a["cudaCoverage"], b["cudaCoverage"])
        compare_values(a["numericalComparison"], b["numericalComparison"])
        if a["cudaCoverage"] != "CUDA" or a["numericalComparison"] != "PASS":
            raise AssertionError("Actual CUDA numerical evidence missing")
        for key in ["reference", "otWork", "poolSize", "referenceComparisons", "simplexMode"]:
            compare_values(a["cuda"][key], b["cuda"][key], f"cuda.{key}")
        for key in ["baselinePreparations", "workersCreated", "gpuSlotsCreated", "workerDeviceBytes", "allocationsAfterWarmup"]:
            compare_values(a["cuda"]["resources"][key], b["cuda"]["resources"][key], f"resources.{key}")
        tiers.append(dict(count=a["cpu"]["requested"], status="PASS",
                          originalCpuMilliseconds=b["cpu"]["steadyMilliseconds"],
                          relocatedCpuMilliseconds=a["cpu"]["steadyMilliseconds"],
                          originalCudaMilliseconds=b["cuda"]["steadyMilliseconds"],
                          relocatedCudaMilliseconds=a["cuda"]["steadyMilliseconds"]))
    report = dict(status="PASS", tolerance=1e-8, originalSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                  relocatedSha256=hashlib.sha256(Path(benchmark.__file__).read_bytes()).hexdigest(),
                  tiers=tiers, comparisonsPerPathPerRunner=sum(t["count"] for t in tiers),
                  timing="Independent measured runs; runtime and scheduling are not equality assertions")
    (out / "parity.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2), flush=True)


def main():
    global original
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original-benchmark", type=Path)
    parser.add_argument("--integration", action="store_true")
    parser.add_argument("--cuda-run", type=Path)
    parser.add_argument("--cuda-validation-report", type=Path)
    parser.add_argument("--counts", default="100,500,1000,2000,5000,10000")
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    if args.integration and (not args.cuda_run or not args.cuda_validation_report or not args.output_dir):
        parser.error("Integration requires --cuda-run, --cuda-validation-report and --output-dir")
    with tempfile.TemporaryDirectory(prefix="fgw-original-benchmark-") as directory:
        source = args.original_benchmark
        if source is None:
            source = Path(directory) / "benchmark.py"
            revision = subprocess.run(["git", "log", "-1", "--diff-filter=AM", "--format=%H", "--", ORIGINAL_PATH],
                                      cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()
            if not revision:
                parser.error("Original Git source unavailable; supply --original-benchmark")
            source.write_bytes(subprocess.run(["git", "show", f"{revision}:{ORIGINAL_PATH}"], cwd=ROOT,
                                              check=True, capture_output=True).stdout)
        original = load_original(source)
        with threadpool_limits(limits=1):
            result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(BenchmarkParityTest))
        if not result.wasSuccessful():
            raise SystemExit(1)
        if args.integration:
            integration(args, source)


if __name__ == "__main__":
    main()
