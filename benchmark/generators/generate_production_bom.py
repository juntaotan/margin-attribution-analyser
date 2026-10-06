#!/usr/bin/env python3
"""Generate two layered BOM periods, actual issue records, and frontier arrays."""

import argparse
import csv
import json
import math
from functools import lru_cache
from pathlib import Path

import numpy as np


def make_boms(rng, pools, changes):
    """Build the standard DAG, then replace half the semi branches in chosen products."""
    standard = {}
    for level, pool in enumerate(pools[:7]):
        for parent in pool:
            semis = [] if level == 6 else rng.choice(
                pools[level + 1], int(rng.integers(2, 5)), replace=False).tolist()
            materials = rng.choice(pools[7], int(rng.integers(1, 3)), replace=False).tolist()
            standard[parent] = [(int(child), int(rng.integers(1, 4))) for child in semis + materials]
    # Attach otherwise-unused inventory codes to reachable parents at their
    # preceding layer, so the graph really contains the requested inventory.
    for level in range(1, 8):
        active = reachable(pools[0], standard)
        parents = [node for node in pools[level - 1] if node in active]
        for position, node in enumerate(pools[level]):
            if node not in active:
                standard[parents[position % len(parents)]].append((node, 1))
    actual = {node: edges.copy() for node, edges in standard.items()}
    next_new_id = pools[-1][-1] + 1
    replacements = []
    for product in rng.choice(pools[0], changes, replace=False):
        level = int(rng.integers(1, 5))  # Human layers 2 through 5.
        parent = int(product)
        for _ in range(level - 1):
            parent = next(child for child, _ in actual[parent] if child in actual)
        semi_children = [child for child, _ in actual[parent] if child in actual]
        chosen = rng.choice(semi_children, size=math.ceil(len(semi_children) / 2), replace=False)
        cloned = {}

        def clone(node):
            """Give every semi in the replacement subtree a period-specific ID."""
            nonlocal next_new_id
            if node not in actual:
                return node
            if node not in cloned:
                replacement_id = next_new_id
                next_new_id += 1
                cloned[node] = replacement_id
                actual[replacement_id] = [(clone(child), q) for child, q in actual[node]]
            return cloned[node]

        substitutions = {int(old): clone(int(old)) for old in chosen}
        # A changed recipe must change consumption, not merely rename nodes.
        for semi in cloned.values():
            if all(child not in actual for child, _ in actual[semi]):
                child, quantity = actual[semi][0]
                actual[semi][0] = (child, quantity + 1)
        actual[parent] = [(substitutions.get(child, child), q) for child, q in actual[parent]]
        replacements.extend({"product": int(product), "layer": level + 1,
                             "old_semi": old, "new_semi": new}
                            for old, new in substitutions.items())
    actual_active = reachable(pools[0], actual)
    stable_bottom = [node for node in pools[6] if node in actual_active]
    for position, material in enumerate(pools[7]):
        if material not in actual_active:
            actual[stable_bottom[position % len(stable_bottom)]].append((material, 1))
    return standard, actual, replacements


def reachable(roots, graph):
    """Find nodes used by one period, omitting replaced standard branches."""
    found = set(roots)
    pending = list(roots)
    while pending:
        for child, _ in graph.get(pending.pop(), ()):
            if child not in found:
                found.add(child)
                pending.append(child)
    return found


def calculate(roots, graph, active, prices, orders):
    """Roll per-unit cost upward and issued quantities downward."""
    @lru_cache(None)
    def unit(node):
        return sum(unit(child) * quantity for child, quantity in graph[node]) \
            if node in graph else prices[node]

    demand = dict.fromkeys(active, 0)
    incoming = dict.fromkeys(active, 0)
    for parent in active:
        for child, _ in graph.get(parent, ()):
            incoming[child] += 1
    for root in roots:
        demand[root] += orders
    pending = list(roots)
    usage = 0
    while pending:
        parent = pending.pop()
        for child, quantity in graph.get(parent, ()):
            demand[child] += demand[parent] * quantity
            usage += demand[parent]
            incoming[child] -= 1
            if incoming[child] == 0:
                pending.append(child)
    return {node: unit(node) * demand[node] * 1_000_000 for node in active}, demand, usage


def write_bom(path, graph, active):
    """Write the database BOM's product, material, and usage columns."""
    with path.open("w", newline="") as file:
        writer = csv.writer(file)
        writer.writerow(["bom_no", "product_no", "material_no", "material_usage"])
        for parent in sorted(active):
            for child, quantity in graph.get(parent, ()):
                writer.writerow([f"BOM_{parent}", f"I{parent}", f"I{child}", f"{quantity}.000000"])


def write_usage(path, graph, active, demand):
    """Write one little-endian parent/material ID pair per actual issue event."""
    with path.open("wb") as file:
        for parent in sorted(active):
            for child, _ in graph.get(parent, ()):
                pair = np.array([parent, child], dtype="<u4")
                count = demand[parent]
                while count:
                    block = min(count, 100_000)
                    np.broadcast_to(pair, (block, 2)).tofile(file)
                    count -= block


