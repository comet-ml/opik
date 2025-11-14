"""CLI command for usage report module."""

import datetime
import json
import os
import sys
import traceback
import webbrowser
from pathlib import Path
from typing import List, Optional

import click
from rich.console import Console

import opik.config as config
import opik.url_helpers as url_helpers
import opik.httpx_client as httpx_client

from .extraction import extract_project_data
from .pdf import create_pdf_report
from .statistics import calculate_statistics

console = Console()


@click.command(name="usage-report")
@click.argument("workspaces", nargs=-1, type=str)
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
@click.option(
    "--from-json",
    "from_json",
    is_flag=True,
    help="Load data from existing JSON files instead of extracting from API. "
    "JSON files should match the output pattern (e.g., opik_usage_report.json or opik_usage_report_{workspace}.json).",
)
@click.pass_context
def usage_report(
    ctx: click.Context,
    workspaces: tuple,
    start_date: Optional[str],
    end_date: Optional[str],
    unit: str,
    output: str,
    open_pdf: bool,
    from_json: bool,
) -> None:
    """
    Extract Opik usage data for one or more workspaces.

    This command extracts project-level metrics from Opik for specified workspace(s):
    - Loops through all projects in each workspace
    - Gets trace count, cost, and token count
    - Gets experiment and dataset counts (workspace-level)
    - Aggregates data by the specified time unit (month, week, day, or hour)
    - Saves data to a JSON file
    - Generates a PDF report with charts and statistics

    WORKSPACES: Zero or more workspace names to extract data from.
                If no workspaces are provided, all workspaces will be processed.

    Examples:

      Extract data for a single workspace with auto-detected date range:

        opik usage-report my-workspace

      Extract data for multiple workspaces:

        opik usage-report workspace1 workspace2 workspace3

      Extract data for all workspaces (no workspace specified):

        opik usage-report

      Extract data aggregated by week:

        opik usage-report my-workspace --unit week

      Extract data for specific date range, aggregated by day:

        opik usage-report my-workspace --start-date 2024-01-01 --end-date 2024-12-31 --unit day

      Extract data and automatically open the PDF report:

        opik usage-report my-workspace --open

      Generate PDF from existing JSON file (skip data extraction):

        opik usage-report my-workspace --from-json
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

        # Determine which workspaces to process
        workspaces_list: List[str] = list(workspaces) if workspaces else []

        # If no workspaces provided, fetch all workspaces
        if not workspaces_list:
            console.print(
                "[blue]No workspaces specified. Fetching all workspaces...[/blue]"
            )
            cfg = config.OpikConfig()
            # Use API key from context if available, otherwise from config
            api_key_to_use = api_key or cfg.api_key
            with httpx_client.get(
                workspace=None,  # No workspace needed when fetching workspace list
                api_key=api_key_to_use,
                check_tls_certificate=cfg.check_tls_certificate,
                compress_json_requests=cfg.enable_json_request_compression,
            ) as client:
                base_url = url_helpers.get_base_url(cfg.url_override)
                workspace_list_url = url_helpers.get_workspace_list_url(base_url)
                response = client.get(workspace_list_url)
                workspaces_list = response.json().get("workspaceNames", [])

            if not workspaces_list:
                console.print("[yellow]No workspaces found.[/yellow]")
                return

            console.print(f"[green]Found {len(workspaces_list)} workspace(s)[/green]\n")

        # Process each workspace
        for idx, workspace in enumerate(workspaces_list, 1):
            console.print(f"\n[cyan]{'='*60}[/cyan]")
            console.print(
                f"[blue]Processing workspace {idx}/{len(workspaces_list)}: {workspace}[/blue]"
            )
            console.print(f"[cyan]{'='*60}[/cyan]\n")

            try:
                # Generate output filename for this workspace
                if len(workspaces_list) == 1:
                    # Single workspace: use the provided output filename
                    workspace_output = output
                else:
                    # Multiple workspaces: append workspace name to output filename
                    output_path = Path(output)
                    workspace_output = str(
                        output_path.parent
                        / f"{output_path.stem}_{workspace}{output_path.suffix}"
                    )

                # Load from JSON or extract from API
                if from_json:
                    console.print(
                        f"[green]Loading data from {workspace_output}...[/green]\n"
                    )
                    if not os.path.exists(workspace_output):
                        console.print(
                            f"[red]Error: JSON file not found: {workspace_output}[/red]"
                        )
                        console.print(
                            f"[yellow]Expected file: {workspace_output}[/yellow]"
                        )
                        continue

                    with open(workspace_output, "r") as f:
                        data = json.load(f)

                    # Verify workspace matches
                    if data.get("workspace") != workspace:
                        console.print(
                            f"[yellow]Warning: JSON file workspace '{data.get('workspace')}' "
                            f"does not match expected workspace '{workspace}'[/yellow]"
                        )

                    # Calculate statistics if not present in JSON
                    if "statistics" not in data:
                        console.print("[blue]Calculating summary statistics...[/blue]")
                        stats = calculate_statistics(data)
                        data["statistics"] = stats

                    console.print(f"[green]Loaded data from {workspace_output}[/green]")
                else:
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
                    console.print(f"[blue]Saving data to {workspace_output}...[/blue]")
                    with open(workspace_output, "w") as f:
                        json.dump(data, f, indent=2, default=str)

                    console.print(
                        f"[green]Data extraction complete! Results saved to {workspace_output}[/green]"
                    )

                # Generate PDF report
                console.print(f"\n[cyan]{'='*60}[/cyan]")
                console.print("[blue]Generating PDF report...[/blue]")
                try:
                    output_path = Path(workspace_output)
                    output_dir = (
                        output_path.parent if output_path.parent != Path(".") else "."
                    )
                    pdf_filename = create_pdf_report(data, output_dir=str(output_dir))
                    console.print(f"[green]PDF report saved to {pdf_filename}[/green]")

                    # Open PDF if --open flag is set (only for the last workspace)
                    if open_pdf and idx == len(workspaces_list):
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
                console.print(f"[red]Error processing workspace {workspace}: {e}[/red]")
                traceback.print_exc()
                # Continue with next workspace instead of exiting
                continue

        console.print(
            f"\n[green]Completed processing {len(workspaces_list)} workspace(s)[/green]"
        )

    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        traceback.print_exc()
        sys.exit(1)
