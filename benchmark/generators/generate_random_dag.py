"""Generate a TreeBuilder-shaped random DAG benchmark fixture."""

import argparse
import json
import random
from pathlib import Path

import igraph as ig
import numpy as np


UINT32_MAX = np.iinfo(np.uint32).max


def build_csr(
    node_count: int,
    sources: np.ndarray,
    targets: np.ndarray,
) -> tuple[np.ndarray, np.ndarray]:
    """
    Build CSR arrays from directed edges.

    Returns:
        offsets:    uint64[node_count + 1]
        successors: uint32[edge_count]
    """

    # Sort by source first, then target.
    # This also makes each CSR row deterministic.
    order = np.lexsort((targets, sources))

    sorted_sources = sources[order]
    sorted_targets = targets[order]

    degrees = np.bincount(
        sorted_sources,
        minlength=node_count,
    )

    offsets = np.empty(
        node_count + 1,
        dtype="<u8",
    )

    offsets[0] = 0

    np.cumsum(
        degrees,
        dtype=np.uint64,
        out=offsets[1:],
    )

    successors = sorted_targets.astype(
        "<u4",
        copy=False,
    )

    return offsets, successors


def build_arrays(
    nodes: int,
    edges: int,
    seed: int,
):
    """
    Generate a random DAG and convert it into the same graph shape
    expected by TreeBuilder.

    Direction:
        material -> product

    A synthetic __COGS__ node is appended after all generated nodes.
    Every sink product points to __COGS__.
    """

    if nodes <= 0:
        raise ValueError("--nodes must be greater than 0")

    if edges < 0:
        raise ValueError("--edges must be non-negative")

    # IDs are stored as uint32.
    # Generated node IDs: 0 .. nodes - 1
    # __COGS__ ID: nodes
    if nodes > UINT32_MAX:
        raise ValueError(
            f"--nodes must be <= {UINT32_MAX} "
            "because node IDs are stored as uint32"
        )

    max_edges = nodes * (nodes - 1) // 2

    if edges > max_edges:
        raise ValueError(
            f"--edges={edges} exceeds the maximum number "
            f"of simple edges for {nodes} nodes: {max_edges}"
        )

    # igraph uses Python's random generator by default.
    random.seed(seed)

    # ---------------------------------------------------------
    # 1. Generate an undirected random graph.
    # ---------------------------------------------------------

    graph = ig.Graph.Erdos_Renyi(
        n=nodes,
        m=edges,
        directed=False,
        loops=False,
    )

    # ---------------------------------------------------------
    # 2. Convert it into a DAG.
    # ---------------------------------------------------------

    graph.to_directed(mode="acyclic")

    if not graph.is_dag():
        raise RuntimeError("Generated graph is unexpectedly not a DAG")

    # ---------------------------------------------------------
    # 3. Find sinks and terminals.
    #
    # material -> product
    #
    # sink:
    #     out-degree == 0
    #     final products that point to __COGS__
    #
    # terminal:
    #     in-degree == 0
    #     raw-material / initial frontier nodes
    # ---------------------------------------------------------

    in_degrees = np.asarray(
        graph.degree(mode="in"),
        dtype=np.uint64,
    )

    out_degrees = np.asarray(
        graph.degree(mode="out"),
        dtype=np.uint64,
    )

    sinks = np.flatnonzero(
        out_degrees == 0
    ).astype("<u4")

    terminals = np.flatnonzero(
        in_degrees == 0
    ).astype("<u4")

    # ---------------------------------------------------------
    # 4. Extract DAG edges.
    #
    # Original DAG already has the required direction:
    #
    #     material -> product
    # ---------------------------------------------------------

    edge_list = graph.get_edgelist()

    if edge_list:
        edge_array = np.asarray(
            edge_list,
            dtype="<u4",
        )

        sources = edge_array[:, 0]
        targets = edge_array[:, 1]
    else:
        sources = np.empty(0, dtype="<u4")
        targets = np.empty(0, dtype="<u4")

    # ---------------------------------------------------------
    # 5. Add the synthetic __COGS__ node.
    #
    #     sink product -> __COGS__
    # ---------------------------------------------------------

    cogs_node = np.uint32(nodes)

    cogs_sources = sinks

    cogs_targets = np.full(
        len(sinks),
        cogs_node,
        dtype="<u4",
    )

    sources = np.concatenate(
        (sources, cogs_sources)
    )

    targets = np.concatenate(
        (targets, cogs_targets)
    )

    total_nodes = nodes + 1

    # ---------------------------------------------------------
    # 6. Forward CSR
    #
    #     material -> product
    # ---------------------------------------------------------

    offsets, successors = build_csr(
        total_nodes,
        sources,
        targets,
    )

    # ---------------------------------------------------------
    # 7. Reverse CSR
    #
    #     product -> material
    # ---------------------------------------------------------

    reverse_offsets, reverse_successors = build_csr(
        total_nodes,
        targets,
        sources,
    )

    # ---------------------------------------------------------
    # 8. Basic consistency checks
    # ---------------------------------------------------------

    assert len(offsets) == total_nodes + 1
    assert len(reverse_offsets) == total_nodes + 1

    assert offsets[-1] == len(successors)
    assert reverse_offsets[-1] == len(reverse_successors)

    assert len(successors) == len(reverse_successors)

    return (
        offsets,
        successors,
        reverse_offsets,
        reverse_successors,
        terminals,
        len(sinks),
    )


