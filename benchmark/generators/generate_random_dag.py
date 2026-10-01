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
        offsets:    uint32[node_count + 1]
        successors: uint32[edge_count]
    """

    if len(sources) != len(targets):
        raise ValueError("sources and targets must have the same length")

    if len(sources) > UINT32_MAX:
        raise ValueError(
            "CSR edge count exceeds uint32 capacity"
        )

    # Sort by source first, then target.
    # This makes each CSR row deterministic.
    order = np.lexsort((targets, sources))

    sorted_sources = sources[order]
    sorted_targets = targets[order]

    degrees = np.bincount(
        sorted_sources,
        minlength=node_count,
    )

    # Calculate with uint64 first so overflow can be detected
    # before converting to uint32.
    offsets_u64 = np.empty(
        node_count + 1,
        dtype=np.uint64,
    )

    offsets_u64[0] = 0

    np.cumsum(
        degrees,
        dtype=np.uint64,
        out=offsets_u64[1:],
    )

    if offsets_u64[-1] > UINT32_MAX:
        raise ValueError(
            "CSR offsets exceed uint32 capacity"
        )

    offsets = offsets_u64.astype("<u4")

    successors = sorted_targets.astype(
        "<u4",
        copy=False,
    )

    return offsets, successors


def build_arrays(
    nodes: int,
    edges: int,
    seed: int,
    comparable_ratio: float,
    value_min: int,
    value_max: int,
):
    """
    Generate a random DAG matching BomUpwardGraph.

    Forward CSR:
        product -> materials

    Reverse CSR:
        material -> products

    terminal_nodes:
        Nodes with no outgoing material edges.

    A synthetic __COGS__ node is added as a super-root:

        __COGS__ -> top-level products

    Therefore reverse upward traversal can propagate:

        terminal material
            -> product
            -> higher-level product
            -> __COGS__
    """

    if nodes <= 0:
        raise ValueError(
            "--nodes must be greater than 0"
        )

    if nodes > UINT32_MAX:
        raise ValueError(
            f"--nodes must be <= {UINT32_MAX}"
        )

    if edges < 0:
        raise ValueError(
            "--edges must be non-negative"
        )

    max_edges = nodes * (nodes - 1) // 2

    if edges > max_edges:
        raise ValueError(
            f"--edges={edges} exceeds the maximum "
            f"number of simple DAG edges for "
            f"{nodes} nodes: {max_edges}"
        )

    if not 0.0 <= comparable_ratio <= 1.0:
        raise ValueError(
            "--comparable-ratio must be between 0 and 1"
        )

    if value_min > value_max:
        raise ValueError(
            "--value-min must be <= --value-max"
        )

    # ---------------------------------------------------------
    # 1. Reproducible random generators
    # ---------------------------------------------------------

    ig.set_random_number_generator(
        random.Random(seed)
    )

    rng = np.random.default_rng(seed)

    # ---------------------------------------------------------
    # 2. Generate random undirected graph
    # ---------------------------------------------------------

    graph = ig.Graph.Erdos_Renyi(
        n=nodes,
        m=edges,
        directed=False,
        loops=False,
    )

    # ---------------------------------------------------------
    # 3. Convert to DAG
    #
    # We interpret the resulting direction as:
    #
    #     product -> material
    #
    # ---------------------------------------------------------

    graph.to_directed(mode="acyclic")

    if not graph.is_dag():
        raise RuntimeError(
            "Generated graph is unexpectedly not a DAG"
        )

    # ---------------------------------------------------------
    # 4. Identify roots and terminals
    #
    # Forward direction:
    #
    #     product -> material
    #
    # root:
    #     in-degree == 0
    #     top-level / sold product
    #
    # terminal:
    #     out-degree == 0
    #     raw material / initial upward frontier
    # ---------------------------------------------------------

    in_degrees = np.asarray(
        graph.degree(mode="in"),
        dtype=np.uint64,
    )

    out_degrees = np.asarray(
        graph.degree(mode="out"),
        dtype=np.uint64,
    )

    roots = np.flatnonzero(
        in_degrees == 0
    ).astype("<u4")

    terminal_nodes = np.flatnonzero(
        out_degrees == 0
    ).astype("<u4")

    # ---------------------------------------------------------
    # 5. Extract original DAG edges
    #
    #     product -> material
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
        sources = np.empty(
            0,
            dtype="<u4",
        )

        targets = np.empty(
            0,
            dtype="<u4",
        )

    # ---------------------------------------------------------
    # 6. Add synthetic __COGS__ node
    #
    # Forward direction:
    #
    #     __COGS__ -> top-level products
    #
    # Reverse direction therefore becomes:
    #
    #     top-level product -> __COGS__
    #
    # which allows upward propagation to reach __COGS__.
    # ---------------------------------------------------------

    cogs_node = np.uint32(nodes)

    cogs_sources = np.full(
        len(roots),
        cogs_node,
        dtype="<u4",
    )

    cogs_targets = roots

    sources = np.concatenate(
        (sources, cogs_sources)
    )

    targets = np.concatenate(
        (targets, cogs_targets)
    )

    total_nodes = nodes + 1
    total_edges = len(sources)

    if total_edges > UINT32_MAX:
        raise ValueError(
            "Total CSR edge count exceeds uint32 capacity"
        )

    # ---------------------------------------------------------
    # 7. Forward CSR
    #
    #     Product -> materials
    #
    # offsets is required by BomUpwardGraph.
    # successors is retained as auxiliary benchmark data.
    # ---------------------------------------------------------

    offsets, successors = build_csr(
        total_nodes,
        sources,
        targets,
    )

    # ---------------------------------------------------------
    # 8. Reverse CSR
    #
    #     Material -> products
    #
    # This is the graph used by upward traversal.
    # ---------------------------------------------------------

    reverse_offsets, reverse_successors = build_csr(
        total_nodes,
        targets,
        sources,
    )

    # ---------------------------------------------------------
    # 9. Node values
    #
    # int64_t / cl_long
    # ---------------------------------------------------------

    generated_values = rng.integers(
        low=value_min,
        high=value_max + 1,
        size=nodes,
        dtype=np.int64,
    )

    # __COGS__ starts at zero.
    node_values = np.concatenate(
        (
            generated_values.astype("<i8"),
            np.array([0], dtype="<i8"),
        )
    )

    # ---------------------------------------------------------
    # 10. Comparable flags
    #
    # uint8_t / cl_uchar
    #
    # 1 = node exists in both DAGs
    # 0 = node exists in only one DAG
    # ---------------------------------------------------------

    generated_comparable = (
        rng.random(nodes) < comparable_ratio
    ).astype("u1")

    # __COGS__ is always comparable.
    node_comparable = np.concatenate(
        (
            generated_comparable,
            np.array([1], dtype="u1"),
        )
    )

    # ---------------------------------------------------------
    # 11. Consistency checks
    # ---------------------------------------------------------

    assert offsets.dtype == np.dtype("<u4")
    assert reverse_offsets.dtype == np.dtype("<u4")

    assert successors.dtype == np.dtype("<u4")
    assert reverse_successors.dtype == np.dtype("<u4")
    assert terminal_nodes.dtype == np.dtype("<u4")

    assert node_values.dtype == np.dtype("<i8")
    assert node_comparable.dtype == np.dtype("u1")

    assert len(offsets) == total_nodes + 1
    assert len(reverse_offsets) == total_nodes + 1

    assert offsets[-1] == len(successors)

    assert (
        reverse_offsets[-1]
        == len(reverse_successors)
    )

    assert (
        len(successors)
        == len(reverse_successors)
    )

    assert len(node_values) == total_nodes
    assert len(node_comparable) == total_nodes

    # Every terminal must have no children/materials
    # in forward CSR.
    for terminal in terminal_nodes:
        terminal = int(terminal)

        assert (
            offsets[terminal]
            == offsets[terminal + 1]
        )

    # __COGS__ must point to every original root.
    assert (
        offsets[int(cogs_node) + 1]
        - offsets[int(cogs_node)]
        == len(roots)
    )

    return (
        offsets,
        successors,
        reverse_offsets,
        reverse_successors,
        terminal_nodes,
        node_values,
        node_comparable,
        len(roots),
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
        help=(
            "Number of generated DAG nodes, "
            "excluding __COGS__"
        ),
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
        "--comparable-ratio",
        type=float,
        default=0.8,
        help=(
            "Probability that a generated node is "
            "marked comparable (default: 0.8)"
        ),
    )

    parser.add_argument(
        "--value-min",
        type=int,
        default=-1_000_000,
        help="Minimum generated node value",
    )

    parser.add_argument(
        "--value-max",
        type=int,
        default=1_000_000,
        help="Maximum generated node value",
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
        terminal_nodes,
        node_values,
        node_comparable,
        root_count,
    ) = build_arrays(
        nodes=args.nodes,
        edges=args.edges,
        seed=args.seed,
        comparable_ratio=args.comparable_ratio,
        value_min=args.value_min,
        value_max=args.value_max,
    )

    args.output.mkdir(
        parents=True,
        exist_ok=True,
    )

    # Remove the old filename produced by the
    # previous version of this generator.
    old_terminals_file = (
        args.output / "terminals.bin"
    )

    if old_terminals_file.exists():
        old_terminals_file.unlink()

    # ---------------------------------------------------------
    # Binary files
    # ---------------------------------------------------------

    write_array(
        args.output / "offsets.bin",
        offsets,
    )

    # Auxiliary forward successors.
    # Current CUDA/OpenCL BomUpwardGraph does not require it,
    # but retaining it makes the fixture fully reconstructable.
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
        args.output / "terminal_nodes.bin",
        terminal_nodes,
    )

    write_array(
        args.output / "node_values.bin",
        node_values,
    )

    write_array(
        args.output / "node_comparable.bin",
        node_comparable,
    )

    # ---------------------------------------------------------
    # Metadata
    # ---------------------------------------------------------

    comparable_count = int(
        np.count_nonzero(node_comparable)
    )

    metadata = {
        "format_version": 2,

        "generator": (
            "igraph.Erdos_Renyi + "
            "Graph.to_directed(mode='acyclic')"
        ),

        "igraph_version": ig.__version__,

        "seed": args.seed,

        "direction": {
            "forward": "product -> material",
            "reverse": "material -> product",
        },

        "node_ids": {
            "generated": f"0..{args.nodes - 1}",
            "__COGS__": args.nodes,
        },

        "generated_nodes": args.nodes,
        "total_nodes": args.nodes + 1,

        "sampled_edges": args.edges,
        "cogs_edges": root_count,
        "total_csr_edges": int(len(successors)),

        "root_count": root_count,
        "terminal_count": int(
            len(terminal_nodes)
        ),

        "comparable_ratio_requested": (
            args.comparable_ratio
        ),

        "comparable_nodes": comparable_count,

        "node_value_range": {
            "min": args.value_min,
            "max": args.value_max,
        },

        "endianness": "little",

        "files": {
            "offsets.bin": {
                "dtype": "uint32",
                "count": int(len(offsets)),
                "role": "forward CSR offsets",
            },

            "successors.bin": {
                "dtype": "uint32",
                "count": int(len(successors)),
                "role": (
                    "auxiliary forward CSR successors"
                ),
            },

            "reverse_offsets.bin": {
                "dtype": "uint32",
                "count": int(
                    len(reverse_offsets)
                ),
                "role": "reverse CSR offsets",
            },

            "reverse_successors.bin": {
                "dtype": "uint32",
                "count": int(
                    len(reverse_successors)
                ),
                "role": (
                    "reverse CSR successors"
                ),
            },

            "terminal_nodes.bin": {
                "dtype": "uint32",
                "count": int(
                    len(terminal_nodes)
                ),
                "role": (
                    "initial upward traversal frontier"
                ),
            },

            "node_values.bin": {
                "dtype": "int64",
                "count": int(
                    len(node_values)
                ),
            },

            "node_comparable.bin": {
                "dtype": "uint8",
                "count": int(
                    len(node_comparable)
                ),
            },
        },
    }

    (
        args.output / "metadata.json"
    ).write_text(
        json.dumps(
            metadata,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )

    # ---------------------------------------------------------
    # Summary
    # ---------------------------------------------------------

    print("Dataset generated successfully")
    print(f"Output:        {args.output}")
    print(f"Nodes:         {args.nodes + 1:,}")
    print(f"Sample edges:  {args.edges:,}")
    print(f"CSR edges:     {len(successors):,}")
    print(f"Roots:         {root_count:,}")
    print(
        f"Terminals:     "
        f"{len(terminal_nodes):,}"
    )
    print(
        f"Comparable:    "
        f"{comparable_count:,}"
        f"/{args.nodes + 1:,}"
    )
    print(f"__COGS__ ID:   {args.nodes}")


if __name__ == "__main__":
    main()