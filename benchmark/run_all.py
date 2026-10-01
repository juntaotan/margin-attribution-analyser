#!/usr/bin/env python3
"""Build and run Java, OpenCL, and CUDA against one shared fixture."""

from __future__ import annotations

import argparse
import datetime as dt
import html
import json
import math
import statistics
import subprocess
import time
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
BENCHMARK = ROOT / "benchmark"
BUILD = BENCHMARK / "build"


def command(arguments: list[str], cwd: Path = ROOT) -> str:
    """Runs one build or backend command and returns its captured output."""
    completed = subprocess.run(
        arguments, cwd=cwd, text=True, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"Command failed: {' '.join(arguments)}\n{completed.stdout}")
    return completed.stdout


def build_all() -> None:
    """Builds the Java classes and both native benchmark executables."""
    print("Building Java, OpenCL, and CUDA benchmark runners...")
    command(["./mvnw", "-q", "-DskipTests", "compile"], ROOT / "backend")
    java_build = BUILD / "java"
    java_build.mkdir(parents=True, exist_ok=True)
    command([
        "javac", "-cp", str(ROOT / "backend/target/classes"),
        "-d", str(java_build), str(BENCHMARK / "java/BomFrontierJavaBenchmark.java"),
    ])
    command(["cmake", "-S", str(BENCHMARK), "-B", str(BUILD),
             "-DCMAKE_BUILD_TYPE=Release"])
    command(["cmake", "--build", str(BUILD), "--parallel"])


def run_backend(name: str, executable: list[str], dataset: Path,
                iterations: int, threshold: int, warmups: int) -> dict[str, Any]:
    """Runs one backend and records its complete process wall-clock time."""
    print(f"Running {name}...")
    start = time.perf_counter_ns()
    output = command(executable + [str(dataset), str(iterations), str(threshold), str(warmups)])
    wall_ms = (time.perf_counter_ns() - start) / 1_000_000.0
    line = next((line for line in output.splitlines()
                 if line.startswith("BENCHMARK_JSON ")), None)
    if line is None:
        raise RuntimeError(f"{name} emitted no BENCHMARK_JSON line\n{output}")
    result = json.loads(line.removeprefix("BENCHMARK_JSON "))
    result["end_to_end_ms"] = wall_ms
    result["iterations"] = iterations
    result["warmups"] = warmups
    return result


def percentile(values: list[float], fraction: float) -> float:
    """Returns a nearest-rank percentile for a small iteration sample."""
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def summarize(result: dict[str, Any], java_end_to_end: float | None) -> dict[str, Any]:
    """Creates the stable summary fields used by every report format."""
    summary = dict(result)
    for field in ("input_ms", "compute_ms", "output_ms", "total_ms"):
        summary[field.removesuffix("_ms") + "_avg_ms"] = statistics.fmean(result[field])
    summary["p50_ms"] = statistics.median(result["total_ms"])
    summary["p95_ms"] = percentile(result["total_ms"], 0.95)
    summary["speedup"] = (java_end_to_end / summary["end_to_end_ms"]
                          if java_end_to_end is not None else 1.0)
    return summary


def table(summaries: list[dict[str, Any]]) -> str:
    """Formats a compact terminal table with all requested timing phases."""
    headers = ["Backend", "End-to-end", "Load", "Input/setup", "Compute", "Output",
               "Engine", "P95", "Speedup"]
    rows = []
    for item in summaries:
        rows.append([
            item["backend"], f"{item['end_to_end_ms']:.2f}", f"{item['load_ms']:.2f}",
            f"{item['input_avg_ms']:.3f}",
            f"{item['compute_avg_ms']:.3f}", f"{item['output_avg_ms']:.3f}",
            f"{item['total_avg_ms']:.3f}", f"{item['p95_ms']:.3f}", f"{item['speedup']:.2f}×",
        ])
    widths = [max(len(headers[index]), *(len(row[index]) for row in rows))
              for index in range(len(headers))]
    separator = "┼".join("─" * (width + 2) for width in widths)
    output = ["┌" + separator.replace("┼", "┬") + "┐"]
    output.append("│" + "│".join(f" {headers[i]:<{widths[i]}} " for i in range(len(headers))) + "│")
    output.append("├" + separator + "┤")
    for row in rows:
        output.append("│" + "│".join(f" {row[i]:<{widths[i]}} " for i in range(len(headers))) + "│")
    output.append("└" + separator.replace("┼", "┴") + "┘")
    return "\n".join(output)


