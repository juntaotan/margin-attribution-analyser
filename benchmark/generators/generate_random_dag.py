#!/usr/bin/env python3
"""Generate a large TreeBuilder-shaped random DAG benchmark fixture.

This generator is designed for datasets that are too large for an in-memory
igraph edge list. It generates exactly M unique DAG edges, processes them in
chunks, and stores large arrays as raw little-endian memory-mapped files.

Forward direction:
    product -> material
Reverse direction:
    material -> product

A synthetic __COGS__ node is appended as node ID N and points to every
original root (in-degree == 0).
"""

from __future__ import annotations

import argparse
import json
import math
import shutil
from pathlib import Path

import numpy as np


UINT32_MAX = int(np.iinfo(np.uint32).max)
U4 = np.dtype("<u4")
I8 = np.dtype("<i8")
U1 = np.dtype("u1")

# Odd constants; multiplication modulo 2^k is therefore bijective.
_MIX1 = np.uint64(0xBF58476D1CE4E5B9)
_MIX2 = np.uint64(0x94D049BB133111EB)


def _permute_pow2(values: np.ndarray, mask: int, seed: int) -> np.ndarray:
    """Bijectively mix integers in [0, 2^k) where mask == 2^k - 1."""
    m = np.uint64(mask)
    seed_key = np.uint64((seed ^ 0x9E3779B97F4A7C15) & mask)
    x = np.asarray(values, dtype=np.uint64).copy()

    # Every operation below is invertible modulo 2^k:
    # addition, xor-right-shift, and multiplication by an odd integer.
    with np.errstate(over="ignore"):
        x = (x + seed_key) & m
        x ^= x >> np.uint64(30)
        x = (x * _MIX1) & m
        x ^= x >> np.uint64(27)
        x = (x * _MIX2) & m
        x ^= x >> np.uint64(31)
        x &= m
    return x


def sample_unique_edge_ids(
    start: int,
    count: int,
    possible_edges: int,
    seed: int,
) -> np.ndarray:
    """Return a deterministic, unique pseudo-random subset of edge IDs.

    Cycle-walking turns a permutation over the next power-of-two domain into
    a permutation over [0, possible_edges). Taking distinct input indices
    therefore yields distinct edge IDs without a global hash set.
    """
    if count == 0:
        return np.empty(0, dtype=np.int64)

    if start < 0 or start + count > possible_edges:
        raise ValueError("Requested edge-ID range is outside the DAG edge space")

    bits = max(1, (possible_edges - 1).bit_length())
    mask = (1 << bits) - 1

    x = np.arange(start, start + count, dtype=np.uint64)
    y = _permute_pow2(x, mask, seed)

    limit = np.uint64(possible_edges)
    rejected = y >= limit
    while np.any(rejected):
        y[rejected] = _permute_pow2(y[rejected], mask, seed)
        rejected = y >= limit

    return y.astype(np.int64, copy=False)


def edge_ids_to_pairs(edge_ids: np.ndarray, nodes: int) -> tuple[np.ndarray, np.ndarray]:
    """Map row-major upper-triangle IDs to unique pairs (source, target).

    Every pair satisfies source < target, so the graph is acyclic by
    construction. The possible edge space contains nodes*(nodes-1)/2 pairs.
    """
    edge_ids = np.asarray(edge_ids, dtype=np.int64)
    if edge_ids.size == 0:
        empty = np.empty(0, dtype=U4)
        return empty, empty.copy()

    # Prefix edges before source u:
    #     P(u) = u * (2N - u - 1) / 2
    # Solve P(u) <= edge_id < P(u+1). Float64 gives a fast estimate; integer
    # correction below removes any boundary error from floating-point sqrt.
    b = np.float64(2 * nodes - 1)
    discriminant = b * b - 8.0 * edge_ids.astype(np.float64)
    source = np.floor((b - np.sqrt(discriminant)) / 2.0).astype(np.int64)

    def prefix(u: np.ndarray) -> np.ndarray:
        return (u * (2 * nodes - u - 1)) // 2

    too_high = prefix(source) > edge_ids
    while np.any(too_high):
        source[too_high] -= 1
        too_high = prefix(source) > edge_ids

    too_low = prefix(source + 1) <= edge_ids
    while np.any(too_low):
        source[too_low] += 1
        too_low = prefix(source + 1) <= edge_ids

    target = source + 1 + (edge_ids - prefix(source))

    return source.astype(U4), target.astype(U4)


