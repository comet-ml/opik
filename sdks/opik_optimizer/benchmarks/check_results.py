"""
Check and display benchmark results from Modal Volume.

This script retrieves results from completed benchmark runs and generates reports.
It can be run at any time to check the status of running or completed benchmarks.

Usage:
    # Check results for a specific run
    modal run benchmarks/check_results.py --run-id run_20250423_153045

    # Watch for new results (live monitoring)
    modal run benchmarks/check_results.py --run-id run_20250423_153045 --watch

    # List all available runs
    modal run benchmarks/check_results.py --list-runs

    # Generate detailed report with all metrics
    modal run benchmarks/check_results.py --run-id run_20250423_153045 --detailed
"""

import argparse
import json
import sys
import time
from collections import defaultdict
from typing import Any
from datetime import datetime
from pathlib import Path

import modal

# Import rich - needed for helper functions
try:
    from rich.console import Console
    from rich.panel import Panel
    from rich.table import Table
    from rich.live import Live
    from rich import box
except ImportError:
    # Rich not installed - will fail if user tries to run check_results
    pass

# Define Modal app
app = modal.App("opik-optimizer-benchmarks-results")

# Create image with rich for remote functions
image = modal.Image.debian_slim(python_version="3.12").pip_install("rich")

# Access the results volume
results_volume = modal.Volume.from_name(
    "opik-benchmark-results", create_if_missing=True
)

# Initialize console (will be overridden in main if needed)
console: Any = None


@app.function(image=image, volumes={"/results": results_volume})
def list_available_runs() -> list[dict]:
    """List all available benchmark runs in the Volume."""
    results_dir = Path("/results")

    if not results_dir.exists():
        return []

    runs = []
    for run_dir in results_dir.iterdir():
        if run_dir.is_dir() and run_dir.name.startswith("run_"):
            metadata_file = run_dir / "metadata.json"
            if metadata_file.exists():
                with open(metadata_file) as f:
                    metadata = json.load(f)
                    runs.append(metadata)
            else:
                # No metadata, just include basic info
                runs.append(
                    {
                        "run_id": run_dir.name,
                        "timestamp": "unknown",
                    }
                )

    return sorted(runs, key=lambda x: x.get("timestamp", ""), reverse=True)


@app.function(image=image, volumes={"/results": results_volume})
def load_run_results(run_id: str) -> dict:
    """
    Load all results for a specific run.

    Returns:
        Dictionary containing:
        - metadata: Run configuration
        - tasks: List of task results
        - call_ids: List of function call IDs
        - summary: Summary statistics
    """
    run_dir = Path("/results") / run_id

    if not run_dir.exists():
        return {"error": f"Run {run_id} not found"}

    # Load metadata
    metadata_file = run_dir / "metadata.json"
    metadata = {}
    if metadata_file.exists():
        with open(metadata_file) as f:
            metadata = json.load(f)

    # Load call IDs
    call_ids_file = run_dir / "call_ids.json"
    call_ids = []
    if call_ids_file.exists():
        with open(call_ids_file) as f:
            call_ids = json.load(f)

    # Load task results
    tasks_dir = run_dir / "tasks"
    tasks = []
    if tasks_dir.exists():
        for task_file in tasks_dir.glob("*.json"):
            try:
                with open(task_file) as f:
                    task_data = json.load(f)
                    tasks.append(task_data)
            except Exception as e:
                print(f"⚠️  Error loading {task_file.name}: {e}")

    # Compute summary statistics
    summary = _compute_summary(metadata, tasks, call_ids)

    return {
        "metadata": metadata,
        "tasks": tasks,
        "call_ids": call_ids,
        "summary": summary,
    }


