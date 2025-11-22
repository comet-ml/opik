"""
Check and display benchmark results from Modal Volume.

This script retrieves results from completed benchmark runs and generates reports.
It can be run at any time to check the status of running or completed benchmarks.

Usage:
    # Check results for a specific run
    modal run check_results.py --run-id run_20250423_153045

    # Watch for new results (live monitoring)
    modal run check_results.py --run-id run_20250423_153045 --watch

    # List all available runs
    modal run check_results.py --list-runs

    # Generate detailed report with all metrics
    modal run check_results.py --run-id run_20250423_153045 --detailed
"""

import argparse
import os
import sys
import time
from typing import Any

import modal
from rich.console import Console
from rich.live import Live
from rich.table import Table
from rich.panel import Panel

# Define Modal app
app = modal.App("opik-optimizer-benchmarks-results")

# Create image with rich and local benchmarks directory
image = (
    modal.Image.debian_slim(python_version="3.12")
    .pip_install("opik_optimizer>=2.1.3", "rich")
    .add_local_dir(
        local_path=os.path.dirname(os.path.abspath(__file__)),
        remote_path="/root/benchmarks",
        ignore=["__pycache__", ".venv", "benchmark_results"],
    )
)

# Access the results volume
results_volume = modal.Volume.from_name(
    "opik-benchmark-results", create_if_missing=True
)

# Initialize console
console: Console | None = None


@app.function(image=image, volumes={"/results": results_volume})
def list_available_runs() -> list[dict]:
    """List all available benchmark runs in the Volume."""
    sys.path.insert(0, "/root/benchmarks")
    from benchmarks.modal_utils.storage import list_available_runs_from_volume

    return list_available_runs_from_volume()


@app.function(image=image, volumes={"/results": results_volume})
def load_run_results(run_id: str) -> dict:
    """
    Load all results for a specific run.

    Returns:
        Dictionary containing:
        - metadata: Run configuration
        - tasks: List of task results
        - call_ids: List of function call IDs
    """
    sys.path.insert(0, "/root/benchmarks")
    from benchmarks.modal_utils.storage import load_run_results_from_volume

    return load_run_results_from_volume(run_id)


@app.local_entrypoint()
def main(
    run_id: str | None = None,
    list_runs: bool = False,
    watch: bool = False,
    detailed: bool = False,
    raw: bool = False,
    show_errors: bool = False,
    task: str | None = None,
    watch_interval: int = 30,
) -> None:
    """
    Check and display benchmark results.

    Args:
        run_id: ID of the run to check
        list_runs: List all available runs
        watch: Continuously monitor for new results
        detailed: Show detailed metrics breakdown
        show_errors: Show full error messages for failed tasks
        task: Filter to specific task ID (can be partial match)
        watch_interval: Seconds between updates when watching (default: 30)
    """
    global console
    console = Console()

    if list_runs:
        _list_runs()
        return

    if run_id is None:
        # In non-interactive environments (e.g., modal run), refuse to prompt.
        if not sys.stdin.isatty():
            assert console is not None
            console.print(
                "\n[red]No run-id provided and interactive input is unavailable.[/red]\n"
                "Pass a run id explicitly, e.g.:\n"
                "  modal run benchmarks/check_results.py --run-id <run_id>\n"
                "or list runs first:\n"
                "  modal run benchmarks/check_results.py --list-runs\n"
            )
            sys.exit(1)
        run_id = _select_run()
        if run_id is None:
            sys.exit(1)

    if show_errors or task:
        _show_errors(run_id, task)
    elif watch:
        _watch_results(run_id, watch_interval, detailed)
    else:
        _display_results(run_id, detailed, raw)


def _list_runs() -> None:
    """Display list of all available runs."""
    from benchmarks.modal_utils.display import display_runs_table

    assert console is not None
    console.print("\n[bold]📋 Available Benchmark Runs[/bold]\n")

    runs = list_available_runs.remote()

    display_runs_table(runs, console)


