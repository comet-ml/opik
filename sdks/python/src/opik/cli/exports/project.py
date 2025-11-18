"""Project export functionality."""

import sys
from datetime import datetime
from pathlib import Path
from typing import Optional

import click
from rich.console import Console
from rich.progress import Progress, SpinnerColumn, TextColumn

import opik
from opik.rest_api.types.project_public import ProjectPublic
from .utils import (
    debug_print,
    dump_to_file,
    matches_name_pattern,
    should_skip_file,
    print_export_summary,
)

console = Console()


def export_traces(
    client: opik.Opik,
    project_name: str,
    project_dir: Path,
    max_results: int,
    filter_string: Optional[str],
    project_name_filter: Optional[str] = None,
    format: str = "json",
    debug: bool = False,
    force: bool = False,
) -> tuple[int, int]:
    """Download traces and their spans with pagination support for large projects."""
    if debug:
        debug_print(
            f"DEBUG: _export_traces called with project_name: {project_name}, project_dir: {project_dir}",
            debug,
        )
    exported_count = 0
    skipped_count = 0
    page_size = min(100, max_results)  # Process in smaller batches
    current_page = 1
    total_processed = 0

    # For CSV format, set up streaming writer
    csv_file = None
    csv_file_handle = None
    csv_writer = None
    csv_fieldnames = None

    try:
        with Progress(
            SpinnerColumn(),
            TextColumn("[progress.description]{task.description}"),
            console=console,
        ) as progress:
            task = progress.add_task("Searching for traces...", total=None)

            while total_processed < max_results:
                # Calculate how many traces to fetch in this batch
                remaining = max_results - total_processed
                current_page_size = min(page_size, remaining)

                try:
                    if debug:
                        debug_print(
                            f"DEBUG: Getting traces by project with project_name: {project_name}, filter: {filter_string}, page: {current_page}, size: {current_page_size}",
                            debug,
                        )
                    # Use get_traces_by_project for better performance when we know the project name
                    trace_page = client.rest_client.traces.get_traces_by_project(
                        project_name=project_name,
                        filters=filter_string,
                        page=current_page,
                        size=current_page_size,
                        truncate=False,  # Don't truncate data for download
                    )
                    traces = trace_page.content or []
                    if debug:
                        debug_print(
                            f"DEBUG: Found {len(traces)} traces in project (page {current_page})",
                            debug,
                        )
                except Exception as e:
                    # Check if it's an OQL parsing error and provide user-friendly message
                    error_msg = str(e)
                    if "Invalid value" in error_msg and (
                        "expected an string in double quotes" in error_msg
                        or "expected a string in double quotes" in error_msg
                    ):
                        console.print(
                            "[red]Error: Invalid filter format in export query.[/red]"
                        )
                        console.print(
                            "[yellow]This appears to be an internal query parsing issue. Please try the export again.[/yellow]"
                        )
                        if debug:
                            debug_print(f"Technical details: {e}", debug)
                    else:
                        console.print(f"[red]Error searching traces: {e}[/red]")
                    break

                if not traces:
                    # No more traces to process
                    break

                # Update progress description to show current page
                progress.update(
                    task,
                    description=f"Downloading traces... (page {current_page}, {len(traces)} traces)",
                )

                # Filter traces by project name if specified
                if project_name_filter:
                    original_count = len(traces)
                    traces = [
                        trace
                        for trace in traces
                        if matches_name_pattern(trace.name or "", project_name_filter)
                    ]
                    if len(traces) < original_count:
                        console.print(
                            f"[blue]Filtered to {len(traces)} traces matching project '{project_name_filter}' in current batch[/blue]"
                        )

                if not traces:
                    # No traces match the name pattern, but we might have more to process
                    # Use original_traces for pagination, not the filtered empty list
                    total_processed += current_page_size
                    continue

                # Update progress for downloading
                progress.update(
                    task,
                    description=f"Downloading traces... (batch {total_processed // page_size + 1})",
                )

                # Download each trace with its spans
                for trace in traces:
                    try:
                        # Get spans for this trace
                        spans = client.search_spans(
                            project_name=project_name,
                            trace_id=trace.id,
                            max_results=1000,  # Get all spans for the trace
                            truncate=False,
                        )

                        # Create trace data structure
                        trace_data = {
                            "trace": trace.model_dump(),
                            "spans": [span.model_dump() for span in spans],
                            "downloaded_at": datetime.now().isoformat(),
                            "project_name": project_name,
                        }

                        # For CSV format, we need to handle this differently
                        # CSV creates a single consolidated file, not individual files
                        if format.lower() == "csv":
                            # For CSV, we only check the consolidated file once
                            csv_file_path = project_dir / f"traces_{project_name}.csv"
                            if should_skip_file(csv_file_path, force):
                                if debug:
                                    debug_print(
                                        f"Skipping CSV export (traces_{project_name}.csv already exists)",
                                        debug,
                                    )
                                # For CSV, if the file exists, we skip the entire export
                                return (
                                    0,
                                    1,
                                )  # 0 exported, 1 skipped (the consolidated file)
                            file_path = csv_file_path
                        else:
                            # For JSON, check each individual trace file
                            file_path = project_dir / f"trace_{trace.id}.json"
                            if should_skip_file(file_path, force):
                                if debug:
                                    debug_print(
                                        f"Skipping trace {trace.id} (already exists)",
                                        debug,
                                    )
                                skipped_count += 1
                                total_processed += 1
                                continue

                        # Use helper function to dump data
                        csv_writer, csv_fieldnames = dump_to_file(
                            trace_data,
                            file_path,
                            format,
                            csv_writer,
                            csv_fieldnames,
                            "trace",
                        )

                        exported_count += 1
                        total_processed += 1

                    except Exception as e:
                        console.print(
                            f"[red]Error exporting trace {trace.id}: {e}[/red]"
                        )
                        continue

                # Update pagination for next iteration
                if traces:
                    current_page += 1
                else:
                    # No more traces to process
                    break

                # If we got fewer traces than requested, we've reached the end
                if len(traces) < current_page_size:
                    break

            # Final progress update
            if exported_count == 0:
                console.print("[yellow]No traces found in the project.[/yellow]")
            else:
                progress.update(
                    task, description=f"Exported {exported_count} traces total"
                )

    finally:
        # Close CSV file if it was opened
        if csv_file_handle:
            csv_file_handle.close()
        if csv_file and csv_file.exists():
            console.print(f"[green]CSV file saved to {csv_file}[/green]")

    return exported_count, skipped_count


