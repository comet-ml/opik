#!/usr/bin/env python3
"""Unified benchmark runner powered by pluggable execution engines."""

from __future__ import annotations

import argparse
import os
from typing import Any

from rich import box
from rich.console import Console
from rich.panel import Panel
from rich.table import Table

from benchmarks.packages import registry as benchmark_config
from benchmarks.core.planning import PlanInput, compile_task_plan
from benchmarks.core.runtime import deploy_engine, run_plan
from benchmarks.core.types import TaskSpec
from benchmarks.engines.registry import list_engines

try:
    from opik_optimizer.constants import DEFAULT_BENCHMARK_MAX_CONCURRENT
except Exception:
    DEFAULT_BENCHMARK_MAX_CONCURRENT = 5


def _print_manifest_summary(tasks: list[TaskSpec], console: Console) -> None:
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

    engine_table = Table(title="Engines", box=box.SIMPLE, expand=True)
    engine_table.add_column("Engine")
    for name in list_engines():
        engine_table.add_row(name)

    console.print(Panel(ds_table, title="Dataset Registry", border_style="cyan"))
    console.print(Panel(opt_table, title="Optimizer Registry", border_style="cyan"))
    console.print(Panel(engine_table, title="Engine Registry", border_style="cyan"))


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run benchmarks for prompt optimization using pluggable engines",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )

    parser.add_argument(
        "--engine",
        type=str,
        choices=list_engines(),
        default="local",
        help="Benchmark engine to use",
    )
    parser.add_argument(
        "--modal",
        action="store_true",
        help="Alias for --engine modal",
    )
    parser.add_argument(
        "--deploy-engine",
        action="store_true",
        help="Deploy engine infrastructure (if supported) and exit",
    )

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
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--max-concurrent",
        type=int,
        default=DEFAULT_BENCHMARK_MAX_CONCURRENT,
    )
    parser.add_argument(
        "--checkpoint-dir",
        type=str,
        default=os.path.join(
            os.path.expanduser("~"), ".opik_optimizer", "benchmark_results"
        ),
    )
    parser.add_argument("--retry-failed-run-id", type=str, default=None)
    parser.add_argument("--resume-run-id", type=str, default=None)
    parser.add_argument("--config", type=str, default=None)
    parser.add_argument(
        "--yes",
        action="store_true",
        default=False,
        help="Skip interactive confirmation prompts for large local runs",
    )
    parser.add_argument("--list-registries", action="store_true")
    args = parser.parse_args()

    console = Console()
    if args.list_registries:
        _print_registry(console)
        return

    engine_name = "modal" if args.modal else args.engine

    plan = compile_task_plan(
        PlanInput(
            demo_datasets=args.demo_datasets,
            optimizers=args.optimizers,
            models=args.models,
            seed=args.seed,
            test_mode=args.test_mode,
            max_concurrent=args.max_concurrent,
            checkpoint_dir=args.checkpoint_dir,
            auto_confirm=args.yes,
            config_path=args.config,
            retry_failed_run_id=args.retry_failed_run_id,
            resume_run_id=args.resume_run_id,
        )
    )

    if plan.manifest_path:
        _print_manifest_summary(plan.tasks, console)

    if args.deploy_engine:
        summary = deploy_engine(engine_name)
        console.print(
            Panel(
                f"Engine '{summary.engine}' deployed.\n{summary.metadata}",
                title="Deployment",
                border_style="green",
            )
        )
        return

    summary = run_plan(engine_name, plan)
    console.print(
        Panel(
            f"Engine: {summary.engine}\nRun ID: {summary.run_id or 'n/a'}\nStatus: {summary.status}",
            title="Run Complete",
            border_style="green" if summary.status == "succeeded" else "red",
        )
    )


if __name__ == "__main__":
    main()
