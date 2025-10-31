"""Results display logic for Modal benchmarks."""

from collections import defaultdict
from datetime import datetime
from typing import Any

from rich import box
from rich.console import Console
from rich.panel import Panel
from rich.table import Table


def compute_summary(metadata: dict, tasks: list[dict], call_ids: list[dict]) -> dict:
    """
    Compute summary statistics for a benchmark run.

    Args:
        metadata: Run metadata dictionary
        tasks: List of task result dictionaries
        call_ids: List of function call ID dictionaries

    Returns:
        Summary statistics dictionary
    """
    total_tasks = metadata.get("total_tasks", len(call_ids))
    completed_tasks = len(tasks)
    pending_tasks = total_tasks - completed_tasks

    # Count success/failure
    success_count = sum(1 for task in tasks if task.get("status") == "Success")
    failed_count = sum(1 for task in tasks if task.get("status") == "Failed")

    # Compute metrics by dataset
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


def generate_results_display(
    run_id: str, detailed: bool, is_live: bool, results: dict
) -> Panel:
    """
    Generate rich display of benchmark results.

    Args:
        run_id: Run identifier
        detailed: Whether to show detailed metrics
        is_live: Whether this is a live/watching display
        results: Results dictionary from load_run_results

    Returns:
        Rich Panel object for display
    """
    if "error" in results:
        return Panel(
            f"[red]❌ {results['error']}[/red]",
            title=f"Run: {run_id}",
            border_style="red",
        )

    metadata = results["metadata"]
    tasks = results["tasks"]
    print(results)
    # Compute summary
    summary = compute_summary(metadata, tasks, results.get("call_ids", []))

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

    # All tasks list with status and logs links
    content_parts.append("\n[bold cyan]All Tasks[/bold cyan]")

    # Get workspace and worker_app_id for URL construction
    workspace = metadata.get("workspace")

    # Create a map of task_id to call_id
    call_ids = results.get("call_ids", [])
    task_to_call_id = {cid["task_id"]: cid["call_id"] for cid in call_ids}

    # Get all expected task IDs from call_ids
    all_task_ids = [cid["task_id"] for cid in call_ids]

    # Map completed tasks
    completed_task_ids = {task["id"]: task for task in tasks}

    for task_id in all_task_ids:
        call_id = task_to_call_id.get(task_id)

        # Determine status
        if task_id in completed_task_ids:
            task_data = completed_task_ids[task_id]
            status = task_data.get("status", "Unknown")
            if status == "Success":
                status_display = "[green]✓ Success[/green]"
            elif status == "Failed":
                status_display = "[red]✗ Failed[/red]"
            else:
                status_display = f"[yellow]{status}[/yellow]"
        else:
            status_display = "[yellow]⏳ Pending[/yellow]"

        # Build logs URL if possible
        if call_id and workspace:
            logs_url = f"https://modal.com/apps/{workspace}/main/deployed/opik-optimizer-benchmarks?&&activeTab=logs&fcId={call_id}"
            content_parts.append(
                f"  • {task_id}: {status_display} - [link={logs_url}][cyan]View logs[/cyan][/link]"
            )
        else:
            content_parts.append(f"  • {task_id}: {status_display}")

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


def display_runs_table(runs: list[dict], console: Console) -> None:
    """
    Display a table of available benchmark runs.

    Args:
        runs: List of run metadata dictionaries
        console: Rich Console object
    """
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
