"""Backward-compatible wrapper for the Modal benchmark coordinator.

This module is kept to avoid breaking existing commands that reference
`benchmarks/run_benchmark_modal.py`.
"""

from benchmarks.runners.run_benchmark_modal import main, submit_benchmark_tasks

__all__ = ["main", "submit_benchmark_tasks"]

if __name__ == "__main__":
    main()
