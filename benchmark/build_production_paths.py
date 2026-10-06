#!/usr/bin/env python3
"""Stream material-to-stop paths from a selected-node frontier result."""

import argparse
import hashlib
import json
import struct
import time
from collections import deque
from pathlib import Path

import numpy as np


def build_paths(dataset, selected_file, output):
    """Follow the same upward edges as the API and write every completed path."""
    started = time.perf_counter_ns()
    offsets = np.fromfile(dataset / "reverse_offsets.bin", dtype="<u4").tolist()
    successors = np.fromfile(dataset / "reverse_successors.bin", dtype="<u4").tolist()
    terminals = np.fromfile(dataset / "terminal_nodes.bin", dtype="<u4").tolist()
    selected = set(np.fromfile(selected_file, dtype="<u4").tolist())
    loaded = time.perf_counter_ns()
    pending = deque((terminal,) for terminal in terminals)
    digest = hashlib.sha256()
    path_count = path_positions = output_bytes = max_length = 0
    with output.open("wb") as file:
        while pending:
            path = pending.popleft()
            node = path[-1]
            for edge in range(offsets[node], offsets[node + 1]):
                parent = successors[edge]
                extended = path + (parent,)
                if parent in selected:
                    record = struct.pack(f"<{len(extended) + 1}I", len(extended), *extended)
                    file.write(record)
                    digest.update(record)
                    path_count += 1
                    path_positions += len(extended)
                    output_bytes += len(record)
                    max_length = max(max_length, len(extended))
                else:
                    pending.append(extended)
    finished = time.perf_counter_ns()
    return {"paths": path_count, "path_positions": path_positions,
            "max_path_length": max_length, "output_bytes": output_bytes,
            "sha256": digest.hexdigest(), "load_ms": (loaded - started) / 1e6,
            "build_and_write_ms": (finished - loaded) / 1e6,
            "total_ms": (finished - started) / 1e6}


def main():
    """Emit a compact report while keeping full path output in a binary file."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--selected", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print("PATH_JSON " + json.dumps(build_paths(args.dataset, args.selected, args.output)))


if __name__ == "__main__":
    main()
