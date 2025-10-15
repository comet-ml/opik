"""Download command for Opik CLI."""

import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Optional

import click
from rich.console import Console
from rich.progress import Progress, SpinnerColumn, TextColumn

import opik

console = Console()


def _matches_name_pattern(name: str, pattern: Optional[str]) -> bool:
    """Check if a name matches the given regex pattern."""
    if pattern is None:
        return True
    try:
        return bool(re.search(pattern, name))
    except re.error as e:
        console.print(f"[red]Invalid regex pattern '{pattern}': {e}[/red]")
        return False


def _export_traces(
    client: opik.Opik,
    project_name: str,
    project_dir: Path,
    max_results: int,
    filter: Optional[str],
    name_pattern: Optional[str] = None,
) -> int:
    """Download traces and their spans with pagination support for large projects."""
    console.print(
        f"[blue]DEBUG: _export_traces called with project_name: {project_name}, project_dir: {project_dir}[/blue]"
    )
    exported_count = 0
    page_size = min(100, max_results)  # Process in smaller batches
    last_trace_time = None
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

            # Build filter string with pagination
            pagination_filter = filter or ""
            if last_trace_time:
                # Add timestamp filter to continue from where we left off
                time_filter = f"start_time < '{last_trace_time.isoformat()}'"
                if pagination_filter:
                    pagination_filter = f"({pagination_filter}) AND {time_filter}"
                else:
                    pagination_filter = time_filter

            try:
                console.print(
                    f"[blue]DEBUG: Searching traces with project_name: {project_name}, filter: {pagination_filter}, max_results: {current_page_size}[/blue]"
                )
                traces = client.search_traces(
                    project_name=project_name,
                    filter_string=pagination_filter if pagination_filter else None,
                    max_results=current_page_size,
                    truncate=False,  # Don't truncate data for download
                )
                console.print(
                    f"[blue]DEBUG: Found {len(traces) if traces else 0} traces[/blue]"
                )
            except Exception as e:
                console.print(f"[red]Error searching traces: {e}[/red]")
                break

            if not traces:
                # No more traces to process
                break

            # Update progress description
            progress.update(
                task, description=f"Found {len(traces)} traces in current batch"
            )

            # Store original traces for pagination before filtering
            original_traces = traces

            # Filter traces by name pattern if specified
            if name_pattern:
                original_count = len(traces)
                traces = [
                    trace
                    for trace in traces
                    if _matches_name_pattern(trace.name or "", name_pattern)
                ]
                if len(traces) < original_count:
                    console.print(
                        f"[blue]Filtered to {len(traces)} traces matching pattern '{name_pattern}' in current batch[/blue]"
                    )

            if not traces:
                # No traces match the name pattern, but we might have more to process
                # Use original_traces for pagination, not the filtered empty list
                last_trace_time = (
                    original_traces[0].start_time if original_traces else None
                )
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

                    # Save to file
                    trace_file = project_dir / f"trace_{trace.id}.json"
                    with open(trace_file, "w", encoding="utf-8") as f:
                        json.dump(trace_data, f, indent=2, default=str)

                    exported_count += 1
                    total_processed += 1

                except Exception as e:
                    console.print(f"[red]Error exporting trace {trace.id}: {e}[/red]")
                    continue

            # Update last trace time for pagination
            if traces:
                last_trace_time = traces[-1].start_time

            # If we got fewer traces than requested, we've reached the end
            if len(traces) < current_page_size:
                break

        # Final progress update
        if exported_count == 0:
            console.print("[yellow]No traces found in the project.[/yellow]")
        else:
            progress.update(task, description=f"Exported {exported_count} traces total")

    return exported_count