def iter_edge_chunks(
    nodes: int,
    edges: int,
    seed: int,
    chunk_edges: int,
):
    possible_edges = nodes * (nodes - 1) // 2
    for start in range(0, edges, chunk_edges):
        count = min(chunk_edges, edges - start)
        edge_ids = sample_unique_edge_ids(start, count, possible_edges, seed)
        yield edge_ids_to_pairs(edge_ids, nodes)


def update_degree_counts(degrees: np.memmap, keys: np.ndarray) -> None:
    """Add one degree for every key without allocating a node_count-sized bincount."""
    unique, counts = np.unique(keys, return_counts=True)
    degrees[unique] = degrees[unique] + counts.astype(np.uint32, copy=False)


def build_offsets(
    degrees: np.memmap,
    output_path: Path,
    chunk_nodes: int,
) -> tuple[np.memmap, int]:
    """Build uint32 CSR offsets from disk-backed uint32 degree counts."""
    node_count = len(degrees)
    offsets = np.memmap(output_path, dtype=U4, mode="w+", shape=(node_count + 1,))
    offsets[0] = 0

    carry = np.uint64(0)
    for start in range(0, node_count, chunk_nodes):
        end = min(start + chunk_nodes, node_count)
        block = np.asarray(degrees[start:end], dtype=np.uint64)
        cumulative = np.cumsum(block, dtype=np.uint64)
        cumulative += carry

        if cumulative.size and int(cumulative[-1]) > UINT32_MAX:
            raise ValueError("CSR offsets exceed uint32 capacity")

        offsets[start + 1 : end + 1] = cumulative.astype(U4, copy=False)
        if cumulative.size:
            carry = cumulative[-1]

    offsets.flush()
    return offsets, int(carry)


def copy_memmap_chunked(
    source: np.memmap,
    destination: np.memmap,
    count: int,
    chunk_nodes: int,
) -> None:
    for start in range(0, count, chunk_nodes):
        end = min(start + chunk_nodes, count)
        destination[start:end] = source[start:end]


def scatter_by_key(
    output: np.memmap,
    cursor: np.memmap,
    keys: np.ndarray,
    values: np.ndarray,
) -> None:
    """Append values into CSR rows selected by keys.

    Only one edge chunk is sorted at a time. cursor[node] stores the next free
    slot in that node's row.
    """
    n = len(keys)
    if n == 0:
        return

    order = np.argsort(keys, kind="stable")
    sorted_keys = keys[order]
    sorted_values = values[order]

    starts = np.empty(n, dtype=np.bool_)
    starts[0] = True
    starts[1:] = sorted_keys[1:] != sorted_keys[:-1]
    start_indices = np.flatnonzero(starts)
    unique_keys = sorted_keys[start_indices]

    ends = np.empty_like(start_indices)
    if len(start_indices) > 1:
        ends[:-1] = start_indices[1:]
    ends[-1] = n
    counts = ends - start_indices

    # Repeat each row's current base and group start only within this chunk.
    bases = np.repeat(cursor[unique_keys], counts).astype(np.uint32, copy=False)
    repeated_starts = np.repeat(start_indices.astype(np.uint32, copy=False), counts)
    local = np.arange(n, dtype=np.uint32) - repeated_starts
    positions = bases + local

    output[positions] = sorted_values
    cursor[unique_keys] = cursor[unique_keys] + counts.astype(np.uint32, copy=False)


def append_zero_degree_nodes(
    degrees: np.memmap,
    count_nodes: int,
    output_path: Path,
    chunk_nodes: int,
) -> int:
    """Write node IDs whose degree is zero as raw uint32 and return the count."""
    total = 0
    with output_path.open("wb") as handle:
        for start in range(0, count_nodes, chunk_nodes):
            end = min(start + chunk_nodes, count_nodes)
            block = np.asarray(degrees[start:end])
            ids = np.flatnonzero(block == 0).astype(np.uint32, copy=False)
            ids += np.uint32(start)
            ids.astype(U4, copy=False).tofile(handle)
            total += len(ids)
    return total


