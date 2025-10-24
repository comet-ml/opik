#!/usr/bin/env python3
"""
Unified benchmark runner for both local and Modal execution.

Usage:
    # Local execution
    python run_benchmark.py --demo-datasets gsm8k --optimizers few_shot --test-mode

    # Modal execution
    python run_benchmark.py --modal --demo-datasets gsm8k --optimizers few_shot --test-mode

    # Modal execution with custom concurrency
    python run_benchmark.py --modal --demo-datasets gsm8k --optimizers few_shot --test-mode --max-concurrent 10
"""

import argparse

import benchmark_config


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run benchmarks for prompt optimization (local or Modal)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Local execution with test mode
  python run_benchmark.py --demo-datasets gsm8k --optimizers few_shot --test-mode

  # Modal execution with test mode
  python run_benchmark.py --modal --demo-datasets gsm8k --optimizers few_shot --test-mode

  # Modal execution with multiple datasets and optimizers
  python run_benchmark.py --modal \\
    --demo-datasets gsm8k hotpot_300 \\
    --optimizers few_shot meta_prompt \\
    --models openai/gpt-4o-mini \\
    --max-concurrent 10

  # Local execution with custom concurrency
  python run_benchmark.py \\
    --demo-datasets gsm8k hotpot_300 \\
    --optimizers few_shot \\
    --max-concurrent 4

  # Resume a previous run
  python run_benchmark.py --modal --resume-run-id run_20250423_153045

  # Retry failed tasks
  python run_benchmark.py --modal --retry-failed-run-id run_20250423_153045
        """,
    )

    # Execution mode
    parser.add_argument(
        "--modal",
        action="store_true",
        help="Run on Modal (distributed cloud execution) instead of locally",
    )

    # Benchmark configuration
    parser.add_argument(
        "--demo-datasets",
        type=str,
        nargs="*",
        default=None,
        help=f"Dataset names to benchmark. Available: {list(benchmark_config.DATASET_CONFIG.keys())}",
    )
    parser.add_argument(
        "--optimizers",
        type=str,
        nargs="*",
        default=None,
        help=f"Optimizer names to benchmark. Available: {list(benchmark_config.OPTIMIZER_CONFIGS.keys())}",
    )
    parser.add_argument(
        "--models",
        type=str,
        nargs="*",
        default=None,
        help=f"Model names to benchmark. Available: {benchmark_config.MODELS}",
    )
    parser.add_argument(
        "--test-mode",
        action="store_true",
        default=False,
        help="Run in test mode with 5 examples per dataset",
    )
    parser.add_argument(
        "--seed", type=int, default=42, help="Random seed for reproducibility"
    )

    # Concurrency options
    parser.add_argument(
        "--max-concurrent",
        type=int,
        default=5,
        help="Maximum number of concurrent tasks (workers for local, containers for Modal)",
    )

    # Local-specific options
    parser.add_argument(
        "--checkpoint-dir",
        type=str,
        default="./benchmark_results",
        help="[Local only] Directory to save benchmark results",
    )

    # Resume/retry options
    parser.add_argument(
        "--retry-failed-run-id",
        type=str,
        default=None,
        metavar="RUN_ID",
        help="Retry only failed tasks from a previous run",
    )
    parser.add_argument(
        "--resume-run-id",
        type=str,
        default=None,
        metavar="RUN_ID",
        help="Resume incomplete run (skip completed tasks)",
    )

    args = parser.parse_args()

    if args.modal:
        # Modal execution
        print("🌩️  Running on Modal (cloud execution)")
        print("-" * 80)

        # Import Modal submission function
        from run_benchmark_modal import app, submit_benchmark_tasks

        # Call the function within app context
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
    else:
        # Local execution
        print("💻 Running locally (local machine execution)")
        print("-" * 80)

        # Import local benchmark function
        from run_benchmark_local import run_benchmark

        run_benchmark(
            demo_datasets=args.demo_datasets,
            optimizers=args.optimizers,
            models=args.models,
            max_workers=args.max_concurrent,
            seed=args.seed,
            test_mode=args.test_mode,
            checkpoint_dir=args.checkpoint_dir,
            retry_failed_run_id=args.retry_failed_run_id,
            resume_run_id=args.resume_run_id,
        )


if __name__ == "__main__":
    main()