def markdown_report(dataset: Path, summaries: list[dict[str, Any]], matched: bool) -> str:
    """Creates a portable Markdown benchmark report."""
    lines = ["# BOM Frontier Benchmark", "", f"Dataset: `{dataset}`",
             f"Measured runs: **{summaries[0]['iterations']}**; warm-up runs: **{summaries[0]['warmups']}**", "",
             f"Correctness signatures: **{'MATCH' if matched else 'MISMATCH'}**", "",
             "| Backend | Device | End-to-end ms | Load ms | Input/setup ms | Compute ms | Output ms | Engine ms | P50 ms | P95 ms | Speedup |",
             "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for item in summaries:
        lines.append("| {backend} | {device} | {end_to_end_ms:.2f} | {load_ms:.2f} | {input_avg_ms:.3f} | "
                     "{compute_avg_ms:.3f} | {output_avg_ms:.3f} | {total_avg_ms:.3f} | "
                     "{p50_ms:.3f} | {p95_ms:.3f} | {speedup:.2f}× |".format(**item))
    lines += ["", "`End-to-end` is the complete backend process time. `Load` is fixture I/O; "
              "the engine phases are per-run values (or averages when multiple iterations are requested).", ""]
    return "\n".join(lines)


def html_report(dataset: Path, summaries: list[dict[str, Any]], matched: bool) -> str:
    """Creates a self-contained visual report with timing bars."""
    maximum = max(item["end_to_end_ms"] for item in summaries) or 1.0
    colors = {"Java": "#2563eb", "OpenCL": "#7c3aed", "CUDA": "#059669"}
    cards = []
    for item in summaries:
        width = 100 * item["end_to_end_ms"] / maximum
        cards.append(f"""
        <article class="card"><div class="title"><strong>{html.escape(item['backend'])}</strong>
        <span>{html.escape(item['device'])}</span></div>
        <div class="bar"><i style="width:{width:.2f}%;background:{colors[item['backend']]}"></i></div>
        <div class="total">{item['end_to_end_ms']:.2f} ms <small>end-to-end · {item['speedup']:.2f}× vs Java</small></div>
        <div class="phases"><span>Input/setup <b>{item['input_avg_ms']:.3f}</b></span>
        <span>Compute <b>{item['compute_avg_ms']:.3f}</b></span>
        <span>Output <b>{item['output_avg_ms']:.3f}</b></span>
        <span>P95 <b>{item['p95_ms']:.3f}</b></span></div></article>""")
    status = "MATCH" if matched else "MISMATCH"
    status_color = "#059669" if matched else "#dc2626"
    return f"""<!doctype html><html><head><meta charset="utf-8"><title>BOM Benchmark</title>
    <style>body{{font-family:Inter,system-ui;background:#f3f6fb;color:#172033;margin:0;padding:32px}}
    main{{max-width:980px;margin:auto}}h1{{margin-bottom:4px}}.meta{{color:#64748b;margin-bottom:24px}}
    .status{{color:{status_color};font-weight:800}}.card{{background:white;border:1px solid #dbe3ef;border-radius:14px;
    padding:20px;margin:14px 0;box-shadow:0 8px 24px #1e293b10}}.title{{display:flex;justify-content:space-between;gap:20px}}
    .title span{{color:#64748b;font-size:13px}}.bar{{height:13px;background:#e8edf5;border-radius:9px;margin:16px 0;overflow:hidden}}
    .bar i{{display:block;height:100%;border-radius:9px}}.total{{font-size:26px;font-weight:800}}small{{font-size:13px;color:#64748b}}
    .phases{{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-top:16px}}.phases span{{background:#f8fafc;padding:10px;border-radius:8px;font-size:12px}}
    .phases b{{display:block;font-size:15px;margin-top:3px}}code{{word-break:break-all}}</style></head>
    <body><main><h1>BOM Frontier Benchmark</h1><div class="meta"><code>{html.escape(str(dataset))}</code><br>
    Measured runs: {summaries[0]['iterations']} · Warm-up runs: {summaries[0]['warmups']}<br>
    Correctness signatures: <span class="status">{status}</span></div>{''.join(cards)}</main></body></html>"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=BENCHMARK / "datasets/random_10k")
    parser.add_argument("--iterations", type=int, default=1)
    parser.add_argument("--warmups", type=int, default=0)
    parser.add_argument("--threshold", type=int, default=100_000)
    parser.add_argument("--java-xmx", default="8g", help="Java maximum heap, for example 8g or 24g")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--output", type=Path, default=BENCHMARK / "results")
    args = parser.parse_args()
    dataset = args.dataset.resolve()

    if not args.skip_build:
        build_all()

    print(f"Configuration: {args.iterations} measured run(s), {args.warmups} warm-up run(s)")
    java_classpath = f"{BUILD / 'java'}:{ROOT / 'backend/target/classes'}"
    results = [
        run_backend("Java", ["java", f"-Xmx{args.java_xmx}", "-cp", java_classpath,
                    "BomFrontierJavaBenchmark"],
                    dataset, args.iterations, args.threshold, args.warmups),
        run_backend("OpenCL", [str(BUILD / "opencl_benchmark")],
                    dataset, args.iterations, args.threshold, args.warmups),
        run_backend("CUDA", [str(BUILD / "cuda_benchmark")],
                    dataset, args.iterations, args.threshold, args.warmups),
    ]

    java_end_to_end = results[0]["end_to_end_ms"]
    summaries = [summarize(result, java_end_to_end) for result in results]
    signatures = {(item["result_count"], item["result_sum"], item["result_xor"])
                  for item in results}
    matched = len(signatures) == 1

    print("\n" + table(summaries))
    print(f"\nCorrectness: {'✓ all result signatures match' if matched else '✗ result signatures differ'}")

    args.output.mkdir(parents=True, exist_ok=True)
    timestamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    base = args.output / f"benchmark-{timestamp}"
    payload = {"generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
               "dataset": str(dataset), "correctness_match": matched,
               "iterations": args.iterations, "warmups": args.warmups,
               "results": summaries}
    base.with_suffix(".json").write_text(json.dumps(payload, indent=2) + "\n")
    base.with_suffix(".md").write_text(markdown_report(dataset, summaries, matched))
    base.with_suffix(".html").write_text(html_report(dataset, summaries, matched))
    print(f"Reports: {base}.json | .md | .html")
    if not matched:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