def _export_datasets(
    client: opik.Opik,
    project_dir: Path,
    max_results: int,
    name_pattern: Optional[str] = None,
    debug: bool = False,
) -> int:
    """Export datasets."""
    try:
        datasets = client.get_datasets(max_results=max_results, sync_items=True)

        if not datasets:
            console.print("[yellow]No datasets found in the project.[/yellow]")
            return 0

        # Filter datasets by name pattern if specified
        if name_pattern:
            original_count = len(datasets)
            datasets = [
                dataset
                for dataset in datasets
                if _matches_name_pattern(dataset.name, name_pattern)
            ]
            if len(datasets) < original_count:
                console.print(
                    f"[blue]Filtered to {len(datasets)} datasets matching pattern '{name_pattern}'[/blue]"
                )

        if not datasets:
            console.print(
                "[yellow]No datasets found matching the name pattern.[/yellow]"
            )
            return 0

        exported_count = 0
        for dataset in datasets:
            try:
                # Get dataset items using the get_items method
                if debug:
                    console.print(
                        f"[blue]Getting items for dataset: {dataset.name}[/blue]"
                    )
                dataset_items = dataset.get_items()

                # Convert dataset items to the expected format for import
                formatted_items = []
                for item in dataset_items:
                    formatted_item = {
                        "input": item.get("input"),
                        "expected_output": item.get("expected_output"),
                        "metadata": item.get("metadata"),
                    }
                    formatted_items.append(formatted_item)

                # Create dataset data structure
                dataset_data = {
                    "name": dataset.name,
                    "description": dataset.description,
                    "items": formatted_items,
                    "downloaded_at": datetime.now().isoformat(),
                }

                # Save to file
                dataset_file = project_dir / f"dataset_{dataset.name}.json"
                with open(dataset_file, "w", encoding="utf-8") as f:
                    json.dump(dataset_data, f, indent=2, default=str)

                exported_count += 1

            except Exception as e:
                console.print(
                    f"[red]Error downloading dataset {dataset.name}: {e}[/red]"
                )
                continue

        return exported_count

    except Exception as e:
        console.print(f"[red]Error exporting datasets: {e}[/red]")
        return 0


def _export_prompts(
    client: opik.Opik,
    project_dir: Path,
    max_results: int,
    name_pattern: Optional[str] = None,
) -> int:
    """Export prompts."""
    try:
        prompts = client.search_prompts()

        if not prompts:
            console.print("[yellow]No prompts found in the project.[/yellow]")
            return 0

        # Filter prompts by name pattern if specified
        if name_pattern:
            original_count = len(prompts)
            prompts = [
                prompt
                for prompt in prompts
                if _matches_name_pattern(prompt.name, name_pattern)
            ]
            if len(prompts) < original_count:
                console.print(
                    f"[blue]Filtered to {len(prompts)} prompts matching pattern '{name_pattern}'[/blue]"
                )

        if not prompts:
            console.print(
                "[yellow]No prompts found matching the name pattern.[/yellow]"
            )
            return 0

        exported_count = 0
        for prompt in prompts:
            try:
                # Get prompt history
                prompt_history = client.get_prompt_history(prompt.name)

                # Create prompt data structure
                prompt_data = {
                    "name": prompt.name,
                    "current_version": {
                        "prompt": prompt.prompt,
                        "metadata": prompt.metadata,
                        "type": prompt.type.value if prompt.type else None,
                        "commit": prompt.commit,
                    },
                    "history": [
                        {
                            "prompt": version.prompt,
                            "metadata": version.metadata,
                            "type": version.type.value if version.type else None,
                            "commit": version.commit,
                        }
                        for version in prompt_history
                    ],
                    "downloaded_at": datetime.now().isoformat(),
                }

                # Save to file
                prompt_file = (
                    project_dir / f"prompt_{prompt.name.replace('/', '_')}.json"
                )
                with open(prompt_file, "w", encoding="utf-8") as f:
                    json.dump(prompt_data, f, indent=2, default=str)

                exported_count += 1

            except Exception as e:
                console.print(f"[red]Error downloading prompt {prompt.name}: {e}[/red]")
                continue

        return exported_count

    except Exception as e:
        console.print(f"[red]Error exporting prompts: {e}[/red]")
        return 0


