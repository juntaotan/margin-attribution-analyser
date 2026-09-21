from dataclasses import dataclass
from enum import Enum


class NodeType(Enum):
    PRODUCT = "PRODUCT"
    SUBASSEMBLY = "SUBASSEMBLY"
    MATERIAL = "MATERIAL"


@dataclass
class BomNode:
    node_id: int
    bom_id: int
    node_type: NodeType
    depth: int


@dataclass
class BomEdge:
    bom_id: int

    parent_id: int
    child_id: int

    # Quantity of child required by parent.
    quantity: float

    # Standard cost per unit.
    standard_unit_cost: float


@dataclass
class Bom:
    bom_id: int
    root_id: int

    nodes: dict[int, BomNode]
    edges: list[BomEdge]


@dataclass
class ProductionOrder:
    order_id: int
    bom_id: int
    product_id: int
    production_quantity: int


@dataclass
class MaterialIssue:
    issue_id: int
    order_id: int
    bom_id: int

    material_id: int

    planned_quantity: float
    actual_quantity: float

    standard_unit_cost: float
    actual_unit_cost: float

    actual_cost: float


@dataclass
class CostSummary:
    order_id: int
    bom_id: int

    material_cost: float
    labor_cost: float
    overhead_cost: float

    total_cost: float