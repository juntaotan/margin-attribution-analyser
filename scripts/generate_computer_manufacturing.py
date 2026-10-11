#!/usr/bin/env python3
"""Generate English computer-manufacturing records using only the standard library.

Standard recipes are separate from independently mutated production occurrences.
Every original component occurrence gets independent structure/material draws;
purchased leaves also get a price draw. Costs use Decimal throughout. No database
connection is made, and a non-empty destination is never overwritten.
"""

import argparse
import csv
import json
import random
from collections import Counter
from dataclasses import dataclass, field
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

D = Decimal
ZERO = D("0")
PRECISION = D("0.000001")
FAMILIES = (
    ("Office Desktop", 8), ("Gaming Desktop", 8), ("Ultrabook", 6),
    ("Gaming Laptop", 6), ("All-in-One", 4), ("Workstation", 4),
)
STAGES = (
    "Core Assembly", "Mainboard Assembly", "Power Module", "Power Stage",
    "Semiconductor Package", "Tested Die", "Processed Wafer", "Polished Wafer",
)
RAW_PARTS = (
    ("Memory Module", "16 GB DDR5", "each", "42"),
    ("Solid State Drive", "1 TB NVMe", "each", "65"),
    ("Cooling Fan", "80 mm PWM", "each", "8"),
    ("Copper Foil", "35 um copper foil", "kg", "12"),
    ("Solder Paste", "Lead-free SAC305", "kg", "35"),
    ("Thermal Compound", "Electrically insulating compound", "kg", "22"),
    ("Mounting Screw", "M3 x 6 mm", "each", "0.05"),
    ("Carton", "Recyclable shipping carton", "each", "2"),
    ("Silicon Feedstock", "Electronic-grade silicon", "kg", "18"),
)


def money(value):
    """Round ledger values to six decimal places using decimal HALF_UP."""
    return value.quantize(PRECISION, rounding=ROUND_HALF_UP)


def text(value):
    return format(value, "f") if isinstance(value, D) else value


@dataclass
class Occurrence:
    """One actual material position; equal codes never merge occurrences."""

    code: str
    usage: Decimal
    position: str
    children: list = field(default_factory=list)
    price: Decimal = ZERO
    event_ids: list = field(default_factory=list)


