#!/usr/bin/env python3
"""Compatibility entry point for the shared FGW numerical validation harness."""
from pathlib import Path
import runpy

if __name__ == "__main__":
    runpy.run_path(str(Path(__file__).resolve().parents[7] / "benchmark/fgw/validate_ports.py"), run_name="__main__")
