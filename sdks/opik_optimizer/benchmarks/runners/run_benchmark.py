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
import os
from typing import Any
from rich.console import Console
from rich.table import Table
from rich.panel import Panel
from rich import box

from benchmarks.configs.benchmark_manifest import load_manifest, manifest_to_task_specs
from benchmarks.core import benchmark_config
from benchmarks.core.benchmark_taskspec import BenchmarkTaskSpec


def _print_manifest_summary(tasks: list[BenchmarkTaskSpec], console: Console) -> None:
    table = Table(title="Manifest Summary", box=None, padding=(0, 1))
    table.add_column("Dataset", no_wrap=True)
    table.add_column("Splits", no_wrap=False)
    table.add_column("Optimizer", no_wrap=True)
    table.add_column("Model", no_wrap=True)
    table.add_column("max_trials", no_wrap=True)
    table.add_column("n_samples", no_wrap=True)

    warnings: list[str] = []

    for task in tasks:
        splits: list[str] = []
        ds_conf = task.datasets or {}
        for role in ("train", "validation", "test"):
            role_conf = ds_conf.get(role)
            if role_conf:
                count = role_conf.get("count")
                name = role_conf.get("dataset_name") or role_conf.get("loader") or role
                splits.append(f"{role}={name}({count if count is not None else '-'})")
            else:
                splits.append(f"{role}=None")
        splits_text = ", ".join(splits)

        max_trials = "-"
        n_samples = "-"
        if task.optimizer_prompt_params:
            if task.optimizer_prompt_params.get("max_trials") is not None:
                max_trials = str(task.optimizer_prompt_params.get("max_trials"))
            else:
                warnings.append(
                    f"{task.dataset_name}/{task.optimizer_name}: missing max_trials"
                )
            if task.optimizer_prompt_params.get("n_samples") is not None:
                n_samples = str(task.optimizer_prompt_params.get("n_samples"))

        table.add_row(
            task.dataset_name,
            splits_text,
            task.optimizer_name,
            task.model_name,
            max_trials,
            n_samples,
        )

    console.print(table)
    if warnings:
        console.print(
            Panel("\n".join(warnings), title="Warnings", border_style="yellow")
        )


def _print_registry(console: Console) -> None:
    """Print available datasets and optimizers from the registry."""
    split_suffixes = {"train": "_train", "validation": "_validation", "test": "_test"}
    dataset_groups: dict[str, dict[str, Any]] = {}

    for name, cfg in benchmark_config.DATASET_CONFIG.items():
        base = name
        split = None
        for role, suffix in split_suffixes.items():
            if name.endswith(suffix):
                base = name[: -len(suffix)]
                split = role
                break
        info = dataset_groups.setdefault(
            base,
            {
                "display_name": cfg.display_name,
                "metrics": {m.__name__ for m in cfg.metrics},
                "splits": set(),
            },
        )
        info["splits"].add(split or "default")
        info["metrics"].update(m.__name__ for m in cfg.metrics)

    ds_table = Table(title="Datasets", box=box.SIMPLE, expand=True)
    ds_table.add_column("Name")
    ds_table.add_column("Splits")
    ds_table.add_column("Metrics")
    ds_table.add_column("Display")
    for base, info in sorted(dataset_groups.items()):
        ds_table.add_row(
            base,
            ", ".join(sorted(info["splits"])),
            ", ".join(sorted(info["metrics"])),
            info["display_name"],
        )

    opt_table = Table(title="Optimizers", box=box.SIMPLE, expand=True)
    opt_table.add_column("Name")
    opt_table.add_column("Class")
    opt_table.add_column("Params")
    opt_table.add_column("Prompt Params")
    for name, cfg in sorted(benchmark_config.OPTIMIZER_CONFIGS.items()):
        opt_table.add_row(
            name,
            cfg.class_name,
            ", ".join(sorted(cfg.params.keys())) or "[dim]-[/dim]",
            ", ".join(sorted(cfg.optimizer_prompt_params.keys())) or "[dim]-[/dim]",
        )

    console.print(Panel(ds_table, title="Dataset Registry", border_style="cyan"))
    console.print(Panel(opt_table, title="Optimizer Registry", border_style="cyan"))


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
        default=os.path.join(
            os.path.expanduser("~"), ".opik_optimizer", "benchmark_results"
        ),
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

    parser.add_argument(
        "--config",
        type=str,
        default=None,
        help="Path to benchmark manifest JSON (overrides dataset/model/optimizer options)",
    )
    parser.add_argument(
        "--list-registries",
        action="store_true",
        help="Show available datasets and optimizers, then exit",
    )
    args = parser.parse_args()

    manifest_tasks: list[BenchmarkTaskSpec] | None = None
    manifest_seed = args.seed
    manifest_test_mode = args.test_mode
    console = Console()

    if args.list_registries:
        _print_registry(console)
        return

    if args.config:
        manifest = load_manifest(args.config)
        manifest_tasks = manifest_to_task_specs(
            manifest, fallback_test_mode=args.test_mode
        )
        if not manifest_tasks:
            raise ValueError("Manifest must contain at least one task.")
        if manifest.seed is not None:
            manifest_seed = manifest.seed
        if manifest.test_mode is not None:
            manifest_test_mode = manifest.test_mode
        _print_manifest_summary(manifest_tasks, console)

    if args.modal:
        # Modal execution
        print("🌩️  Running on Modal (cloud execution)")
        print("-" * 80)

        try:
            import modal  # noqa: F401
        except ModuleNotFoundError:
            print(
                "❌ Modal is not installed. Install it with `pip install modal` or rerun without --modal."
            )
            return

        # Import Modal submission function
        from benchmarks.runners.run_benchmark_modal import app, submit_benchmark_tasks

        # Call the function within app context
        with app.run():
            submit_benchmark_tasks(
                demo_datasets=args.demo_datasets,
                optimizers=args.optimizers,
                models=args.models,
                seed=manifest_seed,
                test_mode=manifest_test_mode,
                max_concurrent=args.max_concurrent,
                retry_failed_run_id=args.retry_failed_run_id,
                resume_run_id=args.resume_run_id,
                task_specs=manifest_tasks,
                manifest_path=args.config,
            )
    else:
        # Local execution
        print("💻 Running locally (local machine execution)")
        print("-" * 80)

        # Import local benchmark function
        from benchmarks.runners.run_benchmark_local import run_benchmark

        run_benchmark(
            demo_datasets=args.demo_datasets,
            optimizers=args.optimizers,
            models=args.models,
            max_workers=args.max_concurrent,
            seed=manifest_seed,
            test_mode=manifest_test_mode,
            checkpoint_dir=args.checkpoint_dir,
            retry_failed_run_id=args.retry_failed_run_id,
            resume_run_id=args.resume_run_id,
            task_specs=manifest_tasks,
            skip_confirmation=manifest_tasks is not None,
            manifest_path=args.config,
        )


if __name__ == "__main__":
    main()