def export_project_by_name(
    name: str,
    workspace: str,
    output_path: str,
    filter_string: Optional[str],
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
) -> None:
    """Export a project by exact name."""
    try:
        if debug:
            debug_print(f"Exporting project: {name}", debug)

        # Initialize client
        client = opik.Opik(workspace=workspace)

        # Create output directory
        output_dir = Path(output_path) / workspace / "projects"
        output_dir.mkdir(parents=True, exist_ok=True)

        if debug:
            debug_print(f"Target directory: {output_dir}", debug)

        # Get projects and find exact match
        projects_response = client.rest_client.projects.find_projects()
        projects = projects_response.content or []
        matching_project = None

        for project in projects:
            if project.name == name:
                matching_project = project
                break

        if not matching_project:
            console.print(f"[red]Project '{name}' not found[/red]")
            sys.exit(1)

        if debug:
            debug_print(f"Found project by exact match: {matching_project.name}", debug)

        # Export the project
        exported_count = export_single_project(
            client,
            matching_project,
            output_dir,
            filter_string,
            max_results,
            force,
            debug,
            format,
        )

        # Collect statistics for summary
        stats = {
            "projects": 1 if exported_count > 0 else 0,
            "projects_skipped": 0 if exported_count > 0 else 1,
        }

        # Get trace statistics from the project directory
        project_traces_dir = output_dir / matching_project.name
        if project_traces_dir.exists():
            trace_files = list(project_traces_dir.glob("trace_*.json"))
            csv_files = list(project_traces_dir.glob("trace_*.csv"))
            total_trace_files = len(trace_files) + len(csv_files)
            stats["traces"] = total_trace_files
            stats["traces_skipped"] = (
                0  # We don't track skipped traces in current implementation
            )

        # Show export summary
        print_export_summary(stats, format)

        if exported_count > 0:
            console.print(
                f"[green]Successfully exported project '{name}' to {output_dir}[/green]"
            )
        else:
            console.print(
                f"[yellow]Project '{name}' already exists (use --force to re-download)[/yellow]"
            )

    except Exception as e:
        console.print(f"[red]Error exporting project: {e}[/red]")
        sys.exit(1)


def export_single_project(
    client: opik.Opik,
    project: ProjectPublic,
    output_dir: Path,
    filter_string: Optional[str],
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
) -> int:
    """Export a single project."""
    try:
        # Check if already exists and force is not set
        project_file = output_dir / f"project_{project.name}.json"

        if project_file.exists() and not force:
            if debug:
                debug_print(f"Skipping {project.name} (already exists)", debug)
            return 0

        # Create project-specific directory for traces
        project_traces_dir = output_dir / project.name
        project_traces_dir.mkdir(parents=True, exist_ok=True)

        # Export related traces for this project
        traces_exported, traces_skipped = export_traces(
            client,
            project.name,
            project_traces_dir,
            max_results or 1000,  # Use provided max_results or default to 1000
            filter_string,
            None,  # project_name_filter
            format,
            debug,
            force,
        )

        # Project export only exports traces - datasets and prompts must be exported separately
        if traces_exported > 0 or traces_skipped > 0:
            if debug:
                debug_print(
                    f"Exported project: {project.name} with {traces_exported} traces",
                    debug,
                )
            return 1
        else:
            return 0

    except Exception as e:
        console.print(f"[red]Error exporting project {project.name}: {e}[/red]")
        return 0


@click.command(name="project")
@click.argument("name", type=str)
@click.option(
    "--filter",
    type=str,
    help="Filter string to narrow down traces using Opik Query Language (OQL).",
)
@click.option(
    "--max-results",
    type=int,
    help="Maximum number of traces to export. Limits the total number of traces downloaded.",
)
@click.option(
    "--path",
    "-p",
    type=click.Path(file_okay=False, dir_okay=True, writable=True),
    default="./",
    help="Directory to save exported data. Defaults to current directory.",
)
@click.option(
    "--force",
    is_flag=True,
    help="Re-download items even if they already exist locally.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the export process.",
)
@click.option(
    "--format",
    type=click.Choice(["json", "csv"], case_sensitive=False),
    default="json",
    help="Format for exporting data. Defaults to json.",
)
@click.pass_context
def export_project_command(
    ctx: click.Context,
    name: str,
    filter: Optional[str],
    max_results: Optional[int],
    path: str,
    force: bool,
    debug: bool,
    format: str,
) -> None:
    """Export a project by exact name to workspace/projects."""
    # Get workspace from context
    workspace = ctx.obj["workspace"]
    export_project_by_name(
        name, workspace, path, filter, max_results, force, debug, format
    )
