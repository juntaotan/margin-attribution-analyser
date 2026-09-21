from dataclasses import dataclass


@dataclass(frozen=True)
class GeneratorConfig:
    # Reproducibility
    random_seed: int = 576

    # BOM generation
    bom_count: int = 100
    min_children: int = 5
    max_children: int = 10
    max_depth: int = 8
    max_nodes_per_bom: int = 10_000

    # Probability that a node continues expanding.
    initial_propagation_probability: float = 0.90
    propagation_decay_rate: float = 0.55

    # Probability of reusing an existing downstream node,
    # which turns the structure from a tree into a DAG.
    reuse_probability: float = 0.15

    # Production data
    target_material_issue_rows: int = 10_000_000

    min_production_quantity: int = 10
    max_production_quantity: int = 1000

    # Output
    output_directory: str = "./generated"