def _compute_summary(metadata: dict, tasks: list[dict], call_ids: list[dict]) -> dict:
    """Compute summary statistics for a run."""
    total_tasks = metadata.get("total_tasks", len(call_ids))
    completed_tasks = len(tasks)
    pending_tasks = total_tasks - completed_tasks

    # Count success/failure
    success_count = sum(1 for task in tasks if task.get("status") == "Success")
    failed_count = sum(1 for task in tasks if task.get("status") == "Failed")

    # Compute metrics by dataset and optimizer
    metrics_by_dataset: dict[str, dict[str, list[dict[str, Any]]]] = defaultdict(
        lambda: {"initial": [], "optimized": []}
    )

    for task in tasks:
        if task.get("status") != "Success":
            continue

        dataset = task.get("dataset_name")
        if not dataset:
            continue

        initial_eval = task.get("initial_evaluation", {})
        optimized_eval = task.get("optimized_evaluation", {})

        if initial_eval and "metrics" in initial_eval:
            for metric in initial_eval["metrics"]:
                metrics_by_dataset[dataset]["initial"].append(
                    {
                        "metric": metric.get("metric_name"),
                        "score": metric.get("score"),
                    }
                )

        if optimized_eval and "metrics" in optimized_eval:
            for metric in optimized_eval["metrics"]:
                metrics_by_dataset[dataset]["optimized"].append(
                    {
                        "metric": metric.get("metric_name"),
                        "score": metric.get("score"),
                    }
                )

    return {
        "total_tasks": total_tasks,
        "completed_tasks": completed_tasks,
        "pending_tasks": pending_tasks,
        "success_count": success_count,
        "failed_count": failed_count,
        "completion_rate": completed_tasks / total_tasks if total_tasks > 0 else 0,
        "success_rate": success_count / completed_tasks if completed_tasks > 0 else 0,
        "metrics_by_dataset": dict(metrics_by_dataset),
    }


@app.local_entrypoint()
def main(
    run_id: str | None = None,
    list_runs: bool = False,
    watch: bool = False,
    detailed: bool = False,
    watch_interval: int = 30,
) -> None:
    """
    Check and display benchmark results.

    Args:
        run_id: ID of the run to check
        list_runs: List all available runs
        watch: Continuously monitor for new results
        detailed: Show detailed metrics breakdown
        watch_interval: Seconds between updates when watching (default: 30)
    """
    global console
    console = Console()

    if list_runs:
        _list_runs()
        return

    if run_id is None:
        console.print("[red]❌ Error: --run-id is required[/red]")
        console.print("Use --list-runs to see available runs")
        sys.exit(1)

    if watch:
        _watch_results(run_id, watch_interval, detailed)
    else:
        _display_results(run_id, detailed)


def _list_runs() -> None:
    """Display list of all available runs."""
    console.print("\n[bold]📋 Available Benchmark Runs[/bold]\n")

    runs = list_available_runs.remote()

    if not runs:
        console.print("[yellow]No runs found in Modal Volume[/yellow]")
        return

    table = Table(box=box.ROUNDED)
    table.add_column("Run ID", style="cyan")
    table.add_column("Timestamp", style="green")
    table.add_column("Datasets", style="blue")
    table.add_column("Optimizers", style="magenta")
    table.add_column("Total Tasks", style="yellow")

    for run in runs:
        datasets = ", ".join(run.get("demo_datasets", ["unknown"]))[:40]
        optimizers = ", ".join(run.get("optimizers", ["unknown"]))[:40]
        total_tasks = str(run.get("total_tasks", "?"))

        table.add_row(
            run["run_id"],
            run.get("timestamp", "unknown"),
            datasets,
            optimizers,
            total_tasks,
        )

    console.print(table)
    console.print(
        "\n💡 Check results: [cyan]modal run benchmarks/check_results.py --run-id <RUN_ID>[/cyan]\n"
    )


def _watch_results(run_id: str, interval: int, detailed: bool) -> None:
    """Continuously monitor and display results."""
    console.print(f"\n[bold]👀 Watching results for run: {run_id}[/bold]")
    console.print(f"Refreshing every {interval} seconds. Press Ctrl+C to stop.\n")

    try:
        with Live(console=console, refresh_per_second=1) as live:
            while True:
                display = _generate_results_display(run_id, detailed, is_live=True)
                live.update(display)
                time.sleep(interval)
    except KeyboardInterrupt:
        console.print("\n[yellow]Stopped watching[/yellow]")


def _display_results(run_id: str, detailed: bool) -> None:
    """Display results once."""
    display = _generate_results_display(run_id, detailed, is_live=False)
    console.print(display)


