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
import sys
import time
from typing import Any

import modal
from rich.console import Console
from rich.live import Live

# Import display logic
from modal_utils.display import display_runs_table, generate_results_display
from modal_utils.storage import (
    list_available_runs_from_volume,
    load_run_results_from_volume,
)

# Define Modal app
app = modal.App("opik-optimizer-benchmarks-results")

# Create image with rich for remote functions
image = modal.Image.debian_slim(python_version="3.12").pip_install("rich")

# Access the results volume
results_volume = modal.Volume.from_name(
    "opik-benchmark-results", create_if_missing=True
)

# Initialize console
console: Console | None = None


@app.function(image=image, volumes={"/results": results_volume})
def list_available_runs() -> list[dict]:
    """List all available benchmark runs in the Volume."""
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
    return load_run_results_from_volume(run_id)


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
    assert console is not None
    console.print("\n[bold]📋 Available Benchmark Runs[/bold]\n")

    runs = list_available_runs.remote()

    display_runs_table(runs, console)


def _watch_results(run_id: str, interval: int, detailed: bool) -> None:
    """Continuously monitor and display results."""
    assert console is not None
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
    assert console is not None
    display = _generate_results_display(run_id, detailed, is_live=False)
    console.print(display)


def _generate_results_display(run_id: str, detailed: bool, is_live: bool) -> Any:
    """Generate rich display of results."""
    results = load_run_results.remote(run_id)

    return generate_results_display(run_id, detailed, is_live, results)


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
