#!/usr/bin/env python3
"""Benchmark raw issue ingestion, cost rollup, graph build, and frontier pruning."""

import argparse
import datetime as dt
import html
import json
import subprocess
import tempfile
import time
from pathlib import Path

from run_all import BENCHMARK, BUILD, ROOT, build_all, run_backend


def prepare(dataset, output, leaf_threshold):
    """Run the shared CPU preparation as a fresh process for one backend."""
    command = ["python3", str(BENCHMARK / "prepare_production_bom.py"),
               "--dataset", str(dataset), "--output", str(output),
               "--leaf-threshold", str(leaf_threshold)]
    start = time.perf_counter_ns()
    completed = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, check=True)
    wall_ms = (time.perf_counter_ns() - start) / 1e6
    line = next(line for line in completed.stdout.splitlines() if line.startswith("PREP_JSON "))
    return json.loads(line.removeprefix("PREP_JSON ")), wall_ms


def paths(dataset, selected, output):
    """Write complete paths in a fresh process and include its wall-clock cost."""
    command = ["python3", str(BENCHMARK / "build_production_paths.py"),
               "--dataset", str(dataset), "--selected", str(selected), "--output", str(output)]
    start = time.perf_counter_ns()
    completed = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, check=True)
    wall_ms = (time.perf_counter_ns() - start) / 1e6
    line = next(line for line in completed.stdout.splitlines() if line.startswith("PATH_JSON "))
    return json.loads(line.removeprefix("PATH_JSON ")), wall_ms


def print_table(results):
    """Show the measured pipeline and its two independently timed processes."""
    headers = ["Backend", "Pipeline ms", "Prepare ms", "Engine proc ms", "Path ms",
               "Compute ms", "Selected", "Paths", "Speedup"]
    base = next((row["pipeline_ms"] for row in results if row["backend"] == "Java"),
                results[0]["pipeline_ms"])
    rows = [[result["backend"], f'{result["pipeline_ms"]:.2f}',
             f'{result["prepare_process_ms"]:.2f}',
             f'{result["engine"]["end_to_end_ms"]:.2f}',
             f'{result["path_process_ms"]:.2f}',
             f'{result["engine"]["compute_ms"][0]:.3f}',
             str(result["engine"]["result_count"]),
             str(result["paths"]["paths"]),
             f'{base / result["pipeline_ms"]:.2f}×'] for result in results]
    widths = [max(len(headers[i]), *(len(row[i]) for row in rows)) for i in range(len(headers))]
    rule = "┼".join("─" * (width + 2) for width in widths)
    print("┌" + rule.replace("┼", "┬") + "┐")
    print("│" + "│".join(f" {headers[i]:<{widths[i]}} " for i in range(len(headers))) + "│")
    print("├" + rule + "┤")
    for row in rows:
        print("│" + "│".join(f" {row[i]:<{widths[i]}} " for i in range(len(headers))) + "│")
    print("└" + rule.replace("┼", "┴") + "┘")


