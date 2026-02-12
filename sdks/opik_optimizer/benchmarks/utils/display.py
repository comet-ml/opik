from __future__ import annotations

from collections import defaultdict
from datetime import datetime
import json
from typing import Any

from rich import box
from rich.console import Console
from rich.panel import Panel
from rich.table import Table
from rich.text import Text


def render_active_task_line(
    dataset_name: str,
    optimizer_name: str,
    model_name: str,
    short_id: str | None,
) -> Text:
    del model_name
    if short_id:
        return Text.assemble(
            " • ",
            (f"#{short_id} ", "dim"),
            (f"{dataset_name}", "yellow"),
            (f" [{optimizer_name}]", "dim"),
        )
    return Text.assemble(
        " • ",
        (f"{dataset_name}", "yellow"),
        (f" [{optimizer_name}]", "dim"),
    )


def display_runs_table(runs: list[dict[str, Any]], console: Console) -> None:
    if not runs:
        console.print("[yellow]No benchmark runs found in volume[/yellow]")
        return

    table = Table(show_header=True, header_style="bold magenta", box=box.ROUNDED)
    table.add_column("Run ID", style="cyan", no_wrap=True)
    table.add_column("Timestamp", style="green")
    table.add_column("Datasets", style="yellow")
    table.add_column("Optimizers", style="blue")
    table.add_column("Status", style="white")

    for run in runs:
        run_id = run.get("run_id", "unknown")
        timestamp = run.get("timestamp", "unknown")
        datasets = ", ".join(run.get("demo_datasets", []))
        optimizers = ", ".join(run.get("optimizers", []))
        status = run.get("status", "unknown")

        status_color = "green" if status == "completed" else "yellow"
        table.add_row(
            run_id,
            timestamp,
            datasets or "-",
            optimizers or "-",
            f"[{status_color}]{status}[/{status_color}]",
        )

    console.print(table)


def compute_summary(metadata: dict, tasks: list[dict], call_ids: list[dict]) -> dict:
    total_tasks = metadata.get("total_tasks", len(call_ids))
    completed_tasks = len(tasks)

    success_count = sum(1 for task in tasks if task.get("status") == "Success")
    failed_count = sum(1 for task in tasks if task.get("status") == "Failed")
    running_count = sum(1 for task in tasks if task.get("status") == "Running")

    pending_tasks = total_tasks - completed_tasks

    metrics_by_dataset: dict[str, dict[str, list[dict[str, Any]]]] = defaultdict(
        lambda: {"initial": [], "optimized": []}
    )

    for task in tasks:
        if task.get("status") != "Success":
            continue

        dataset = task.get("dataset_name")
        if not dataset:
            continue

        evals = task.get("evaluations", {}) or {}
        initial_set = evals.get("initial", {})
        final_set = evals.get("final", {})

        def _collect(eval_set: dict, bucket: str) -> None:
            for split_entry in (
                eval_set.get("train"),
                eval_set.get("validation"),
                eval_set.get("test"),
            ):
                if not split_entry:
                    continue
                result = split_entry.get("result") or {}
                for metric in result.get("metrics", []):
                    metrics_by_dataset[dataset][bucket].append(
                        {
                            "metric": metric.get("metric_name"),
                            "score": metric.get("score"),
                        }
                    )

        _collect(initial_set, "initial")
        _collect(final_set, "optimized")

    return {
        "total_tasks": total_tasks,
        "completed_tasks": completed_tasks,
        "pending_tasks": pending_tasks,
        "running_count": running_count,
        "success_count": success_count,
        "failed_count": failed_count,
        "completion_rate": completed_tasks / total_tasks if total_tasks > 0 else 0,
        "success_rate": success_count / completed_tasks if completed_tasks > 0 else 0,
        "metrics_by_dataset": dict(metrics_by_dataset),
    }


