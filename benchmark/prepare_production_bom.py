#!/usr/bin/env python3
"""Rebuild a frontier fixture from raw issue events and the two BOM periods."""

import argparse
import csv
import json
import time
from collections import Counter, defaultdict
from functools import lru_cache
from pathlib import Path

import numpy as np

from generators.generate_production_bom import calculate, reachable, write_fixture


def read_bom(path):
    """Read the period's parent-to-material quantities."""
    graph = defaultdict(list)
    with path.open(newline="") as file:
        for row in csv.DictReader(file):
            graph[int(row["product_no"][1:])].append(
                (int(row["material_no"][1:]), int(float(row["material_usage"]))))
    return dict(graph)


def read_issues(path):
    """Count every raw issue record while keeping only distinct edges in memory."""
    counts = Counter()
    rows = 0
    with path.open("rb") as file:
        while True:
            block = np.fromfile(file, dtype="<u8", count=1_000_000)
            if not len(block):
                break
            keys, frequencies = np.unique(block, return_counts=True)
            counts.update({int(key): int(count) for key, count in zip(keys, frequencies)})
            rows += len(block)
    return counts, rows


def read_reference(path):
    """Read generated costs only to recover leaf unit prices and verify the rollup."""
    with path.open(newline="") as file:
        return {row["inventory_id"]: row for row in csv.DictReader(file)}


def actual_costs_from_issues(graph, active, prices, issue_counts, quantities, products, orders):
    """Aggregate actual demand from issue events, then roll unit costs up the BOM."""
    demand = Counter({product: orders for product in products})
    for key, count in issue_counts.items():
        parent, child = key & 0xffffffff, key >> 32
        demand[child] += count * quantities[(parent, child)]

    @lru_cache(None)
    def unit(node):
        return sum(unit(child) * quantity for child, quantity in graph[node]) \
            if node in graph else prices[node]

    for key, count in issue_counts.items():
        parent = key & 0xffffffff
        if count != demand[parent]:
            raise ValueError(f"Issue count differs from production demand for I{parent}")
    return {node: unit(node) * demand[node] * 1_000_000 for node in active}


def rebuild(dataset, output, leaf_threshold):
    """Read issues, aggregate costs, and encode the same active frontier graph."""
    started = time.perf_counter_ns()
    metadata = json.loads((dataset / "metadata.json").read_text())
    products = list(range(metadata["products"]))
    standard = read_bom(dataset / "standard_bom.csv")
    actual = read_bom(dataset / "actual_bom.csv")
    reference = read_reference(dataset / "node_costs.csv")
    bom_loaded = time.perf_counter_ns()

    issue_counts, issue_rows = read_issues(dataset / "inventory_usage.bin")
    issues_loaded = time.perf_counter_ns()
    edges = {(parent, child): quantity for parent, children in actual.items()
             for child, quantity in children}
    if len(issue_counts) != len(edges) or issue_rows != metadata["actual_usage_records"]:
        raise ValueError("Raw issue count or distinct graph edges differ from the BOM")
    material_demand = Counter()
    for key, count in issue_counts.items():
        parent, child = key & 0xffffffff, key >> 32
        quantity = edges.get((parent, child))
        if quantity is None:
            raise ValueError(f"Issue edge {parent}->{child} is absent from the BOM")
        if child not in actual:
            material_demand[child] += count * quantity

    price_file = dataset / "material_prices.csv"
    if price_file.exists():
        with price_file.open(newline="") as file:
            prices = {int(row["inventory_id"][1:]): int(row["unit_price_micro"]) // 1_000_000
                      for row in csv.DictReader(file)}
        price_source = "material_prices.csv"
    else:
        # Older fixtures contain only rolled-up costs, so recover each leaf's
        # unit price from its actual issued quantity before rebuilding costs.
        first_material = sum(metadata["layer_sizes"][:-1])
        prices = {}
        for material in range(first_material, first_material + metadata["material_codes"]):
            cost = int(reference[f"I{material}"]["actual_micro"])
            price, remainder = divmod(cost, material_demand[material] * 1_000_000)
            if remainder:
                raise ValueError(f"Material I{material} has a non-integral unit price")
            prices[material] = price
        price_source = "derived_from_leaf_costs"
    prices_loaded = time.perf_counter_ns()

    actual_active = reachable(products, actual)
    standard_active = reachable(products, standard)
    actual_cost = actual_costs_from_issues(
        actual, actual_active, prices, issue_counts, edges, products,
        metadata["production_orders_per_product"])
    standard_cost, _, _ = calculate(
        products, standard, standard_active, prices, metadata["production_orders_per_product"])
    cogs = metadata["cogs_node"]
    actual[cogs] = [(product, 1) for product in products]
    actual_cost[cogs] = sum(actual_cost[product] for product in products)
    standard_cost[cogs] = sum(standard_cost[product] for product in products)
    for node, cost in actual_cost.items():
        row = reference["__COGS__" if node == cogs else f"I{node}"]
        if cost != int(row["actual_micro"]) or (node in standard_cost and
                standard_cost[node] != int(row["standard_micro"])):
            raise ValueError(f"Reconstructed cost differs from fixture for node {node}")
    costs_built = time.perf_counter_ns()

    output.mkdir(parents=True, exist_ok=True)
    nodes, arrays, active_count = write_fixture(
        output, actual, actual_cost, standard_cost, leaf_threshold)
    graph_written = time.perf_counter_ns()
    return {"issue_rows": issue_rows, "unique_issue_edges": len(issue_counts),
            "price_source": price_source,
            "nodes": len(nodes), "active_nodes": active_count,
            "edges": len(arrays["reverse_successors"]),
            "terminals": len(arrays["terminal_nodes"]),
            "bom_load_ms": (bom_loaded - started) / 1e6,
            "issue_read_ms": (issues_loaded - bom_loaded) / 1e6,
            "price_extract_ms": (prices_loaded - issues_loaded) / 1e6,
            "cost_rollup_ms": (costs_built - prices_loaded) / 1e6,
            "graph_encode_ms": (graph_written - costs_built) / 1e6,
            "total_ms": (graph_written - started) / 1e6}


def main():
    """Write the rebuilt fixture and a machine-readable stage report."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--leaf-threshold", type=int, default=1)
    args = parser.parse_args()
    print("PREP_JSON " + json.dumps(rebuild(args.dataset, args.output, args.leaf_threshold)))


if __name__ == "__main__":
    main()
