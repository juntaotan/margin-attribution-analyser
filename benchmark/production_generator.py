import random
from typing import Iterator

from config import GeneratorConfig
from models import (
    Bom,
    NodeType,
    ProductionOrder,
    MaterialIssue,
    CostSummary
)


class ProductionGenerator:

    def __init__(self, config: GeneratorConfig):
        self.config = config

        self.random = random.Random(
            config.random_seed + 1
        )

        self.next_order_id = 1
        self.next_issue_id = 1

    def generate_order(
        self,
        bom: Bom
    ) -> ProductionOrder:

        # TODO:
        #
        # Generate:
        # - unique order_id
        # - production_quantity
        #
        # product_id should correspond to bom.root_id.

        raise NotImplementedError

    def get_material_nodes(self, bom: Bom):
        """
        Return raw-material nodes of the BOM.
        """

        # TODO:
        # Filter bom.nodes using NodeType.MATERIAL.

        raise NotImplementedError

    def generate_material_issues(
        self,
        order: ProductionOrder,
        bom: Bom
    ) -> Iterator[MaterialIssue]:

        """
        Generate material issue records for one production order.
        """

        # TODO:
        #
        # For every required raw material:
        #
        # 1. Calculate planned quantity.
        #
        # 2. Generate actual quantity variance.
        #
        # Example:
        #
        # actual_qty =
        #     planned_qty * random(0.95, 1.08)
        #
        # 3. Generate actual price variance.
        #
        # actual_price =
        #     standard_price * random(0.90, 1.15)
        #
        # 4. Calculate:
        #
        # actual_cost =
        #     actual_qty * actual_price
        #
        # 5. yield MaterialIssue(...)
        #
        # IMPORTANT:
        # Do not create a huge list here.

        raise NotImplementedError

    def calculate_cost_summary(
        self,
        order: ProductionOrder,
        material_cost: float
    ) -> CostSummary:

        # TODO:
        #
        # Generate:
        # - direct labor
        # - manufacturing overhead
        #
        # Then:
        #
        # total_cost =
        #     material_cost
        #     + labor_cost
        #     + overhead_cost

        raise NotImplementedError