def _select_run() -> str | None:
    """Interactively select a run from the most recent runs."""

    assert console is not None
    runs = list_available_runs.remote()
    if not runs:
        console.print("[red]No runs found in the volume.[/red]")
        return None

    console.print("\n[bold]📋 Select a run[/bold]\n")
    # Show top 15
    recent = runs[:15]
    table = Table(show_header=True, header_style="bold", box=None, padding=(0, 1))
    table.add_column("#", style="dim", width=3)
    table.add_column("Run ID", no_wrap=True)
    table.add_column("Timestamp", no_wrap=True)
    table.add_column("Datasets")
    for idx, meta in enumerate(recent, 1):
        table.add_row(
            str(idx),
            meta.get("run_id", "?"),
            meta.get("timestamp", "?"),
            ", ".join(meta.get("demo_datasets", [])) or "-",
        )
    console.print(table)

    choice = console.input("\nSelect run number (or 'q' to quit): ").strip()
    if choice.lower() == "q":
        return None
    try:
        choice_idx = int(choice)
        if 1 <= choice_idx <= len(recent):
            return recent[choice_idx - 1].get("run_id")
    except ValueError:
        pass
    console.print("[red]Invalid selection[/red]")
    return None


def _watch_results(run_id: str, interval: int, detailed: bool) -> None:
    """Continuously monitor and display results."""
    assert console is not None
    console.print(f"\n[bold]👀 Watching results for run: {run_id}[/bold]")
    console.print(f"Refreshing every {interval} seconds. Press Ctrl+C to stop.\n")

    try:
        with Live(console=console, refresh_per_second=1) as live:
            while True:
                display = _generate_results_display(
                    run_id, detailed, is_live=True, raw=False
                )
                live.update(display)
                time.sleep(interval)
    except KeyboardInterrupt:
        console.print("\n[yellow]Stopped watching[/yellow]")


def _display_results(run_id: str, detailed: bool, raw: bool) -> None:
    """Display results once."""
    assert console is not None
    display = _generate_results_display(run_id, detailed, is_live=False, raw=raw)
    console.print(display)