@click.command(name="export")
@click.argument("workspace_or_project", type=str)
@click.option(
    "--path",
    "-p",
    type=click.Path(file_okay=False, dir_okay=True, writable=True),
    default="./",
    help="Directory to save exported data. Defaults to current directory.",
)
@click.option(
    "--max-results",
    type=int,
    default=1000,
    help="Maximum number of items to download per data type. Defaults to 1000.",
)
@click.option(
    "--filter",
    type=str,
    help="Filter string to narrow down the search using Opik Query Language (OQL).",
)
@click.option(
    "--all",
    is_flag=True,
    help="Include all data types (traces, datasets, prompts).",
)
@click.option(
    "--include",
    type=click.Choice(["traces", "datasets", "prompts"], case_sensitive=False),
    multiple=True,
    default=["traces"],
    help="Data types to include in download. Can be specified multiple times. Defaults to traces only.",
)
@click.option(
    "--exclude",
    type=click.Choice(["traces", "datasets", "prompts"], case_sensitive=False),
    multiple=True,
    help="Data types to exclude from download. Can be specified multiple times.",
)
@click.option(
    "--name",
    type=str,
    help="Filter items by name using Python regex patterns. Matches against trace names, dataset names, or prompt names.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the export process.",
)
def export(
    workspace_or_project: str,
    path: str,
    max_results: int,
    filter: Optional[str],
    all: bool,
    include: tuple,
    exclude: tuple,
    name: Optional[str],
    debug: bool,
) -> None:
    """
    Download data from a workspace or workspace/project to local files.

    This command fetches traces, datasets, and prompts from the specified workspace or project
    and saves them to local JSON files in the output directory.

    Note: Thread metadata is automatically derived from traces with the same thread_id,
    so threads don't need to be exported separately.

    WORKSPACE_OR_PROJECT: Either a workspace name (e.g., "my-workspace") to export all projects,
                          or workspace/project (e.g., "my-workspace/my-project") to export a specific project.
    """
    try:
        if debug:
            console.print("[blue]DEBUG: Starting export with parameters:[/blue]")
            console.print(
                f"[blue]  workspace_or_project: {workspace_or_project}[/blue]"
            )
            console.print(f"[blue]  path: {path}[/blue]")
            console.print(f"[blue]  max_results: {max_results}[/blue]")
            console.print(f"[blue]  include: {include}[/blue]")
            console.print(f"[blue]  debug: {debug}[/blue]")

        # Parse workspace/project from the argument
        if "/" in workspace_or_project:
            workspace, project_name = workspace_or_project.split("/", 1)
            export_specific_project = True
            if debug:
                console.print(
                    f"[blue]DEBUG: Parsed workspace: {workspace}, project: {project_name}[/blue]"
                )
        else:
            # Only workspace specified - download all projects
            workspace = workspace_or_project
            project_name = None
            export_specific_project = False
            if debug:
                console.print(f"[blue]DEBUG: Workspace only: {workspace}[/blue]")

        # Initialize Opik client with workspace
        if debug:
            console.print(
                f"[blue]DEBUG: Initializing Opik client with workspace: {workspace}[/blue]"
            )
        client = opik.Opik(workspace=workspace)

        # Create output directory
        output_path = Path(path)
        output_path.mkdir(parents=True, exist_ok=True)

        # Determine which data types to download
        if all:
            # If --all is specified, include all data types
            include_set = {"traces", "datasets", "prompts"}
        else:
            include_set = set(item.lower() for item in include)

        exclude_set = set(item.lower() for item in exclude)

        # Apply exclusions
        data_types = include_set - exclude_set

        if export_specific_project:
            # Download from specific project
            console.print(
                f"[green]Downloading data from workspace: {workspace}, project: {project_name}[/green]"
            )

            # Create workspace/project directory structure
            workspace_dir = output_path / workspace
            assert project_name is not None  # Type narrowing for mypy
            project_dir = workspace_dir / project_name
            project_dir.mkdir(parents=True, exist_ok=True)

            if debug:
                console.print(
                    f"[blue]Output directory: {workspace}/{project_name}[/blue]"
                )
                console.print(
                    f"[blue]Data types: {', '.join(sorted(data_types))}[/blue]"
                )

            # Note about workspace vs project-specific data
            project_specific = [dt for dt in data_types if dt in ["traces"]]
            workspace_data = [dt for dt in data_types if dt in ["datasets", "prompts"]]

            if project_specific and workspace_data:
                console.print(
                    f"[yellow]Note: {', '.join(project_specific)} are project-specific, {', '.join(workspace_data)} belong to workspace '{workspace}'[/yellow]"
                )
            elif workspace_data:
                console.print(
                    f"[yellow]Note: {', '.join(workspace_data)} belong to workspace '{workspace}'[/yellow]"
                )

            # Download each data type
            total_exported = 0

            # Download traces
            if "traces" in data_types:
                if debug:
                    console.print("[blue]Downloading traces...[/blue]")
                if debug:
                    console.print(
                        f"[blue]DEBUG: Calling _export_traces with project_name: {project_name}, project_dir: {project_dir}[/blue]"
                    )
                traces_exported = _export_traces(
                    client, project_name, project_dir, max_results, filter, name
                )
                if debug:
                    console.print(
                        f"[blue]DEBUG: _export_traces returned: {traces_exported}[/blue]"
                    )
                total_exported += traces_exported

            # Download datasets
            if "datasets" in data_types:
                if debug:
                    console.print("[blue]Downloading datasets...[/blue]")
                datasets_exported = _export_datasets(
                    client, project_dir, max_results, name, debug
                )
                total_exported += datasets_exported

            # Download prompts
            if "prompts" in data_types:
                if debug:
                    console.print("[blue]Downloading prompts...[/blue]")
                prompts_exported = _export_prompts(
                    client, project_dir, max_results, name
                )
                total_exported += prompts_exported

            console.print(
                f"[green]Successfully exported {total_exported} items to {project_dir}[/green]"
            )
        else:
            # Export from all projects in workspace
            console.print(
                f"[green]Exporting data from workspace: {workspace} (all projects)[/green]"
            )

            # Get all projects in the workspace
            try:
                projects_response = client.rest_client.projects.find_projects()
                projects = projects_response.content or []

                if not projects:
                    console.print(
                        f"[yellow]No projects found in workspace '{workspace}'[/yellow]"
                    )
                    return

                console.print(
                    f"[blue]Found {len(projects)} projects in workspace[/blue]"
                )
                console.print(
                    f"[blue]Data types: {', '.join(sorted(data_types))}[/blue]"
                )

                # Note about workspace vs project-specific data
                project_specific = [dt for dt in data_types if dt in ["traces"]]
                workspace_data = [
                    dt for dt in data_types if dt in ["datasets", "prompts"]
                ]

                if project_specific and workspace_data:
                    console.print(
                        f"[yellow]Note: {', '.join(project_specific)} are project-specific, {', '.join(workspace_data)} belong to workspace '{workspace}'[/yellow]"
                    )
                elif workspace_data:
                    console.print(
                        f"[yellow]Note: {', '.join(workspace_data)} belong to workspace '{workspace}'[/yellow]"
                    )

                total_exported = 0

                # Download workspace-level data once (datasets, experiments, prompts)
                workspace_dir = output_path / workspace
                workspace_dir.mkdir(parents=True, exist_ok=True)

                # Download datasets
                if "datasets" in data_types:
                    if debug:
                        console.print("[blue]Downloading datasets...[/blue]")
                    datasets_exported = _export_datasets(
                        client, workspace_dir, max_results, name, debug
                    )
                    total_exported += datasets_exported

                # Download prompts
                if "prompts" in data_types:
                    if debug:
                        console.print("[blue]Downloading prompts...[/blue]")
                    prompts_exported = _export_prompts(
                        client, workspace_dir, max_results, name
                    )
                    total_exported += prompts_exported

                # Download traces from each project
                if "traces" in data_types:
                    for project in projects:
                        project_name = project.name
                        console.print(
                            f"[blue]Downloading traces from project: {project_name}...[/blue]"
                        )

                        # Create project directory
                        assert project_name is not None  # Type narrowing for mypy
                        project_dir = workspace_dir / project_name
                        project_dir.mkdir(parents=True, exist_ok=True)

                        traces_exported = _export_traces(
                            client, project_name, project_dir, max_results, filter, name
                        )
                        total_exported += traces_exported

                        if traces_exported > 0:
                            console.print(
                                f"[green]Exported {traces_exported} traces from {project_name}[/green]"
                            )

                console.print(
                    f"[green]Successfully exported {total_exported} items from workspace '{workspace}'[/green]"
                )

            except Exception as e:
                console.print(f"[red]Error getting projects from workspace: {e}[/red]")
                sys.exit(1)

    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        sys.exit(1)