def write_node_attributes(
    output_dir: Path,
    nodes: int,
    seed: int,
    comparable_ratio: float,
    value_min: int,
    value_max: int,
    chunk_nodes: int,
) -> int:
    total_nodes = nodes + 1
    rng = np.random.default_rng(seed)

    values = np.memmap(
        output_dir / "node_values.bin",
        dtype=I8,
        mode="w+",
        shape=(total_nodes,),
    )
    for start in range(0, nodes, chunk_nodes):
        end = min(start + chunk_nodes, nodes)
        values[start:end] = rng.integers(
            low=value_min,
            high=value_max + 1,
            size=end - start,
            dtype=np.int64,
        )
    values[nodes] = 0
    values.flush()
    del values

    comparable = np.memmap(
        output_dir / "node_comparable.bin",
        dtype=U1,
        mode="w+",
        shape=(total_nodes,),
    )
    comparable_count = 0
    for start in range(0, nodes, chunk_nodes):
        end = min(start + chunk_nodes, nodes)
        block = (rng.random(end - start) < comparable_ratio).astype(np.uint8)
        comparable[start:end] = block
        comparable_count += int(np.count_nonzero(block))
    comparable[nodes] = 1
    comparable_count += 1
    comparable.flush()
    del comparable

    return comparable_count


