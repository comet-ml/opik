"""Modal benchmark coordinator entrypoint.

Provides a top-level path for:
  modal run benchmarks/run_benchmark_modal.py
while reusing the implementation in benchmarks/runners/run_benchmark_modal.py.
"""

from benchmarks.runners.run_benchmark_modal import app, main, submit_benchmark_tasks

__all__ = ["app", "main", "submit_benchmark_tasks"]

if __name__ == "__main__":
    main()
