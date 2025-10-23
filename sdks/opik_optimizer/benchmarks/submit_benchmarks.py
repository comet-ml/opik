#!/usr/bin/env python3
"""
CLI wrapper for submitting benchmark tasks to Modal.

This script handles CLI argument parsing and calls the Modal function.
Modal's local_entrypoint doesn't support list parameters, so we use argparse
and call the Modal function programmatically.

Usage:
    python submit_benchmarks.py --test-mode
    python submit_benchmarks.py --demo-datasets gsm8k hotpot_300 --optimizers few_shot
"""

import argparse


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Submit benchmark tasks to Modal for execution"
    )
    parser.add_argument(
        "--demo-datasets",
        nargs="*",
        default=None,
        help="List of dataset names to benchmark",
    )
    parser.add_argument(
        "--optimizers",
        nargs="*",
        default=None,
        help="List of optimizer names to benchmark",
    )
    parser.add_argument(
        "--models",
        nargs="*",
        default=None,
        help="List of model names to benchmark",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="Random seed for reproducibility",
    )
    parser.add_argument(
        "--test-mode",
        action="store_true",
        help="Run in test mode with 5 examples per dataset",
    )
    parser.add_argument(
        "--max-concurrent",
        type=int,
        default=5,
        help="Maximum number of concurrent tasks",
    )
    parser.add_argument(
        "--retry-failed-run-id",
        type=str,
        default=None,
        help="Run ID to retry failed tasks from",
    )
    parser.add_argument(
        "--resume-run-id",
        type=str,
        default=None,
        help="Run ID to resume incomplete run from",
    )

    args = parser.parse_args()

    # Import the submit function and app (runs locally, not in Modal cloud)
    from submit_benchmarks_modal import app, submit_benchmark_tasks

    # Call the function within app context (it runs locally and spawns Modal tasks)
    with app.run():
        submit_benchmark_tasks(
            demo_datasets=args.demo_datasets,
            optimizers=args.optimizers,
            models=args.models,
            seed=args.seed,
            test_mode=args.test_mode,
            max_concurrent=args.max_concurrent,
            retry_failed_run_id=args.retry_failed_run_id,
            resume_run_id=args.resume_run_id,
        )


if __name__ == "__main__":
    main()
