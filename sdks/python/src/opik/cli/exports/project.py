"""Project export functionality."""

import sys
from datetime import datetime
from pathlib import Path
from typing import Optional

import click
from rich.console import Console
from rich.progress import Progress, SpinnerColumn, TextColumn

import opik
from opik.rest_api.core.api_error import ApiError
from opik.rest_api.types.project_public import ProjectPublic
from .utils import (
    debug_print,
    matches_name_pattern,
    should_skip_file,
    print_export_summary,
    trace_to_csv_rows,
    write_csv_data,
    write_json_data,
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

    # Validate filter syntax before using it
    if filter_string:
        try:
            from opik.api_objects import opik_query_language

            # Try to parse the filter to validate syntax
            oql = opik_query_language.OpikQueryLanguage(filter_string)
            if oql.get_filter_expressions() is None and filter_string.strip():
                console.print(
                    f"[red]Error: Invalid filter syntax: Filter '{filter_string}' could not be parsed.[/red]"
                )
                console.print("[yellow]OQL filter syntax examples:[/yellow]")
                console.print('  status = "running"')
                console.print('  name contains "test"')
                console.print('  start_time >= "2024-01-01T00:00:00Z"')
                console.print("  usage.total_tokens > 1000")
                console.print(
                    "[yellow]Note: String values must be in double quotes, use = not :[/yellow]"
                )
                raise ValueError(
                    f"Invalid filter syntax: '{filter_string}' could not be parsed"
                )
        except ValueError as e:
            # If it's already our custom error message, re-raise it
            if "Invalid filter syntax" in str(e) and "could not be parsed" in str(e):
                raise
            # Otherwise, format the error nicely
            error_msg = str(e)
            console.print(f"[red]Error: Invalid filter syntax: {error_msg}[/red]")
            console.print("[yellow]OQL filter syntax examples:[/yellow]")
            console.print('  status = "running"')
            console.print('  name contains "test"')
            console.print('  start_time >= "2024-01-01T00:00:00Z"')
            console.print("  usage.total_tokens > 1000")
            console.print(
                "[yellow]Note: String values must be in double quotes, use = not :[/yellow]"
            )
            raise

    exported_count = 0
    skipped_count = 0
    page_size = min(100, max_results)  # Process in smaller batches
    current_page = 1
    total_processed = 0

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

                # Parse filter to JSON format - backend expects JSON, not OQL string
                parsed_filter = None
                if filter_string:
                    from opik.api_objects import opik_query_language

                    oql = opik_query_language.OpikQueryLanguage(filter_string)
                    parsed_filter = oql.parsed_filters  # This is the JSON string
                    if debug:
                        debug_print(
                            f"DEBUG: Parsed filter to JSON: {parsed_filter}", debug
                        )

                # Use get_traces_by_project for better performance when we know the project name
                # Backend expects JSON string format for filters
                trace_page = client.rest_client.traces.get_traces_by_project(
                    project_name=project_name,
                    filters=parsed_filter,  # Pass parsed JSON string - backend expects JSON
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
                        '[yellow]String values in filters must be in double quotes, e.g., status = "running"[/yellow]'
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

                    # Determine file path based on format
                    if format.lower() == "csv":
                        file_path = project_dir / f"trace_{trace.id}.csv"
                    else:
                        file_path = project_dir / f"trace_{trace.id}.json"

                    # Check if file already exists and should be skipped
                    if should_skip_file(file_path, force):
                        if debug:
                            debug_print(
                                f"Skipping trace {trace.id} (already exists)",
                                debug,
                            )
                        skipped_count += 1
                        total_processed += 1
                        continue

                    # Save to file using the appropriate format
                    try:
                        if format.lower() == "csv":
                            write_csv_data(trace_data, file_path, trace_to_csv_rows)
                            if debug:
                                debug_print(f"Wrote CSV file: {file_path}", debug)
                        else:
                            write_json_data(trace_data, file_path)
                            if debug:
                                debug_print(f"Wrote JSON file: {file_path}", debug)

                        exported_count += 1
                        total_processed += 1
                    except Exception as write_error:
                        console.print(
                            f"[red]Error writing trace {trace.id} to file: {write_error}[/red]"
                        )
                        if debug:
                            import traceback

                            debug_print(f"Traceback: {traceback.format_exc()}", debug)
                        continue

                except Exception as e:
                    console.print(f"[red]Error exporting trace {trace.id}: {e}[/red]")
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
            progress.update(task, description=f"Exported {exported_count} traces total")

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
    api_key: Optional[str] = None,
) -> None:
    """Export a project by exact name."""
    try:
        if debug:
            debug_print(f"Exporting project: {name}", debug)

        # Validate filter syntax before doing anything else
        if filter_string:
            try:
                from opik.api_objects import opik_query_language

                # Try to parse the filter to validate syntax
                oql = opik_query_language.OpikQueryLanguage(filter_string)
                if oql.get_filter_expressions() is None and filter_string.strip():
                    console.print(
                        f"[red]Error: Invalid filter syntax: Filter '{filter_string}' could not be parsed.[/red]"
                    )
                    console.print("[yellow]OQL filter syntax examples:[/yellow]")
                    console.print('  status = "running"')
                    console.print('  name contains "test"')
                    console.print('  start_time >= "2024-01-01T00:00:00Z"')
                    console.print("  usage.total_tokens > 1000")
                    console.print(
                        "[yellow]Note: String values must be in double quotes, use = not :[/yellow]"
                    )
                    raise ValueError(
                        f"Invalid filter syntax: '{filter_string}' could not be parsed"
                    )
            except ValueError as e:
                # If it's already our custom error message, re-raise it
                if "Invalid filter syntax" in str(e) and "could not be parsed" in str(
                    e
                ):
                    raise
                # Otherwise, format the error nicely
                error_msg = str(e)
                console.print(f"[red]Error: Invalid filter syntax: {error_msg}[/red]")
                console.print("[yellow]OQL filter syntax examples:[/yellow]")
                console.print('  status = "running"')
                console.print('  name contains "test"')
                console.print('  start_time >= "2024-01-01T00:00:00Z"')
                console.print("  usage.total_tokens > 1000")
                console.print(
                    "[yellow]Note: String values must be in double quotes, use = not :[/yellow]"
                )
                raise

        # Initialize client
        if api_key:
            client = opik.Opik(api_key=api_key, workspace=workspace)
        else:
            client = opik.Opik(workspace=workspace)

        # Create output directory
        output_dir = Path(output_path) / workspace / "projects"
        output_dir.mkdir(parents=True, exist_ok=True)

        if debug:
            debug_print(f"Target directory: {output_dir}", debug)

        # Get projects and find exact match
        # Use name filter to narrow down results (backend does partial match, case-insensitive)
        # Then verify exact match on client side
        projects_response = client.rest_client.projects.find_projects(name=name)
        projects = projects_response.content or []
        matching_project = None

        for project in projects:
            if project.name == name:
                matching_project = project
                break

        # If not found with name filter, try paginating through all projects as fallback
        # This handles edge cases where the name filter might not work as expected
        if not matching_project:
            if debug:
                debug_print(
                    f"Project '{name}' not found in filtered results, searching all projects...",
                    debug,
                )
            # Paginate through all projects
            page = 1
            page_size = 100
            while True:
                projects_response = client.rest_client.projects.find_projects(
                    page=page, size=page_size
                )
                projects = projects_response.content or []
                if not projects:
                    break

                for project in projects:
                    if project.name == name:
                        matching_project = project
                        break

                if matching_project:
                    break

                # Check if there are more pages
                if projects_response.total is not None:
                    total_pages = (projects_response.total + page_size - 1) // page_size
                    if page >= total_pages:
                        break
                elif len(projects) < page_size:
                    # No more pages if we got fewer results than page size
                    break

                page += 1

        if not matching_project:
            console.print(f"[red]Project '{name}' not found[/red]")
            sys.exit(1)

        if debug:
            debug_print(f"Found project by exact match: {matching_project.name}", debug)

        # Export the project
        exported_count, traces_exported, traces_skipped = export_single_project(
            client,
            matching_project,
            output_dir,
            filter_string,
            max_results,
            force,
            debug,
            format,
        )

        # Collect statistics for summary using actual export counts
        stats = {
            "projects": 1 if exported_count > 0 else 0,
            "projects_skipped": 0 if exported_count > 0 else 1,
            "traces": traces_exported,
            "traces_skipped": traces_skipped,
        }

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

    except ValueError as e:
        # Filter validation errors are already formatted, just exit
        if "Invalid filter syntax" in str(e):
            sys.exit(1)
        # Other ValueErrors should be shown
        console.print(f"[red]Error exporting project: {e}[/red]")
        sys.exit(1)
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
) -> tuple[int, int, int]:
    """Export a single project."""
    try:
        # Create project-specific directory for traces
        project_traces_dir = output_dir / project.name
        project_traces_dir.mkdir(parents=True, exist_ok=True)

        # Check if traces directory already has files in the requested format and force is not set
        # Skip early return if a filter is provided, as we need to check if existing traces match the filter
        if not force and not filter_string:
            # Only check for files in the requested format
            if format.lower() == "csv":
                existing_traces = list(project_traces_dir.glob("trace_*.csv"))
            else:
                existing_traces = list(project_traces_dir.glob("trace_*.json"))

            if existing_traces:
                if debug:
                    debug_print(
                        f"Skipping {project.name} (already has {len(existing_traces)} trace files in {format} format, use --force to re-download)",
                        debug,
                    )
                # Return project status, and trace counts (all skipped)
                return (1, 0, len(existing_traces))

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
        if traces_exported > 0:
            if debug:
                debug_print(
                    f"Exported project: {project.name} with {traces_exported} traces",
                    debug,
                )
            return (1, traces_exported, traces_skipped)
        elif traces_skipped > 0:
            # Traces were skipped (already exist)
            if debug:
                debug_print(
                    f"Project {project.name} already has {traces_skipped} trace files",
                    debug,
                )
            return (1, traces_exported, traces_skipped)
        else:
            # No traces found or exported
            if debug:
                debug_print(
                    f"No traces found in project: {project.name}",
                    debug,
                )
            # Still return 1 to indicate the project was processed
            # (the empty directory will remain, but that's expected if there are no traces)
            return (1, traces_exported, traces_skipped)

    except Exception as e:
        console.print(f"[red]Error exporting project {project.name}: {e}[/red]")
        return (0, 0, 0)


