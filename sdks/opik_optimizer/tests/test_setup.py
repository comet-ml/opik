import os
import sys

# Add the src directory to the Python path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

# Ensure benchmark modules (which use script-style imports) are importable in tests
benchmarks_dir = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "benchmarks")
)
if benchmarks_dir not in sys.path:
    sys.path.insert(0, benchmarks_dir)