def build_large_dataset(
    output_dir: Path,
    nodes: int,
    edges: int,
    seed: int,
    comparable_ratio: float,
    value_min: int,
    value_max: int,
    chunk_edges: int,
    chunk_nodes: int,
) -> dict:
    if nodes <= 0:
        raise ValueError("--nodes must be greater than 0")
    if nodes + 1 > UINT32_MAX:
        raise ValueError(f"Total nodes including __COGS__ must be <= {UINT32_MAX}")
    if edges < 0:
        raise ValueError("--edges must be non-negative")
    if chunk_edges <= 0 or chunk_nodes <= 0:
        raise ValueError("chunk sizes must be positive")
    if not 0.0 <= comparable_ratio <= 1.0:
        raise ValueError("--comparable-ratio must be between 0 and 1")
    if value_min > value_max:
        raise ValueError("--value-min must be <= --value-max")

    possible_edges = nodes * (nodes - 1) // 2
    if edges > possible_edges:
        raise ValueError(
            f"--edges={edges} exceeds the maximum simple DAG edge count "
            f"for {nodes} nodes: {possible_edges}"
        )

    output_dir.mkdir(parents=True, exist_ok=True)
    tmp_dir = output_dir / ".generator_tmp"
    if tmp_dir.exists():
        shutil.rmtree(tmp_dir)
    tmp_dir.mkdir()

    # Remove legacy terminal filename if present.
    old_terminals = output_dir / "terminals.bin"
    if old_terminals.exists():
        old_terminals.unlink()

    total_nodes = nodes + 1
    cogs_node = nodes

    out_degree = np.memmap(
        tmp_dir / "out_degree.bin", dtype=U4, mode="w+", shape=(total_nodes,)
    )
    in_degree = np.memmap(
        tmp_dir / "in_degree.bin", dtype=U4, mode="w+", shape=(total_nodes,)
    )
    out_degree[:] = 0
    in_degree[:] = 0

    print("Pass 1/4: counting forward/reverse degrees...")
    chunk_total = math.ceil(edges / chunk_edges) if edges else 0
    for chunk_index, (sources, targets) in enumerate(
        iter_edge_chunks(nodes, edges, seed, chunk_edges), start=1
    ):
        update_degree_counts(out_degree, sources)
        update_degree_counts(in_degree, targets)
        if chunk_index == 1 or chunk_index % 10 == 0 or chunk_index == chunk_total:
            print(f"  degree chunks: {chunk_index}/{chunk_total}", flush=True)

    out_degree.flush()
    in_degree.flush()

    roots_path = tmp_dir / "roots.bin"
    root_count = append_zero_degree_nodes(
        in_degree, nodes, roots_path, chunk_nodes
    )
    terminal_count = append_zero_degree_nodes(
        out_degree,
        nodes,
        output_dir / "terminal_nodes.bin",
        chunk_nodes,
    )

    if edges + root_count > UINT32_MAX:
        raise ValueError("Total CSR edge count exceeds uint32 capacity")

    roots = np.memmap(roots_path, dtype=U4, mode="r", shape=(root_count,))

    # Add __COGS__ -> roots.
    out_degree[cogs_node] = np.uint32(root_count)
    for start in range(0, root_count, chunk_nodes):
        end = min(start + chunk_nodes, root_count)
        in_degree[roots[start:end]] = 1
    out_degree.flush()
    in_degree.flush()

    print("Pass 2/4: building CSR offsets...")
    offsets, forward_total = build_offsets(
        out_degree, output_dir / "offsets.bin", chunk_nodes
    )
    reverse_offsets, reverse_total = build_offsets(
        in_degree, output_dir / "reverse_offsets.bin", chunk_nodes
    )

    expected_total = edges + root_count
    if forward_total != expected_total or reverse_total != expected_total:
        raise RuntimeError(
            f"CSR edge total mismatch: forward={forward_total}, "
            f"reverse={reverse_total}, expected={expected_total}"
        )

    print("Pass 3/4: filling CSR successors in chunks...")
    successors = np.memmap(
        output_dir / "successors.bin", dtype=U4, mode="w+", shape=(expected_total,)
    )
    reverse_successors = np.memmap(
        output_dir / "reverse_successors.bin",
        dtype=U4,
        mode="w+",
        shape=(expected_total,),
    )

    forward_cursor = np.memmap(
        tmp_dir / "forward_cursor.bin", dtype=U4, mode="w+", shape=(total_nodes,)
    )
    reverse_cursor = np.memmap(
        tmp_dir / "reverse_cursor.bin", dtype=U4, mode="w+", shape=(total_nodes,)
    )
    copy_memmap_chunked(offsets, forward_cursor, total_nodes, chunk_nodes)
    copy_memmap_chunked(reverse_offsets, reverse_cursor, total_nodes, chunk_nodes)

    for chunk_index, (sources, targets) in enumerate(
        iter_edge_chunks(nodes, edges, seed, chunk_edges), start=1
    ):
        scatter_by_key(successors, forward_cursor, sources, targets)
        scatter_by_key(reverse_successors, reverse_cursor, targets, sources)
        if chunk_index == 1 or chunk_index % 10 == 0 or chunk_index == chunk_total:
            print(f"  CSR chunks:    {chunk_index}/{chunk_total}", flush=True)

    # Forward COGS row is the final row and contains every original root.
    cogs_begin = int(offsets[cogs_node])
    cogs_end = int(offsets[cogs_node + 1])
    if cogs_end - cogs_begin != root_count:
        raise RuntimeError("__COGS__ forward row length mismatch")
    successors[cogs_begin:cogs_end] = roots

    # Each original root had zero incoming edges; after adding COGS it has one
    # reverse successor, namely the COGS node.
    for start in range(0, root_count, chunk_nodes):
        end = min(start + chunk_nodes, root_count)
        root_block = roots[start:end]
        positions = reverse_offsets[root_block]
        reverse_successors[positions] = np.uint32(cogs_node)

    successors.flush()
    reverse_successors.flush()

    print("Pass 4/4: generating node values/comparable flags...")
    comparable_count = write_node_attributes(
        output_dir,
        nodes,
        seed,
        comparable_ratio,
        value_min,
        value_max,
        chunk_nodes,
    )

    # Basic structural checks that do not materialize the whole graph.
    if int(offsets[-1]) != expected_total:
        raise RuntimeError("Forward offsets do not match successor count")
    if int(reverse_offsets[-1]) != expected_total:
        raise RuntimeError("Reverse offsets do not match successor count")

    metadata = {
        "format_version": 3,
        "generator": "chunked unique upper-triangle DAG edge permutation",
        "seed": seed,
        "chunk_edges": chunk_edges,
        "chunk_nodes": chunk_nodes,
        "direction": {
            "forward": "product -> material",
            "reverse": "material -> product",
        },
        "node_ids": {
            "generated": f"0..{nodes - 1}",
            "__COGS__": nodes,
        },
        "generated_nodes": nodes,
        "total_nodes": total_nodes,
        "sampled_edges": edges,
        "cogs_edges": root_count,
        "total_csr_edges": expected_total,
        "root_count": root_count,
        "terminal_count": terminal_count,
        "comparable_ratio_requested": comparable_ratio,
        "comparable_nodes": comparable_count,
        "node_value_range": {
            "min": value_min,
            "max": value_max,
        },
        "endianness": "little",
        "files": {
            "offsets.bin": {
                "dtype": "uint32",
                "count": total_nodes + 1,
                "role": "forward CSR offsets",
            },
            "successors.bin": {
                "dtype": "uint32",
                "count": expected_total,
                "role": "auxiliary forward CSR successors",
            },
            "reverse_offsets.bin": {
                "dtype": "uint32",
                "count": total_nodes + 1,
                "role": "reverse CSR offsets",
            },
            "reverse_successors.bin": {
                "dtype": "uint32",
                "count": expected_total,
                "role": "reverse CSR successors",
            },
            "terminal_nodes.bin": {
                "dtype": "uint32",
                "count": terminal_count,
                "role": "initial upward traversal frontier",
            },
            "node_values.bin": {
                "dtype": "int64",
                "count": total_nodes,
            },
            "node_comparable.bin": {
                "dtype": "uint8",
                "count": total_nodes,
            },
        },
    }

    (output_dir / "metadata.json").write_text(
        json.dumps(metadata, indent=2) + "\n", encoding="utf-8"
    )

    # Release mappings before deleting temporary files.
    del forward_cursor, reverse_cursor
    del successors, reverse_successors
    del offsets, reverse_offsets
    del roots
    del out_degree, in_degree
    shutil.rmtree(tmp_dir)

    return metadata


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--nodes",
        type=int,
        required=True,
        help="Number of generated DAG nodes, excluding __COGS__",
    )
    parser.add_argument(
        "--edges",
        type=int,
        required=True,
        help="Number of unique randomly distributed DAG edges",
    )
    parser.add_argument("--seed", type=int, default=0, help="Random seed")
    parser.add_argument(
        "--comparable-ratio",
        type=float,
        default=0.8,
        help="Probability a generated node is comparable (default: 0.8)",
    )
    parser.add_argument("--value-min", type=int, default=-1_000_000)
    parser.add_argument("--value-max", type=int, default=1_000_000)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--chunk-edges",
        type=int,
        default=2_000_000,
        help="Edges processed per in-memory chunk (default: 2,000,000)",
    )
    parser.add_argument(
        "--chunk-nodes",
        type=int,
        default=2_000_000,
        help="Nodes processed per sequential chunk (default: 2,000,000)",
    )
    args = parser.parse_args()

    metadata = build_large_dataset(
        output_dir=args.output,
        nodes=args.nodes,
        edges=args.edges,
        seed=args.seed,
        comparable_ratio=args.comparable_ratio,
        value_min=args.value_min,
        value_max=args.value_max,
        chunk_edges=args.chunk_edges,
        chunk_nodes=args.chunk_nodes,
    )

    print("Dataset generated successfully")
    print(f"Output:        {args.output}")
    print(f"Nodes:         {metadata['total_nodes']:,}")
    print(f"Sample edges:  {metadata['sampled_edges']:,}")
    print(f"CSR edges:     {metadata['total_csr_edges']:,}")
    print(f"Roots:         {metadata['root_count']:,}")
    print(f"Terminals:     {metadata['terminal_count']:,}")
    print(
        f"Comparable:    {metadata['comparable_nodes']:,}/"
        f"{metadata['total_nodes']:,}"
    )
    print(f"__COGS__ ID:   {args.nodes}")


if __name__ == "__main__":
    main()