def _show_errors(run_id: str, task_filter: str | None = None) -> None:
    """Display error messages and info for tasks."""
    from rich.syntax import Syntax

    assert console is not None

    if task_filter:
        console.print(f"\n[bold cyan]📋 Task Info: {task_filter}[/bold cyan]\n")

        # Load results to find the task and call_id
        results = load_run_results.remote(run_id)

        if "error" in results:
            console.print(
                Panel(f"[red]❌ {results['error']}[/red]", border_style="red")
            )
            return

        # Find the task in results
        tasks = results.get("tasks", [])
        task = None
        for t in tasks:
            if t.get("id") == task_filter:
                task = t
                break

        if not task:
            console.print(f"[red]❌ Task '{task_filter}' not found[/red]\n")
            console.print("[yellow]Available tasks:[/yellow]")
            for t in tasks[:20]:
                status_color = "green" if t.get("status") == "Success" else "red"
                console.print(
                    f"  [{status_color}]•[/{status_color}] {t.get('id', 'unknown')}"
                )
            if len(tasks) > 20:
                console.print(f"  [dim]... and {len(tasks) - 20} more[/dim]")
            return

        # Find the call_id
        console.log("results")
        console.log(results)
        call_ids = results.get("call_ids", [])
        call_id = None
        for cid in call_ids:
            if cid.get("task_id") == task_filter:
                call_id = cid.get("call_id")
                break

        # Display task info
        status = task.get("status", "Unknown")
        status_color = "green" if status == "Success" else "red"

        console.print(f"[bold]Status:[/bold] [{status_color}]{status}[/{status_color}]")
        console.print(f"[bold]Dataset:[/bold] {task.get('dataset_name', 'unknown')}")
        console.print(
            f"[bold]Optimizer:[/bold] {task.get('optimizer_name', 'unknown')}"
        )
        console.print(f"[bold]Model:[/bold] {task.get('model_name', 'unknown')}\n")

        # Show error if failed
        if status == "Failed":
            error_msg = task.get("error_message", "No error message available")
            console.print(
                Panel(
                    Syntax(error_msg, "python", theme="monokai", word_wrap=True),
                    title="[red]Error Message[/red]",
                    border_style="red",
                )
            )
            console.print()

        # Show call_id and try to construct logs URL
        if call_id:
            # Try to get workspace from metadata
            metadata = results.get("metadata", {})
            workspace = metadata.get("workspace")

            if workspace:
                # Construct the logs URL
                logs_url = f"https://modal.com/apps/{workspace}/main/deployed/opik-optimizer-benchmarks?&&activeTab=logs&fcId={call_id}"
                console.print(
                    Panel(
                        f"[bold]Function Call ID:[/bold]\n[cyan]{call_id}[/cyan]\n\n[bold]View Logs:[/bold]\n[link={logs_url}]{logs_url}[/link]",
                        title="[cyan]📋 Logs[/cyan]",
                        border_style="cyan",
                    )
                )
            else:
                console.print(
                    Panel(
                        f"[cyan]{call_id}[/cyan]\n\n[dim]Search for this call ID in your Modal dashboard to view logs[/dim]",
                        title="[cyan]📋 Function Call ID[/cyan]",
                        border_style="cyan",
                    )
                )
        return

    # No filter - show all failed tasks
    console.print(f"\n[bold red]🔍 Error Details for Run: {run_id}[/bold red]\n")

    results = load_run_results.remote(run_id)

    if "error" in results:
        console.print(Panel(f"[red]❌ {results['error']}[/red]", border_style="red"))
        return

    tasks = results.get("tasks", [])
    failed_tasks = [t for t in tasks if t.get("status") == "Failed"]

    if not failed_tasks:
        console.print("[green]✅ No failed tasks found![/green]")
        return

    console.print(f"[yellow]Found {len(failed_tasks)} failed task(s)[/yellow]\n")

    for i, task in enumerate(failed_tasks, 1):
        task_id = task.get("id", "unknown")
        error_msg = task.get("error_message", "No error message available")

        console.print(
            Panel(
                Syntax(error_msg, "python", theme="monokai", word_wrap=True),
                title=f"[{i}/{len(failed_tasks)}] {task_id}",
                border_style="red",
                expand=False,
            )
        )
        console.print()  # Empty line between errors


def _generate_results_display(
    run_id: str, detailed: bool, is_live: bool, raw: bool
) -> Any:
    """Generate rich display of results."""
    from benchmarks.modal_utils.display import generate_results_display

    results = load_run_results.remote(run_id)

    return generate_results_display(run_id, detailed, is_live, results, raw)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Check and display benchmark results from Modal",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # List all available runs
  modal run check_results.py --list-runs

  # Check results for a specific run
  modal run check_results.py --run-id run_20250423_153045

  # Watch for new results (live monitoring)
  modal run check_results.py --run-id run_20250423_153045 --watch

  # Show detailed metrics breakdown
  modal run check_results.py --run-id run_20250423_153045 --detailed

  # Show full error messages for failed tasks
  modal run check_results.py --run-id run_20250423_153045 --show-errors

  # Show full logs and details for a specific task (exact task ID)
  modal run check_results.py --run-id run_20250423_153045 --task "ai2_arc_evolutionary_optimizer_openai/gpt-4o-mini"
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
        "--show-errors",
        action="store_true",
        help="Show full error messages for all failed tasks",
    )
    parser.add_argument(
        "--raw",
        action="store_true",
        help="Show raw JSON payload (otherwise a concise summary table is shown)",
    )
    parser.add_argument(
        "--task",
        type=str,
        default=None,
        help="Show full logs and details for specific task ID (exact match required)",
    )
    parser.add_argument(
        "--watch-interval",
        type=int,
        default=30,
        help="Seconds between updates when watching (default: 30)",
    )

    args = parser.parse_args()