class Generator:
    """Build recipes, actual batches, split issues and explicit cost evidence.

    All intermediate batches are made to order and entirely consumed by one
    receiving position (source share 1). This deliberately avoids inferred FIFO
    links and does not simulate shared upstream batches or returns.
    """

    def __init__(self, args):
        self.args = args
        self.rng = random.Random(args.seed)
        self.master = {}
        self.bom = {}
        self.products = []
        self.tables = {name: [] for name in (
            "inventory_usage", "production_outputs", "issue_evidence",
            "processing_sources", "processing_allocations", "purchases",
            "sales_details", "actual_relationships", "anomalies", "batch_expectations",
        )}
        self.order_counter = 0
        self.issue_counter = 0
        self.depths = []

    def material(self, code, name, kind, specification, unit="each", price=ZERO):
        self.master[code] = dict(
            material_no=code, material_name=name, material_type=kind,
            base_uom=unit, specification=specification, material_group=kind,
            status="ACTIVE", supplier="" if kind != "RAW" else f"Supplier {code[-2:]}",
            source_kind="PURCHASED" if kind == "RAW" else "INTERNAL", unit_cost=price,
        )
        return code

    def recipes(self):
        """Make shared variable-depth modules and a maximum-depth path per SKU.

        Root is level 1. Deep paths model a fictional vertically integrated
        group, not a commercially accurate semiconductor process recipe.
        """
        for i, (name, spec, unit, price) in enumerate(RAW_PARTS):
            self.material(f"RAW-{i:03d}", name, "RAW", spec, unit, D(price))
        for family_index, (family, count) in enumerate(FAMILIES):
            modules = []
            for module_index, module_name in enumerate((
                "Compute", "Storage", "Cooling", "Power", "Enclosure", "Display",
            )):
                terminal_level = (self.args.max_depth if module_index == 0
                                  else self.rng.randint(2, self.args.max_depth))
                child = "RAW-008" if module_index == 0 else f"RAW-{self.rng.randrange(8):03d}"
                for level in range(terminal_level - 1, 1, -1):
                    code = f"ASM-{family_index:02d}-{module_index:02d}-{level:02d}"
                    stage = STAGES[min(level - 2, len(STAGES) - 1)] if module_index == 0 else f"{module_name} Stage {level}"
                    self.material(code, f"{family} {stage}", "INTERMEDIATE",
                                  f"Synthetic {module_name.lower()} manufacturing stage {level}")
                    self.bom[code] = [(child, D("1"))]
                    # Side branches terminate early, including on the deepest spine.
                    side = f"RAW-{self.rng.randrange(8):03d}"
                    if side != child:
                        self.bom[code].append((side, self.usage(side)))
                    child = code
                modules.append((child, self.usage(child)))
            for variant in range(count):
                code = f"PC-{family_index:02d}-{variant + 1:02d}"
                ram = (8, 16, 32, 64)[variant % 4]
                storage = (256, 512, 1024, 2048)[variant % 4]
                self.material(code, f"{family} Model {variant + 1:02d}", "FINISHED",
                              f"Tier {variant + 1}; {ram} GB RAM; {storage} GB SSD")
                combined = {}
                for child, usage in modules + [("RAW-000", D(1 + variant % 4)),
                                                ("RAW-001", D(1 + variant % 2)), ("RAW-007", D(1))]:
                    combined[child] = combined.get(child, ZERO) + usage
                self.bom[code] = list(combined.items())
                self.products.append(code)

    def usage(self, code):
        return D("0.05") if self.master[code]["base_uom"] == "kg" else D("1")

    def event(self, kind, root_order, period, position, before, after, **extra):
        event_id = f"EVENT-{len(self.tables['anomalies']) + 1:07d}"
        self.tables["anomalies"].append(dict(
            event_id=event_id, period=period, root_order_no=root_order,
            position=position, type=kind, before=before, after=after, **extra,
        ))
        return event_id

    def substitute(self, code):
        alternative = code + "-ALT"
        if alternative not in self.master:
            original = self.master[code]
            self.material(alternative, original["material_name"] + " Alternative", original["material_type"],
                          original["specification"] + "; alternative grade", original["base_uom"],
                          money(original["unit_cost"] * D("1.15")))
            if code in self.bom:
                self.bom[alternative] = self.bom[code].copy()
        return alternative

    def occurrence(self, code, usage, position, depth, root_order, period, scale):
        """Mutate each original position once; generated wrappers are not redrawn.

        Structure changes group the component in an extra subassembly when the
        depth budget permits; otherwise they add a direct mounting component.
        Material changes retain the recipe for an intermediate alternative.
        """
        ids = []
        if self.rng.random() < self.args.material_rate * scale:
            alternative = self.substitute(code)
            ids.append(self.event("MATERIAL_SUBSTITUTION", root_order, period, position, code, alternative))
            code = alternative
        node = Occurrence(code, usage, position, event_ids=ids)
        for i, (child, child_usage) in enumerate(self.bom.get(code, [])):
            node.children.append(self.occurrence(child, child_usage, f"{position}.{i + 1}",
                                                 depth + 1, root_order, period, scale))
        if not node.children:
            node.price = self.master[code]["unit_cost"]
            if self.rng.random() < self.args.cost_rate * scale:
                factor = D(self.rng.choice(("0.8", "0.9", "1.1", "1.25", "1.5")))
                before = node.price
                node.price = money(before * factor)
                node.event_ids.append(self.event("LEAF_COST_CHANGE", root_order, period, position,
                                                 text(before), text(node.price), factor=text(factor)))
        if self.rng.random() < self.args.structure_rate * scale:
            height = self.height(node)
            if depth + height <= self.args.max_depth:
                wrapper = f"WRAP-{code}"
                if wrapper not in self.master:
                    self.material(wrapper, self.master[code]["material_name"] + " Integration Assembly",
                                  "INTERMEDIATE", "Additional synthetic integration operation",
                                  price=money(self.nominal_unit_cost(code) + D("1.20")))
                event_id = self.event("STRUCTURE_INSERT", root_order, period, position, code, wrapper)
                # Wrapper output is in the original child's unit; its internal use is one.
                self.master[wrapper]["base_uom"] = self.master[code]["base_uom"]
                node.usage = D(1)
                self.reposition(node, position + ".inserted")
                node = Occurrence(wrapper, usage, position, [node], event_ids=[event_id])
            else:
                # At the cap, attach a sibling rather than exceeding maximum depth.
                event_id = self.event("STRUCTURE_EXTRA_COMPONENT", root_order, period, position,
                                      "No additional mounting component", "RAW-006")
                node.event_ids.append(event_id)
                node.extra = Occurrence("RAW-006", D(1), position + ".extra",
                                        price=self.master["RAW-006"]["unit_cost"], event_ids=[event_id])
        return node

    @staticmethod
    def reposition(node, position):
        old = node.position
        node.position = position
        for child in node.children:
            Generator.reposition(child, child.position.replace(old, position, 1))
        if hasattr(node, "extra"):
            Generator.reposition(node.extra, node.extra.position.replace(old, position, 1))

    @staticmethod
    def height(node):
        return 1 + max((Generator.height(c) for c in node.children), default=0)

    def order(self):
        self.order_counter += 1
        return f"MO-{self.order_counter:07d}"

    def manufacture(self, node, quantity, day, root_order, period, order_no=None):
        """Produce children first and split each receiving flow into two issues.

        Child output valuation is the parent's material contribution exactly
        once. Local processing pools are allocated equally by material position,
        with the rounding remainder assigned explicitly to the last position.
        """
        order_no = order_no or self.order()
        children = []
        for child in node.children:
            children.append(child)
            if hasattr(child, "extra"):
                children.append(child.extra)
        materials = []
        for child in children:
            issued = quantity * child.usage
            source_order = ""
            if child.children:
                amount, source_order = self.manufacture(child, issued, day - timedelta(days=1), root_order, period)
            else:
                amount = money(issued * child.price)
            materials.append((child, issued, amount, source_order))
        labour = money(quantity * D("0.75"))
        overhead = money(quantity * D("0.45"))
        total = sum((m[2] for m in materials), ZERO) + labour + overhead
        self.tables["production_outputs"].append(dict(
            output_id=f"OUT-{order_no}", date=day.isoformat(), order_no=order_no,
            product_no=node.code, product_num=quantity, product_total_cost=total,
            root_order_no=root_order, position=node.position, period=period,
            currency=self.args.currency, unit=self.master[node.code]["base_uom"],
            interpretation="RECEIPTS", cost_scope="manufacturing",
        ))
        self.tables["processing_sources"].append(dict(
            source_id=f"PROC-{order_no}", order_no=order_no, labour=labour,
            overhead=overhead, currency=self.args.currency, cost_scope="manufacturing",
            allocation_basis="EQUAL_PER_RECEIVING_POSITION_WITH_LAST_POSITION_REMAINDER",
        ))
        remaining_labour, remaining_overhead = labour, overhead
        for index, (child, issued, amount, source_order) in enumerate(materials):
            allocation_id = f"ALLOC-{order_no}-{index + 1}"
            last = index == len(materials) - 1
            local_labour = remaining_labour if last else money(labour / D(len(materials)))
            local_overhead = remaining_overhead if last else money(overhead / D(len(materials)))
            remaining_labour -= local_labour
            remaining_overhead -= local_overhead
            self.tables["processing_allocations"].append(dict(
                allocation_id=allocation_id, source_id=f"PROC-{order_no}", order_no=order_no,
                position=child.position, material_no=child.code, labour=local_labour,
                overhead=local_overhead, currency=self.args.currency, cost_scope="manufacturing",
            ))
            self.tables["actual_relationships"].append(dict(
                root_order_no=root_order, order_no=order_no, product_no=node.code,
                material_no=child.code, position=child.position, material_usage=child.usage,
                source_order_no=source_order, period=period,
            ))
            # Two documents on different days emulate an initial issue and top-up.
            first_quantity = money(issued * D("0.6"))
            first_amount = money(amount * first_quantity / issued)
            for split, (split_quantity, split_amount) in enumerate((
                (first_quantity, first_amount), (issued - first_quantity, amount - first_amount),
            )):
                self.issue_counter += 1
                line_id = f"LINE-{self.issue_counter:08d}"
                movement = f"ISS-{order_no}-{split + 1}"
                issue_day = day - timedelta(days=1 - split)
                self.tables["inventory_usage"].append(dict(
                    date=issue_day.isoformat(), movement_no=movement, product_no=node.code,
                    product_num=quantity, product_total_cost=total, order_no=order_no,
                    material_no=child.code, material_num=split_quantity, material_total_cost=split_amount,
                ))
                purchase_no = "" if source_order else f"PO-{line_id}"
                self.tables["issue_evidence"].append(dict(
                    line_id=line_id, date=issue_day.isoformat(), movement_no=movement,
                    order_no=order_no, product_no=node.code, material_no=child.code,
                    position=child.position, source_order_no=source_order,
                    source_share=split_quantity / issued if source_order else "",
                    source_output_id=f"OUT-{source_order}" if source_order else "",
                    purchase_order_no=purchase_no, allocation_id=allocation_id,
                    root_order_no=root_order, period=period, unit=self.master[child.code]["base_uom"],
                    currency=self.args.currency, supplier=self.master[child.code]["supplier"],
                    event_ids=";".join(child.event_ids), cost_scope="manufacturing",
                ))
                if purchase_no:
                    self.tables["purchases"].append(dict(
                        date=(issue_day - timedelta(days=3)).isoformat(), purchase_order_no=purchase_no,
                        product_no=child.code, product_num=split_quantity, product_total_price=split_amount,
                    ))
        return total, order_no

    def nominal_unit_cost(self, code):
        if code not in self.bom:
            return self.master[code]["unit_cost"]
        return sum((self.nominal_unit_cost(child) * usage for child, usage in self.bom[code]), ZERO) + D("1.20")

    def run(self):
        self.recipes()
        for code in self.master:
            if code in self.bom:
                self.master[code]["unit_cost"] = money(self.nominal_unit_cost(code))
        for period, start, scale in (
            ("COMPARABLE", self.args.comparable_start, self.args.baseline_scale),
            ("ACTUAL", self.args.actual_start, 1.0),
        ):
            for product in self.products:
                for batch in range(self.args.batches):
                    root_order = self.order()
                    event_start = len(self.tables["anomalies"])
                    root = Occurrence(product, D(1), "root")
                    for i, (child, usage) in enumerate(self.bom[product]):
                        root.children.append(self.occurrence(child, usage, f"root.{i + 1}", 2,
                                                             root_order, period, scale))
                    quantity = D((20, 50, 100)[batch % 3])
                    day = start + timedelta(days=12 + batch % 14)
                    total, _ = self.manufacture(root, quantity, day, root_order, period, root_order)
                    depth = self.height(root)
                    self.depths.append(depth)
                    nominal = self.nominal_unit_cost(product) * quantity
                    self.tables["batch_expectations"].append(dict(
                        root_order_no=root_order, product_no=product, period=period,
                        quantity=quantity, maximum_depth=depth,
                        event_count=len(self.tables["anomalies"]) - event_start,
                        nominal_total_cost=nominal, actual_total_cost=total,
                        difference_from_nominal=total - nominal,
                    ))
                    self.tables["sales_details"].append(dict(
                        date=(day + timedelta(days=2)).isoformat(), movement_no=f"SALE-{root_order}",
                        product_no=product, product_num=quantity,
                        total_revenue=money(nominal * D("1.35")), total_cost=total,
                    ))
        return self

    def write(self, destination):
        """Write basic import CSVs and sidecars; return an inspectable manifest."""
        destination.mkdir(parents=True, exist_ok=True)
        tables = dict(self.tables)
        tables["material_master"] = list(self.master.values())
        # Alternatives preserve the base recipe, but wrappers only exist in actual records.
        tables["bill_of_material"] = [dict(
            bom_no=f"BOM-{parent}", product_no=parent, material_no=child, material_usage=usage,
        ) for parent, children in self.bom.items() if not parent.endswith("-ALT") for child, usage in children]
        for name, rows in tables.items():
            headers = list(dict.fromkeys(key for row in rows for key in row))
            if not headers:
                headers = ["event_id", "period", "root_order_no", "position", "type", "before", "after", "factor"]
            with (destination / f"{name}.csv").open("w", encoding="utf-8", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=headers)
                writer.writeheader()
                writer.writerows({k: text(v) for k, v in row.items()} for row in rows)
        manifest = dict(
            generator="computer_manufacturing_v1", seed=self.args.seed,
            namespace="COMPUTER_DEMO", currency=self.args.currency,
            cost_scope="manufacturing", depth_convention="Root is level 1",
            maximum_allowed_depth=self.args.max_depth, products=len(self.products),
            batches_per_product_per_period=self.args.batches,
            periods={"COMPARABLE": self.args.comparable_start.isoformat(), "ACTUAL": self.args.actual_start.isoformat()},
            anomaly_rates=dict(structure=self.args.structure_rate, material=self.args.material_rate,
                               leaf_cost=self.args.cost_rate, baseline_scale=self.args.baseline_scale),
            depth_distribution=dict(Counter(self.depths)),
            anomaly_counts=dict(Counter(row["type"] for row in self.tables["anomalies"])),
            row_counts={name: len(rows) for name, rows in tables.items()},
            output_interpretation="REPEATED_TOTAL in inventory_usage; RECEIPTS in production_outputs",
            issue_identity="CSV row order aligns inventory_usage and issue_evidence; remap line_id after import",
            source_share_basis="Issue quantity divided by supplying output quantity; two issues sum to 1",
            limitations=["Fictional vertically integrated manufacturing, not engineering recipes",
                         "No returns, scrap, partial source batches, opening inventory or supplier lead-time model",
                         "All output is sold; inventory movements contain manufacturing issues only",
                         "Full-lineage sidecars require explicit evidence adaptation; basic warehouse import is direct scope",
                         "No embeddings or FGW scores are generated"],
        )
        (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        return manifest


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--output", type=Path, required=True)
    result.add_argument("--seed", type=int, default=42)
    result.add_argument("--max-depth", type=int, default=10)
    result.add_argument("--batches", type=int, default=4)
    result.add_argument("--structure-rate", type=float, default=0.03)
    result.add_argument("--material-rate", type=float, default=0.04)
    result.add_argument("--cost-rate", type=float, default=0.08)
    result.add_argument("--baseline-scale", type=float, default=0.0)
    result.add_argument("--currency", default="NZD")
    result.add_argument("--comparable-start", type=date.fromisoformat, default=date(2026, 8, 1))
    result.add_argument("--actual-start", type=date.fromisoformat, default=date(2026, 9, 1))
    return result


def main():
    cli = parser()
    args = cli.parse_args()
    if not 3 <= args.max_depth <= 10 or args.batches < 1:
        cli.error("max-depth must be 3..10 and batches must be positive")
    for name in ("structure_rate", "material_rate", "cost_rate", "baseline_scale"):
        if not 0 <= getattr(args, name) <= 1:
            cli.error(f"{name} must be finite and between 0 and 1")
    if len(args.currency) != 3 or not args.currency.isascii() or not args.currency.isalpha():
        cli.error("currency must be a three-letter ASCII code")
    args.currency = args.currency.upper()
    if args.output.exists() and (not args.output.is_dir() or any(args.output.iterdir())):
        cli.error("output must be a new or empty directory")
    manifest = Generator(args).run().write(args.output)
    print(json.dumps({"output": str(args.output), "rows": manifest["row_counts"],
                      "anomalies": manifest["anomaly_counts"]}, indent=2))


if __name__ == "__main__":
    main()