def write_fixture(output, actual, actual_cost, standard_cost, leaf_threshold):
    """Match BomGraphPreparer's comparable leaves and active-child counts."""
    nodes = sorted(actual_cost)
    index = {node: position for position, node in enumerate(nodes)}
    parents = [[] for _ in nodes]
    for parent, edges in actual.items():
        if parent in index:
            for child, _ in edges:
                parents[index[child]].append(index[parent])
    flags = np.array([node in standard_cost for node in nodes], dtype="u1")
    differences = [actual_cost[node] - standard_cost[node] if node in standard_cost else 0 for node in nodes]
    if any(abs(value) > np.iinfo(np.int64).max for value in differences):
        raise ValueError("Cost difference exceeds signed int64 micro-units")
    terminals = [position for position, node in enumerate(nodes) if node not in actual
                 and flags[position] and abs(differences[position]) >= leaf_threshold]
    active = set(terminals)
    pending = terminals.copy()
    while pending:
        for parent in parents[pending.pop()]:
            if parent not in active:
                active.add(parent)
                pending.append(parent)
    children = [[] for _ in nodes]
    for child in active:
        for parent in parents[child]:
            children[parent].append(child)
    active_parents = [row if child in active else [] for child, row in enumerate(parents)]
    arrays = {}
    for prefix, rows in (("", children), ("reverse_", active_parents)):
        offsets = [0]
        successors = []
        for row in rows:
            successors.extend(row)
            offsets.append(len(successors))
        arrays[prefix + "offsets"] = np.array(offsets, dtype="<u4")
        arrays[prefix + "successors"] = np.array(successors, dtype="<u4")
    arrays.update(terminal_nodes=np.array(terminals, dtype="<u4"),
                  node_values=np.array(differences, dtype="<i8"), node_comparable=flags)
    for name, array in arrays.items():
        array.tofile(output / f"{name}.bin")
    return nodes, arrays, len(active)


def main():
    """Build approximately 100 products, 9000 materials, and over 10M issues."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--products", type=int, default=100)
    parser.add_argument("--materials", type=int, default=9000)
    parser.add_argument("--changed-products", type=int, default=None,
                        help="Products with replaced branches; defaults to all products")
    parser.add_argument("--min-usage-records", type=int, default=10_000_001)
    parser.add_argument("--leaf-threshold", type=int, default=1)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()
    if args.changed_products is None:
        args.changed_products = args.products
    if args.output.exists() and any(args.output.iterdir()):
        parser.error("Output directory must be empty")
    if not (0 < args.changed_products <= args.products and args.materials >= 2
            and args.min_usage_records > 0 and args.leaf_threshold >= 0):
        parser.error("Invalid inventory, replacement, usage, or threshold count")
    rng = np.random.default_rng(args.seed)
    ratio = (args.materials / args.products) ** (1 / 7)
    sizes = [round(args.products * ratio**level) for level in range(8)]
    sizes[0], sizes[-1] = args.products, args.materials
    pools, cursor = [], 0
    for size in sizes:
        pools.append(list(range(cursor, cursor + size)))
        cursor += size
    prices = {node: int(rng.integers(10, 101)) for node in pools[7]}
    args.output.mkdir(parents=True, exist_ok=True)
    with (args.output / "material_prices.csv").open("w", newline="") as file:
        writer = csv.writer(file)
        writer.writerow(["inventory_id", "unit_price_micro"])
        writer.writerows((f"I{node}", price * 1_000_000) for node, price in prices.items())
    standard, actual, replacements = make_boms(rng, pools, args.changed_products)
    standard_nodes = reachable(pools[0], standard)
    actual_nodes = reachable(pools[0], actual)
    _, _, per_order = calculate(pools[0], actual, actual_nodes, prices, 1)
    orders = math.ceil(args.min_usage_records / per_order)
    standard_cost, _, standard_usage = calculate(pools[0], standard, standard_nodes, prices, orders)
    actual_cost, demand, actual_usage = calculate(pools[0], actual, actual_nodes, prices, orders)
    write_bom(args.output / "standard_bom.csv", standard, standard_nodes)
    write_bom(args.output / "actual_bom.csv", actual, actual_nodes)
    write_usage(args.output / "inventory_usage.bin", actual, actual_nodes, demand)
    cogs = max(actual_cost.keys() | standard_cost.keys()) + 1
    actual[cogs] = [(product, 1) for product in pools[0]]
    actual_cost[cogs] = sum(actual_cost[product] for product in pools[0])
    standard_cost[cogs] = sum(standard_cost[product] for product in pools[0])
    nodes, arrays, active_count = write_fixture(args.output, actual, actual_cost,
                                               standard_cost, args.leaf_threshold)
    with (args.output / "node_costs.csv").open("w", newline="") as file:
        writer = csv.writer(file)
        writer.writerow(["inventory_id", "standard_micro", "actual_micro", "difference_micro", "comparable"])
        for node in nodes:
            writer.writerow(["__COGS__" if node == cogs else f"I{node}",
                             standard_cost.get(node, ""), actual_cost[node],
                             actual_cost[node] - standard_cost[node] if node in standard_cost else "",
                             int(node in standard_cost)])
    metadata = {"generator": "layered_period_bom", "seed": args.seed,
                "layer_sizes": sizes, "level_ratio": ratio, "products": args.products,
                "material_codes": args.materials, "changed_products": args.changed_products,
                "replaced_branches": len(replacements), "replacements": replacements,
                "cogs_node": cogs,
                "production_orders_per_product": orders, "actual_usage_records": actual_usage,
                "standard_usage_records": standard_usage,
                "inventory_usage_record_format": "little-endian uint32 parent_id, uint32 material_id",
                "total_nodes": len(nodes), "active_nodes": active_count,
                "total_csr_edges": len(arrays["reverse_successors"]),
                "terminal_count": len(arrays["terminal_nodes"]),
                "unmatched_actual_nodes": int((arrays["node_comparable"] == 0).sum())}
    (args.output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(f"Products: {args.products}; materials: {args.materials}; actual issues: {actual_usage:,}")
    print(f"Graph: {len(nodes):,} nodes; {active_count:,} active; "
          f"{metadata['unmatched_actual_nodes']:,} unmatched; {len(arrays['terminal_nodes']):,} terminals")
    print(f"Wrote {args.output}")


if __name__ == "__main__":
    main()