def _generate_results_display(run_id: str, detailed: bool, is_live: bool) -> Panel:
    """Generate rich display of results."""
    results = load_run_results.remote(run_id)

    if "error" in results:
        return Panel(
            f"[red]❌ {results['error']}[/red]",
            title=f"Run: {run_id}",
            border_style="red",
        )

    metadata = results["metadata"]
    summary = results["summary"]
    tasks = results["tasks"]

    # Build display content
    content_parts = []

    # Header with metadata
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

    # Progress summary
    content_parts.append("\n[bold yellow]Progress Summary[/bold yellow]")
    content_parts.append(f"  Total tasks:     {summary['total_tasks']}")
    content_parts.append(
        f"  Completed:       {summary['completed_tasks']} ({summary['completion_rate']:.1%})"
    )
    content_parts.append(f"  Pending:         {summary['pending_tasks']}")
    content_parts.append(
        f"  Success:         [green]{summary['success_count']}[/green]"
    )
    content_parts.append(f"  Failed:          [red]{summary['failed_count']}[/red]")

    if summary["completed_tasks"] > 0:
        content_parts.append(f"  Success rate:    {summary['success_rate']:.1%}")

    # Detailed metrics
    if detailed and tasks:
        content_parts.append("\n[bold magenta]Detailed Results[/bold magenta]")

        # Group by dataset
        tasks_by_dataset = defaultdict(list)
        for task in tasks:
            if task.get("status") == "Success":
                tasks_by_dataset[task["dataset_name"]].append(task)

        for dataset, dataset_tasks in sorted(tasks_by_dataset.items()):
            content_parts.append(f"\n  [cyan]{dataset}[/cyan]:")

            for task in dataset_tasks:
                optimizer = task["optimizer_name"]
                model = task["model_name"]

                initial_eval = task.get("initial_evaluation", {})
                optimized_eval = task.get("optimized_evaluation", {})

                content_parts.append(f"    {optimizer} + {model}:")

                if initial_eval and "metrics" in initial_eval:
                    for metric in initial_eval["metrics"]:
                        metric_name = metric.get("metric_name", "unknown")
                        initial_score = metric.get("score", 0)

                        # Find corresponding optimized score
                        optimized_score = None
                        if optimized_eval and "metrics" in optimized_eval:
                            for opt_metric in optimized_eval["metrics"]:
                                if opt_metric.get("metric_name") == metric_name:
                                    optimized_score = opt_metric.get("score", 0)
                                    break

                        if optimized_score is not None:
                            improvement = optimized_score - initial_score
                            improvement_pct = (
                                (improvement / initial_score * 100)
                                if initial_score != 0
                                else 0
                            )

                            if improvement > 0:
                                color = "green"
                                arrow = "↑"
                            elif improvement < 0:
                                color = "red"
                                arrow = "↓"
                            else:
                                color = "white"
                                arrow = "→"

                            content_parts.append(
                                f"      {metric_name}: {initial_score:.3f} → [{color}]{optimized_score:.3f}[/{color}] "
                                f"([{color}]{arrow} {improvement:+.3f} / {improvement_pct:+.1f}%[/{color}])"
                            )

    # Failed tasks
    failed_tasks = [t for t in tasks if t.get("status") == "Failed"]
    if failed_tasks:
        content_parts.append("\n[bold red]Failed Tasks[/bold red]")
        for task in failed_tasks[:5]:  # Show first 5 failures
            content_parts.append(f"  • {task['id']}")
            error = task.get("error_message", "Unknown error")
            # Show first line of error
            error_first_line = error.split("\n")[0][:80]
            content_parts.append(f"    [dim]{error_first_line}[/dim]")

        if len(failed_tasks) > 5:
            content_parts.append(f"  [dim]... and {len(failed_tasks) - 5} more[/dim]")

    # Status indicator
    if summary["pending_tasks"] > 0:
        status_color = "yellow"
        status_icon = "⏳"
        status_text = "IN PROGRESS"
    elif summary["failed_count"] > 0:
        status_color = "red"
        status_icon = "⚠️"
        status_text = "COMPLETED WITH FAILURES"
    else:
        status_color = "green"
        status_icon = "✅"
        status_text = "COMPLETED SUCCESSFULLY"

    title = f"{status_icon} Run: {run_id} - {status_text}"

    return Panel(
        "\n".join(content_parts),
        title=title,
        border_style=status_color,
        expand=False,
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Check and display benchmark results from Modal",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # List all available runs
  modal run benchmarks/check_results.py --list-runs

  # Check results for a specific run
  modal run benchmarks/check_results.py --run-id run_20250423_153045

  # Watch for new results (live monitoring)
  modal run benchmarks/check_results.py --run-id run_20250423_153045 --watch

  # Show detailed metrics breakdown
  modal run benchmarks/check_results.py --run-id run_20250423_153045 --detailed
        """,
    )
    parser.add_argument(
        "--run-id",
        type=str,
        default=None,
        help="ID of the benchmark run to check",
    )
    parser.add_argument(
        "--list-runs",
        action="store_true",
        help="List all available benchmark runs",
    )
    parser.add_argument(
        "--watch",
        action="store_true",
        help="Continuously monitor for new results",
    )
    parser.add_argument(
        "--detailed",
        action="store_true",
        help="Show detailed metrics breakdown",
    )
    parser.add_argument(
        "--watch-interval",
        type=int,
        default=30,
        help="Seconds between updates when watching (default: 30)",
    )

    args = parser.parse_args()
