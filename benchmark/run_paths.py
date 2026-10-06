#!/usr/bin/env python3
"""Measure the production path builder offline, without a database or GPU."""

import argparse
import datetime
import json
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "benchmark/build/paths"


def main():
    """Compile the runners, stream stage progress, and save successful measurements."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--threshold", type=int, default=100000, help="Stop threshold in micro-units")
    parser.add_argument("--java-xmx", default="12g")
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()
    BUILD.mkdir(parents=True, exist_ok=True)
    cp_file = BUILD / "classpath.txt"
    if not args.skip_build:
        subprocess.run(["./mvnw", "-q", "-DskipTests", "compile", "dependency:build-classpath",
                        f"-Dmdep.outputFile={cp_file}", "-DincludeScope=runtime"], cwd=ROOT / "backend", check=True)
    classpath = os.pathsep.join([str(BUILD), str(ROOT / "backend/target/classes"), cp_file.read_text().strip()])
    if not args.skip_build:
        subprocess.run(["javac", "-cp", classpath, "-d", str(BUILD),
                        str(ROOT / "benchmark/java/BomFrontierJavaBenchmark.java"),
                        str(ROOT / "benchmark/java/BomPathBenchmark.java")], check=True)
    print("One run, no warm-up. All paths are retained in memory; no JSON path export is timed.", flush=True)
    start = time.perf_counter()
    process = subprocess.Popen(["java", f"-Xmx{args.java_xmx}", "-cp", classpath,
                                "BomPathBenchmark", str(args.dataset.resolve()), str(args.threshold)],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    result = None
    for line in process.stdout:
        if line.startswith("PATH_BENCHMARK_JSON "):
            result = json.loads(line.removeprefix("PATH_BENCHMARK_JSON "))
        else:
            print(line, end="", flush=True)
    code = process.wait()
    if code or result is None:
        raise SystemExit(f"Path benchmark failed (exit {code}). No complete timing recorded. "
                         "If memory was exhausted, use a smaller fixture or a larger available heap.")
    result.update(end_to_end_ms=(time.perf_counter()-start)*1000,
                  dataset=str(args.dataset.resolve()), threshold_micro=args.threshold, java_xmx=args.java_xmx)
    print(json.dumps(result, indent=2))
    output = ROOT / "benchmark/results"
    output.mkdir(exist_ok=True)
    report = output / f"paths-{datetime.datetime.now():%Y%m%d-%H%M%S}.json"
    report.write_text(json.dumps(result, indent=2) + "\n")
    print(f"Report: {report}")


if __name__ == "__main__":
    main()