def write_array(
    path: Path,
    array: np.ndarray,
):
    """Write a NumPy array as raw little-endian binary."""

    array.tofile(path)


def main():
    parser = argparse.ArgumentParser(
        description=__doc__
    )

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
        help="Number of randomly generated DAG edges",
    )

    parser.add_argument(
        "--seed",
        type=int,
        default=0,
        help="Random seed",
    )

    parser.add_argument(
        "--output",
        type=Path,
        required=True,
        help="Output dataset directory",
    )

    args = parser.parse_args()

    (
        offsets,
        successors,
        reverse_offsets,
        reverse_successors,
        terminals,
        sink_count,
    ) = build_arrays(
        args.nodes,
        args.edges,
        args.seed,
    )

    args.output.mkdir(
        parents=True,
        exist_ok=True,
    )

    # ---------------------------------------------------------
    # Binary files
    # ---------------------------------------------------------

    write_array(
        args.output / "offsets.bin",
        offsets,
    )

    write_array(
        args.output / "successors.bin",
        successors,
    )

    write_array(
        args.output / "reverse_offsets.bin",
        reverse_offsets,
    )

    write_array(
        args.output / "reverse_successors.bin",
        reverse_successors,
    )

    write_array(
        args.output / "terminals.bin",
        terminals,
    )

    # ---------------------------------------------------------
    # Metadata
    # ---------------------------------------------------------

    metadata = {
        "format_version": 1,

        "generator": (
            "igraph.Erdos_Renyi + "
            "Graph.to_directed(mode='acyclic')"
        ),

        "igraph_version": ig.__version__,

        "seed": args.seed,

        "direction": {
            "successors": "material -> product",
            "reverse_successors": "product -> material",
        },

        "node_ids": {
            "generated": f"0..{args.nodes - 1}",
            "__COGS__": args.nodes,
        },

        "generated_nodes": args.nodes,
        "total_nodes": args.nodes + 1,

        "sampled_edges": args.edges,
        "cogs_edges": sink_count,
        "total_csr_edges": int(len(successors)),

        "sink_count": sink_count,
        "terminal_count": int(len(terminals)),

        "endianness": "little",

        "files": {
            "offsets.bin": {
                "dtype": "uint64",
                "count": int(len(offsets)),
            },
            "successors.bin": {
                "dtype": "uint32",
                "count": int(len(successors)),
            },
            "reverse_offsets.bin": {
                "dtype": "uint64",
                "count": int(len(reverse_offsets)),
            },
            "reverse_successors.bin": {
                "dtype": "uint32",
                "count": int(len(reverse_successors)),
            },
            "terminals.bin": {
                "dtype": "uint32",
                "count": int(len(terminals)),
            },
        },
    }

    (args.output / "metadata.json").write_text(
        json.dumps(
            metadata,
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )

    print("Dataset generated successfully")
    print(f"Output:       {args.output}")
    print(f"Nodes:        {args.nodes + 1:,}")
    print(f"Sample edges: {args.edges:,}")
    print(f"CSR edges:    {len(successors):,}")
    print(f"Terminals:    {len(terminals):,}")
    print(f"Sinks:        {sink_count:,}")
    print(f"__COGS__ ID:  {args.nodes}")


if __name__ == "__main__":
    main()