import csv
from pathlib import Path
from dataclasses import asdict


class CsvWriter:

    def __init__(self, output_directory: str):
        self.output_directory = Path(output_directory)

        self.output_directory.mkdir(
            parents=True,
            exist_ok=True
        )

    def write_rows(
        self,
        filename: str,
        rows
    ):
        """
        Stream dataclass objects into CSV.
        """

        path = self.output_directory / filename

        iterator = iter(rows)

        try:
            first = next(iterator)
        except StopIteration:
            return

        fieldnames = list(asdict(first).keys())

        # TODO:
        #
        # Open file.
        #
        # Create csv.DictWriter.
        #
        # Write header.
        #
        # Write first object.
        #
        # Iterate over remaining rows.
        #
        # IMPORTANT:
        # Write rows incrementally.
        # Never call list(rows).

        raise NotImplementedError