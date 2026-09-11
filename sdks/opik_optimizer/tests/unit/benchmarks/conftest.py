import os
import sys

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
BENCHMARKS_DIR = os.path.join(ROOT_DIR, "benchmarks")

for path in (ROOT_DIR, BENCHMARKS_DIR):
    if path not in sys.path:
        sys.path.insert(0, path)