def save_report(dataset, threshold, leaf_threshold, results, output):
    """Save raw stage timings and a readable report for later inspection."""
    output.mkdir(parents=True, exist_ok=True)
    stem = output / f"production-pipeline-{dt.datetime.now():%Y%m%d-%H%M%S}"
    signatures = {(row["engine"]["result_count"], row["engine"]["result_sum"],
                   row["engine"]["result_xor"]) for row in results}
    path_signatures = {row["paths"]["sha256"] for row in results}
    matched = len(signatures) == len(path_signatures) == 1 if len(results) > 1 else None
    payload = {"dataset": str(dataset), "threshold_micro": threshold,
               "leaf_threshold_micro": leaf_threshold,
               "scope": "raw issues + two BOMs + cost rollup + CSR + frontier + full path export",
               "excludes": ["database query", "UI serialization"],
               "correctness_match": matched, "results": results}
    stem.with_suffix(".json").write_text(json.dumps(payload, indent=2) + "\n")
    lines = ["# Production BOM pipeline benchmark", "", f"Dataset: `{dataset}`", "",
             f"Stop threshold: {threshold:,} micro-units; leaf threshold: {leaf_threshold:,} micro-units.",
             "", "| Backend | Pipeline ms | Issue read ms | Cost rollup ms | Graph encode ms | "
             "Engine process ms | Path process ms | Frontier compute ms | Selected | Paths |",
             "|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for row in results:
        prep, engine = row["prepare"], row["engine"]
        lines.append(f'| {row["backend"]} | {row["pipeline_ms"]:.2f} | '
                     f'{prep["issue_read_ms"]:.2f} | {prep["cost_rollup_ms"]:.2f} | '
                     f'{prep["graph_encode_ms"]:.2f} | {engine["end_to_end_ms"]:.2f} | '
                     f'{row["path_process_ms"]:.2f} | {engine["compute_ms"][0]:.3f} | '
                     f'{engine["result_count"]} | {row["paths"]["paths"]} |')
    lines += ["", "Each backend re-runs the same CPU preparation in a fresh process. Pipeline time "
              "is preparation, backend, and path-export process wall time; stage times are nested, "
              "not extra additions. The input contains raw issue events, but prices in older datasets "
              "are recovered from leaf costs. Path export follows the API's material-to-stop "
              "traversal and writes every path to a temporary binary file. No database access or UI "
              "serialization is timed.", "", f"Result signatures: "
              f"{'MATCH' if matched else 'MISMATCH' if matched is False else 'NOT COMPARED'}.", ""]
    stem.with_suffix(".md").write_text("\n".join(lines))
    table = "".join(f"<tr><td>{html.escape(row['backend'])}</td><td>{row['pipeline_ms']:.2f}</td>"
                    f"<td>{row['prepare']['issue_read_ms']:.2f}</td>"
                    f"<td>{row['prepare']['cost_rollup_ms']:.2f}</td>"
                    f"<td>{row['prepare']['graph_encode_ms']:.2f}</td>"
                    f"<td>{row['engine']['end_to_end_ms']:.2f}</td>"
                    f"<td>{row['path_process_ms']:.2f}</td>"
                    f"<td>{row['engine']['compute_ms'][0]:.3f}</td>"
                    f"<td>{row['engine']['result_count']}</td><td>{row['paths']['paths']}</td></tr>"
                    for row in results)
    stem.with_suffix(".html").write_text(
        "<!doctype html><meta charset='utf-8'><title>Production BOM benchmark</title>"
        "<style>body{font:16px system-ui;background:#f3f6fb;color:#172033;padding:32px}"
        "main{max-width:1100px;margin:auto;background:white;padding:32px;border-radius:16px}"
        "table{border-collapse:collapse;width:100%}td,th{padding:12px;border-bottom:1px solid #ddd;text-align:right}"
        "td:first-child,th:first-child{text-align:left}small{color:#64748b}</style><main>"
        "<h1>Production BOM pipeline benchmark</h1>"
        f"<p><small>{html.escape(str(dataset))}</small></p>"
        "<table><tr><th>Backend</th><th>Pipeline ms</th><th>Issue read</th><th>Cost rollup</th>"
        "<th>Graph encode</th><th>Engine process</th><th>Path process</th><th>Compute</th>"
        "<th>Selected</th><th>Paths</th></tr>"
        f"{table}</table><p>Raw issues → cost rollup → CSR → frontier → complete path export. "
        "Excludes database and UI serialization.</p></main>")
    print(f"Reports: {stem}.json | .md | .html")
    if matched is False:
        raise SystemExit("Backend result signatures differ")


def main():
    """Run a fresh preparation and one end-to-end engine invocation per backend."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--threshold", type=int, required=True, help="Stop threshold in micro-units")
    parser.add_argument("--leaf-threshold", type=int, default=1)
    parser.add_argument("--java-xmx", default="8g")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--backends", nargs="+", choices=["Java", "OpenCL", "CUDA"],
                        default=["Java", "OpenCL", "CUDA"])
    parser.add_argument("--output", type=Path, default=BENCHMARK / "results")
    args = parser.parse_args()
    dataset = args.dataset.resolve()
    if not args.skip_build:
        build_all()
    runners = {
        "Java": ["java", f"-Xmx{args.java_xmx}", "-cp",
                 f"{BUILD / 'java'}:{ROOT / 'backend/target/classes'}", "BomFrontierJavaBenchmark"],
        "OpenCL": [str(BUILD / "opencl_benchmark")],
        "CUDA": [str(BUILD / "cuda_benchmark")],
    }
    results = []
    for backend in args.backends:
        print(f"Preparing raw issues for {backend}...", flush=True)
        with tempfile.TemporaryDirectory(prefix="bom-pipeline-") as directory:
            directory = Path(directory)
            prepared, prep_wall = prepare(dataset, directory, args.leaf_threshold)
            selected = directory / "selected.bin"
            engine = run_backend(backend, runners[backend], directory, 1,
                                 args.threshold, 0, selected)
            path_result, path_wall = paths(directory, selected, directory / "paths.bin")
        results.append({"backend": backend, "prepare": prepared,
                        "prepare_process_ms": prep_wall, "engine": engine,
                        "paths": path_result, "path_process_ms": path_wall,
                        "pipeline_ms": prep_wall + engine["end_to_end_ms"] + path_wall})
    print_table(results)
    save_report(dataset, args.threshold, args.leaf_threshold, results, args.output)


if __name__ == "__main__":
    main()