def export_project_by_name_or_id(
    name_or_id: str,
    workspace: str,
    output_path: str,
    filter_string: Optional[str],
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
    api_key: Optional[str] = None,
) -> None:
    """Export a project by name or ID.

    First tries to get the project by ID. If not found, tries by name.
    """
    try:
        if debug:
            debug_print(f"Attempting to export project: {name_or_id}", debug)

        # Initialize client
        if api_key:
            client = opik.Opik(api_key=api_key, workspace=workspace)
        else:
            client = opik.Opik(workspace=workspace)

        # Create output directory
        output_dir = Path(output_path) / workspace / "projects"
        output_dir.mkdir(parents=True, exist_ok=True)

        if debug:
            debug_print(f"Target directory: {output_dir}", debug)

        # Try to get project by ID first
        try:
            if debug:
                debug_print(f"Trying to get project by ID: {name_or_id}", debug)
            project = client.get_project(name_or_id)

            # Successfully found by ID, export it
            if debug:
                debug_print(
                    f"Found project by ID: {project.name} (ID: {project.id})", debug
                )

            # Export the project
            exported_count, traces_exported, traces_skipped = export_single_project(
                client,
                project,
                output_dir,
                filter_string,
                max_results,
                force,
                debug,
                format,
            )

            # Show export summary
            stats = {
                "projects": exported_count,
                "traces": traces_exported,
                "traces_skipped": traces_skipped,
            }
            print_export_summary(stats, format)

            if exported_count > 0:
                console.print(
                    f"[green]Successfully exported project '{project.name}' (ID: {project.id}) to {output_dir}[/green]"
                )
            else:
                console.print(
                    f"[yellow]Project '{project.name}' (ID: {project.id}) already exists (use --force to re-download)[/yellow]"
                )
            return

        except ApiError as e:
            # Check if it's a 404 (not found) error
            if e.status_code == 404:
                # Not found by ID, try by name
                if debug:
                    debug_print(
                        f"Project not found by ID, trying by name: {name_or_id}", debug
                    )
                # Fall through to name-based export
                pass
            else:
                # Some other API error, re-raise it
                raise

        # Try by name (either because ID lookup failed or we're explicitly trying name)
        export_project_by_name(
            name_or_id,
            workspace,
            output_path,
            filter_string,
            max_results,
            force,
            debug,
            format,
            api_key,
        )

    except Exception as e:
        console.print(f"[red]Error exporting project: {e}[/red]")
        sys.exit(1)


@click.command(name="project")
@click.argument("name_or_id", type=str)
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
    default="opik_exports",
    help="Directory to save exported data. Defaults to opik_exports.",
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
    name_or_id: str,
    filter: Optional[str],
    max_results: Optional[int],
    path: str,
    force: bool,
    debug: bool,
    format: str,
) -> None:
    """Export a project by name or ID to workspace/projects.

    The command will first try to find the project by ID. If not found, it will try by name.
    """
    # Get workspace and API key from context
    workspace = ctx.obj["workspace"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    export_project_by_name_or_id(
        name_or_id, workspace, path, filter, max_results, force, debug, format, api_key
    )
