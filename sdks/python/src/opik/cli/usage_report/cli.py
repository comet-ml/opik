"""CLI command for usage report module."""

import datetime
import json
import os
import sys
import traceback
import webbrowser
from pathlib import Path
from typing import Optional

import click
from rich.console import Console

from .extraction import extract_project_data
from .pdf import create_pdf_report
from .statistics import calculate_statistics

console = Console()


@click.command(name="usage-report")
@click.argument("workspace", type=str)
@click.option(
    "--start-date",
    type=str,
    help="Start date (YYYY-MM-DD). Defaults to None (auto-detect from data).",
)
@click.option(
    "--end-date",
    type=str,
    help="End date (YYYY-MM-DD). Defaults to None (auto-detect from data).",
)
@click.option(
    "--unit",
    type=click.Choice(["month", "week", "day", "hour"], case_sensitive=False),
    default="month",
    help="Time unit for aggregation (month, week, day, or hour). Defaults to 'month'.",
)
@click.option(
    "--output",
    "-o",
    type=click.Path(file_okay=True, dir_okay=False, writable=True),
    default="opik_usage_report.json",
    help="Output JSON file path. Defaults to opik_usage_report.json.",
)
@click.option(
    "--open",
    "open_pdf",
    is_flag=True,
    help="Automatically open the generated PDF report in the default viewer.",
)
@click.pass_context
def usage_report(
    ctx: click.Context,
    workspace: str,
    start_date: Optional[str],
    end_date: Optional[str],
    unit: str,
    output: str,
    open_pdf: bool,
) -> None:
    """
    Extract Opik usage data for a workspace.

    This command extracts project-level metrics from Opik for a specific workspace:
    - Loops through all projects in the workspace
    - Gets trace count, cost, and token count
    - Gets experiment and dataset counts (workspace-level)
    - Aggregates data by the specified time unit (month, week, day, or hour)
    - Saves data to a JSON file
    - Generates a PDF report with charts and statistics

    WORKSPACE: Workspace name to extract data from.

    Examples:

      Extract data with auto-detected date range, aggregated by month (default):

        opik usage-report my-workspace

      Extract data aggregated by week:

        opik usage-report my-workspace --unit week

      Extract data for specific date range, aggregated by day:

        opik usage-report my-workspace --start-date 2024-01-01 --end-date 2024-12-31 --unit day

      Extract data and automatically open the PDF report:

        opik usage-report my-workspace --open
    """
    try:
        # Get API key from context (set by main CLI)
        api_key = ctx.obj.get("api_key") if ctx.obj else None

        # Parse dates if provided, otherwise None
        start_date_obj = None
        if start_date:
            start_date_obj = datetime.datetime.strptime(start_date, "%Y-%m-%d")

        end_date_obj = None
        if end_date:
            end_date_obj = datetime.datetime.strptime(end_date, "%Y-%m-%d")

        console.print("[green]Starting Opik data extraction...[/green]\n")

        data = extract_project_data(
            workspace, api_key, start_date_obj, end_date_obj, unit
        )

        # Calculate and add summary statistics to the data
        console.print("[blue]Calculating summary statistics...[/blue]")
        stats = calculate_statistics(data)
        data["statistics"] = stats

        # Save to JSON file
        console.print(f"\n[cyan]{'='*60}[/cyan]")
        console.print(f"[blue]Saving data to {output}...[/blue]")
        with open(output, "w") as f:
            json.dump(data, f, indent=2, default=str)

        console.print(
            f"[green]Data extraction complete! Results saved to {output}[/green]"
        )

        # Generate PDF report
        console.print(f"\n[cyan]{'='*60}[/cyan]")
        console.print("[blue]Generating PDF report...[/blue]")
        try:
            output_path = Path(output)
            output_dir = output_path.parent if output_path.parent != Path(".") else "."
            pdf_filename = create_pdf_report(data, output_dir=str(output_dir))
            console.print(f"[green]PDF report saved to {pdf_filename}[/green]")

            # Open PDF if --open flag is set
            if open_pdf:
                pdf_path = os.path.abspath(pdf_filename)
                if os.path.exists(pdf_path):
                    webbrowser.open(f"file://{pdf_path}")
                    console.print("[green]Opened PDF in default viewer[/green]")
                else:
                    console.print(
                        f"[yellow]Warning: PDF file not found: {pdf_path}[/yellow]"
                    )
        except Exception as e:
            console.print(
                f"[yellow]Warning: Could not generate PDF report: {e}[/yellow]"
            )
            traceback.print_exc()

    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        sys.exit(1)