def generate_results_display(
    run_id: str, detailed: bool, is_live: bool, results: dict, raw: bool = False
) -> Panel:
    if "error" in results:
        return Panel(
            f"[red]Error: {results['error']}[/red]",
            title=f"Run: {run_id}",
            border_style="red",
        )

    metadata = results["metadata"]
    tasks = results["tasks"]
    if raw:
        return Panel(
            json.dumps(results, indent=2),
            title=f"Run: {run_id} (raw)",
            border_style="cyan",
        )

    summary = compute_summary(metadata, tasks, results.get("call_ids", []))

    content_parts = []
    if is_live:
        content_parts.append(
            f"[dim]Last updated: {datetime.now().strftime('%H:%M:%S')}[/dim]\n"
        )

    content_parts.append("[bold cyan]Run Configuration[/bold cyan]")
    content_parts.append(
        f"  Datasets:  {', '.join(metadata.get('demo_datasets', ['?']))}"
    )
    content_parts.append(
        f"  Optimizers: {', '.join(metadata.get('optimizers', ['?']))}"
    )
    content_parts.append(f"  Models:     {', '.join(metadata.get('models', ['?']))}")
    content_parts.append(f"  Test mode:  {metadata.get('test_mode', '?')}")
    content_parts.append(f"  Max concurrent: {metadata.get('max_concurrent', '?')}")

    content_parts.append("\n[bold yellow]Progress Summary[/bold yellow]")
    content_parts.append(f"  Total tasks:     {summary['total_tasks']}")
    content_parts.append(
        f"  Completed:       {summary['completed_tasks']} ({summary['completion_rate']:.1%})"
    )
    content_parts.append(f"  Pending:         {summary['pending_tasks']}")
    content_parts.append(f"  Running:         {summary['running_count']}")
    content_parts.append(
        f"  Success:         [green]{summary['success_count']}[/green]"
    )
    content_parts.append(f"  Failed:          [red]{summary['failed_count']}[/red]")
    if summary["completed_tasks"] > 0:
        content_parts.append(f"  Success rate:    {summary['success_rate']:.1%}")

    content_parts.append("\n[bold cyan]All Tasks[/bold cyan]")

    workspace = metadata.get("workspace")
    try:
        from modal.config import config_profiles

        profiles = list(config_profiles())  # type: ignore[no-untyped-call]
        if "opik" in profiles:
            workspace = "opik"
        elif profiles:
            workspace = profiles[0]
    except Exception:
        pass

    call_ids = results.get("call_ids", [])
    task_to_call_id = {cid["task_id"]: cid["call_id"] for cid in call_ids}
    all_task_ids = [cid["task_id"] for cid in call_ids]
    completed_task_ids = {task["id"]: task for task in tasks}

    for task_id in all_task_ids:
        call_id = task_to_call_id.get(task_id)
        if task_id in completed_task_ids:
            task_data = completed_task_ids[task_id]
            status = task_data.get("status", "Unknown")
            if status == "Success":
                status_display = "[green]Success[/green]"
            elif status == "Failed":
                status_display = "[red]Failed[/red]"
            elif status == "Running":
                status_display = "[yellow]Running[/yellow]"
            else:
                status_display = f"[yellow]{status}[/yellow]"
        else:
            status_display = "[yellow]Pending[/yellow]"

        if call_id and workspace:
            logs_url = f"https://modal.com/apps/{workspace}/main/deployed/opik-optimizer-benchmarks?&&activeTab=logs&fcId={call_id}"
            content_parts.append(
                f"  • {task_id}: {status_display} - [link={logs_url}][cyan]View logs[/cyan][/link]"
            )
        else:
            content_parts.append(f"  • {task_id}: {status_display}")

    if detailed and tasks:
        content_parts.append("\n[bold magenta]Detailed Results[/bold magenta]")
        tasks_by_dataset = defaultdict(list)
        for task in tasks:
            if task.get("status") == "Success":
                tasks_by_dataset[task["dataset_name"]].append(task)

        for dataset, dataset_tasks in sorted(tasks_by_dataset.items()):
            content_parts.append(f"\n  [cyan]{dataset}[/cyan]:")
            for task in dataset_tasks:
                optimizer = task["optimizer_name"]
                model = task["model_name"]
                content_parts.append(f"    {optimizer} + {model}:")

    return Panel(
        "\n".join(content_parts),
        title=f"Benchmark Results: {run_id}",
        border_style="green" if summary["failed_count"] == 0 else "yellow",
        padding=(1, 2),
    )
