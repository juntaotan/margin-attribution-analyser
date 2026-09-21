import math
import random

from config import GeneratorConfig
from models import Bom, BomNode, BomEdge, NodeType


class BomGenerator:

    def __init__(self, config: GeneratorConfig):
        self.config = config
        self.random = random.Random(config.random_seed)

        self.next_node_id = 1

    def generate_all(self) -> list[Bom]:
        """
        Generate all BOM DAGs.
        """

        boms = []

        for bom_id in range(1, self.config.bom_count + 1):
            bom = self.generate_bom(bom_id)
            boms.append(bom)

        return boms

    def generate_bom(self, bom_id: int) -> Bom:
        """
        Generate one random BOM DAG.
        """

        root = self._create_node(
            bom_id=bom_id,
            depth=0,
            node_type=NodeType.PRODUCT
        )

        nodes = {
            root.node_id: root
        }

        edges = []

        # TODO:
        # Implement graph propagation starting from root.
        #
        # Suggested idea:
        #
        # frontier = [root]
        #
        # while frontier:
        #     current = frontier.pop()
        #
        #     1. Check current.depth.
        #     2. Calculate propagation probability.
        #     3. Decide whether current becomes a leaf.
        #     4. If it propagates:
        #           generate >= 5 children.
        #     5. Add edges.
        #     6. Add new children to frontier.
        #
        # Remember:
        # - Do not exceed max_depth.
        # - Do not exceed max_nodes_per_bom.
        # - DAG must remain acyclic.

        return Bom(
            bom_id=bom_id,
            root_id=root.node_id,
            nodes=nodes,
            edges=edges
        )

    def propagation_probability(self, depth: int) -> float:
        """
        Probability that a node continues propagating.

        P(d) = P0 * exp(-lambda * d)
        """

        # TODO:
        # Implement exponential decay.
        #
        # Use:
        #
        # initial_propagation_probability
        # propagation_decay_rate

        raise NotImplementedError

    def should_propagate(self, depth: int) -> bool:
        """
        Randomly determine whether a node should generate children.
        """

        # TODO:
        # 1. Return False when depth >= max_depth.
        # 2. Calculate propagation_probability(depth).
        # 3. Generate random number in [0, 1).
        # 4. Compare it with probability.

        raise NotImplementedError

    def generate_child_count(self) -> int:
        """
        Generate number of downstream nodes.

        Requirement:
            child_count >= 5
        """

        # TODO:
        # Generate an integer between:
        #
        # config.min_children
        # config.max_children

        raise NotImplementedError

    def _create_node(
        self,
        bom_id: int,
        depth: int,
        node_type: NodeType
    ) -> BomNode:

        node = BomNode(
            node_id=self.next_node_id,
            bom_id=bom_id,
            node_type=node_type,
            depth=depth
        )

        self.next_node_id += 1

        return node

    def _create_edge(
        self,
        bom_id: int,
        parent: BomNode,
        child: BomNode
    ) -> BomEdge:

        # TODO:
        # Generate realistic random values for:
        #
        # quantity
        # standard_unit_cost

        raise NotImplementedError

    def try_reuse_node(
        self,
        current_node: BomNode,
        nodes: dict[int, BomNode]
    ) -> BomNode | None:
        """
        Attempt to reuse an existing downstream node.

        This creates shared components and converts
        a pure tree into a DAG.
        """

        # TODO:
        #
        # Step 1:
        # Randomly determine whether reuse should happen
        # according to config.reuse_probability.
        #
        # Step 2:
        # Find candidate nodes that can safely be reused.
        #
        # IMPORTANT:
        # Reusing a node must never introduce a cycle.
        #
        # Hint:
        # Restrict candidates by depth / generation.
        #
        # Step 3:
        # Return selected node.
        #
        # Return None if no node should be reused.

        raise NotImplementedError