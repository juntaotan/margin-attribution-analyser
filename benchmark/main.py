from config import GeneratorConfig
from bom_generator import BomGenerator
from production_generator import ProductionGenerator
from csv_writer import CsvWriter
from validator import DatasetValidator


def main():

    config = GeneratorConfig()

    bom_generator = BomGenerator(config)
    production_generator = ProductionGenerator(config)

    writer = CsvWriter(config.output_directory)
    validator = DatasetValidator()

    # -------------------------------------------------
    # Phase 1: Generate BOMs
    # -------------------------------------------------

    print("Generating BOMs...")

    boms = bom_generator.generate_all()

    # -------------------------------------------------
    # Phase 2: Validate BOMs
    # -------------------------------------------------

    print("Validating BOMs...")

    for bom in boms:
        validator.validate_bom(bom)

    # -------------------------------------------------
    # Phase 3: Export BOM structure
    # -------------------------------------------------

    # TODO:
    # Flatten all BomNode objects and write:
    #
    # bom_nodes.csv

    # TODO:
    # Flatten all BomEdge objects and write:
    #
    # bom_edges.csv

    # -------------------------------------------------
    # Phase 4: Generate production transactions
    # -------------------------------------------------

    # TODO:
    #
    # Repeatedly:
    #
    # 1. Select a BOM.
    # 2. Generate ProductionOrder.
    # 3. Generate MaterialIssue records.
    # 4. Write them incrementally.
    # 5. Calculate CostSummary.
    #
    # Stop when:
    #
    # material_issue_count
    # >= 10,000,000
    #
    # IMPORTANT:
    # Do not store 10M records in memory.

    print("Generation complete.")


if __name__ == "__main__":
    main()