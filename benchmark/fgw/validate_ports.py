#!/usr/bin/env python3
"""Validate serial Java and actual OpenCL against independent CPU/POT and CUDA.

Build native executables first. Requires Java 21, Python dependencies from the
CUDA numerical requirements, and actual OpenCL/CUDA devices. No skipped device
run can produce PASS. Raw reports retain actual/expected matrices and LP checks.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[2]
CUDA = ROOT / "backend/src/main/resources/cuda"
OPENCL = ROOT / "backend/src/main/resources/opencl"
JAVA = ROOT / "backend/src/main/java/dev/margintrace/margin_attribution_backend/algorithm/FGW/FgwEngine.java"
FIXTURES = ROOT / "backend/src/test/java/dev/margintrace/margin_attribution_backend/algorithm/FGW/FgwFixtures.java"
MAIN = "dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwFixtures"


def run(command, env=None):
    subprocess.run(list(map(str, command)), check=True, env=env)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--opencl-build", type=Path, required=True)
    parser.add_argument("--cuda-build", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=True)
    classes = out / "java-classes"
    run(["javac", "-d", classes, JAVA, FIXTURES])
    wrapper = out / "java-run"
    wrapper.write_text("#!/bin/sh\nexec java -cp " + shlex.quote(str(classes)) + " " + MAIN + ' "$@"\n')
    wrapper.chmod(0o755)
    summary = {"tolerance": 1e-8, "executions": ["JAVA_SERIAL", "OPENCL", "CUDA"], "modes": [],
               "sources": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                           for p in [JAVA, FIXTURES, OPENCL / "fgw.cl", OPENCL / "fgw_engine.cpp",
                                     OPENCL / "fgw_engine.hpp", CUDA / "fgw.cu", CUDA / "fgw_engine.cu", CUDA / "fgw_engine.cuh"]}}
    for mode in range(4):
        env = dict(os.environ, FGW_SIMPLEX_MODE=str(mode))
        native = {}
        for label, command in [("java", wrapper), ("opencl", args.opencl_build.resolve() / "fgw_opencl_test"),
                               ("cuda", args.cuda_build.resolve() / "fgw_cuda_test")]:
            path = out / f"{label}-mode-{mode}.json"
            run([command, path], env)
            native[label] = path
        row = {"mode": mode, "checks": {}}
        for label, executable in [("java", wrapper), ("opencl", args.opencl_build.resolve() / "fgw_opencl_test")]:
            report = out / f"{label}-validation-mode-{mode}"
            run([os.sys.executable, ROOT / "benchmark/fgw/validate.py", "--native-report", native[label],
                 "--native-executable", executable, "--cuda-report", native["cuda"],
                 "--cuda-executable", args.cuda_build.resolve() / "fgw_cuda_test", "--output", report], env)
            data = json.loads(report.with_suffix(".json").read_text())
            if data["cpuResult"] != "PASS" or data["coverage"]["cuda"] != "CUDA":
                raise RuntimeError(f"{label} mode {mode}: incomplete/failed numerical evidence")
            row["checks"][label] = {"status": "PASS", "count": len(data["checks"]),
                "maximumAbsoluteError": max(c["maximumAbsoluteError"] or 0 for c in data["checks"])}
        summary["modes"].append(row)
        (out / "validation-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    summary["status"] = "PASS"
    (out / "validation-summary.json").write_text(json.dumps(summary, indent=2) + "\n")


if __name__ == "__main__":
    main()
