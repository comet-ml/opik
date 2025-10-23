"""Upload command for Opik CLI."""

import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

import click
from rich.console import Console
from rich.progress import Progress, SpinnerColumn, TextColumn

import opik
from opik.rest_api.core.api_error import ApiError
from opik.api_objects.trace import trace_data
from opik.api_objects.span import span_data
from opik.api_objects.trace.migration import prepare_traces_and_spans_for_copy

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


def _json_to_trace_data(
    trace_info: Dict[str, Any], project_name: str
) -> trace_data.TraceData:
    """Convert JSON trace data to TraceData object."""
    return trace_data.TraceData(
        id=trace_info.get("id", ""),
        name=trace_info.get("name"),
        start_time=(
            datetime.fromisoformat(trace_info["start_time"].replace("Z", "+00:00"))
            if trace_info.get("start_time")
            else None
        ),
        end_time=(
            datetime.fromisoformat(trace_info["end_time"].replace("Z", "+00:00"))
            if trace_info.get("end_time")
            else None
        ),
        metadata=trace_info.get("metadata"),
        input=trace_info.get("input"),
        output=trace_info.get("output"),
        tags=trace_info.get("tags"),
        feedback_scores=trace_info.get("feedback_scores"),
        project_name=project_name,
        created_by=trace_info.get("created_by"),
        error_info=trace_info.get("error_info"),
        thread_id=trace_info.get("thread_id"),
    )


def _json_to_span_data(
    span_info: Dict[str, Any], project_name: str
) -> span_data.SpanData:
    """Convert JSON span data to SpanData object."""
    return span_data.SpanData(
        trace_id=span_info.get("trace_id", ""),
        id=span_info.get("id", ""),
        parent_span_id=span_info.get("parent_span_id"),
        name=span_info.get("name"),
        type=span_info.get("type", "general"),
        start_time=(
            datetime.fromisoformat(span_info["start_time"].replace("Z", "+00:00"))
            if span_info.get("start_time")
            else None
        ),
        end_time=(
            datetime.fromisoformat(span_info["end_time"].replace("Z", "+00:00"))
            if span_info.get("end_time")
            else None
        ),
        metadata=span_info.get("metadata"),
        input=span_info.get("input"),
        output=span_info.get("output"),
        tags=span_info.get("tags"),
        usage=span_info.get("usage"),
        feedback_scores=span_info.get("feedback_scores"),
        project_name=project_name,
        model=span_info.get("model"),
        provider=span_info.get("provider"),
        error_info=span_info.get("error_info"),
        total_cost=span_info.get("total_cost"),
    )


def _import_traces(
    client: opik.Opik,
    project_dir: Path,
    dry_run: bool,
    name_pattern: Optional[str] = None,
) -> int:
    """Import traces from JSON files."""
    trace_files = list(project_dir.glob("trace_*.json"))

    if not trace_files:
        console.print(f"[yellow]No trace files found in {project_dir}[/yellow]")
        return 0

    imported_count = 0
    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        console=console,
    ) as progress:
        task = progress.add_task("Uploading traces...", total=len(trace_files))

        for trace_file in trace_files:
            try:
                with open(trace_file, "r", encoding="utf-8") as f:
                    trace_data = json.load(f)

                # Filter by name pattern if specified
                trace_name = trace_data.get("trace", {}).get("name", "")
                if name_pattern and not _matches_name_pattern(trace_name, name_pattern):
                    continue

                if dry_run:
                    print(f"Would upload trace: {trace_data['trace']['id']}")
                    imported_count += 1
                    progress.update(
                        task,
                        description=f"Imported {imported_count}/{len(trace_files)} traces",
                    )
                    continue

                # Extract trace information
                trace_info = trace_data["trace"]
                spans_info = trace_data.get("spans", [])

                # Convert JSON data to TraceData and SpanData objects
                # Use a temporary project name for the migration logic
                temp_project_name = "temp_import"
                trace_data_obj = _json_to_trace_data(trace_info, temp_project_name)

                # Convert spans to SpanData objects, setting the correct trace_id
                span_data_objects = []
                for span_info in spans_info:
                    span_info["trace_id"] = trace_data_obj.id  # Ensure trace_id is set
                    span_data_obj = _json_to_span_data(span_info, temp_project_name)
                    span_data_objects.append(span_data_obj)

                # Use the migration logic to prepare traces and spans with new IDs
                # This handles orphan spans, validates parent relationships, and logs issues
                new_trace_data, new_span_data = prepare_traces_and_spans_for_copy(
                    destination_project_name=client.project_name or "default",
                    traces_data=[trace_data_obj],
                    spans_data=span_data_objects,
                )

                # Create the trace using the prepared data
                new_trace = new_trace_data[0]
                trace_obj = client.trace(
                    name=new_trace.name,
                    start_time=new_trace.start_time,
                    end_time=new_trace.end_time,
                    input=new_trace.input,
                    output=new_trace.output,
                    metadata=new_trace.metadata,
                    tags=new_trace.tags,
                    thread_id=new_trace.thread_id,
                    error_info=new_trace.error_info,
                )

                # Create spans using the prepared data
                for span_data_obj in new_span_data:
                    client.span(
                        trace_id=trace_obj.id,
                        parent_span_id=span_data_obj.parent_span_id,
                        name=span_data_obj.name,
                        type=span_data_obj.type,
                        start_time=span_data_obj.start_time,
                        end_time=span_data_obj.end_time,
                        input=span_data_obj.input,
                        output=span_data_obj.output,
                        metadata=span_data_obj.metadata,
                        tags=span_data_obj.tags,
                        usage=span_data_obj.usage,
                        model=span_data_obj.model,
                        provider=span_data_obj.provider,
                        error_info=span_data_obj.error_info,
                    )

                imported_count += 1
                progress.update(
                    task,
                    description=f"Imported {imported_count}/{len(trace_files)} traces",
                )

            except Exception as e:
                console.print(
                    f"[red]Error importing trace from {trace_file.name}: {e}[/red]"
                )
                continue

    return imported_count


def _import_datasets(
    client: opik.Opik,
    project_dir: Path,
    dry_run: bool,
    name_pattern: Optional[str] = None,
) -> int:
    """Import datasets from JSON files."""
    dataset_files = list(project_dir.glob("dataset_*.json"))

    if not dataset_files:
        console.print(f"[yellow]No dataset files found in {project_dir}[/yellow]")
        return 0

    imported_count = 0
    for dataset_file in dataset_files:
        try:
            with open(dataset_file, "r", encoding="utf-8") as f:
                dataset_data = json.load(f)

            # Filter by name pattern if specified
            dataset_name = dataset_data.get("name", "")
            if name_pattern and not _matches_name_pattern(dataset_name, name_pattern):
                continue

            if dry_run:
                print(f"Would upload dataset: {dataset_data['name']}")
                imported_count += 1
                continue

            # Check if dataset already exists
            try:
                client.get_dataset(dataset_data["name"])
                console.print(
                    f"[yellow]Dataset '{dataset_data['name']}' already exists, skipping...[/yellow]"
                )
                imported_count += 1
                continue
            except ApiError as e:
                if e.status_code == 404:
                    # Dataset doesn't exist, create it
                    pass
                else:
                    # Re-raise other API errors (network, auth, etc.)
                    raise

            # Create dataset
            dataset = client.create_dataset(
                name=dataset_data["name"], description=dataset_data.get("description")
            )

            # Insert dataset items
            for item in dataset_data.get("items", []):
                dataset.insert(
                    [
                        {
                            "input": item["input"],
                            "expected_output": item["expected_output"],
                            "metadata": item.get("metadata"),
                        }
                    ]
                )

            imported_count += 1

        except Exception as e:
            console.print(
                f"[red]Error importing dataset from {dataset_file.name}: {e}[/red]"
            )
            continue

    return imported_count


def _import_prompts(
    client: opik.Opik,
    project_dir: Path,
    dry_run: bool,
    name_pattern: Optional[str] = None,
) -> int:
    """Import prompts from JSON files."""
    prompt_files = list(project_dir.glob("prompt_*.json"))

    if not prompt_files:
        console.print(f"[yellow]No prompt files found in {project_dir}[/yellow]")
        return 0

    imported_count = 0
    for prompt_file in prompt_files:
        try:
            with open(prompt_file, "r", encoding="utf-8") as f:
                prompt_data = json.load(f)

            # Filter by name pattern if specified
            prompt_name = prompt_data.get("name", "")
            if name_pattern and not _matches_name_pattern(prompt_name, name_pattern):
                continue

            if dry_run:
                print(f"Would upload prompt: {prompt_data['name']}")
                imported_count += 1
                continue

            # Create prompt
            client.create_prompt(
                name=prompt_data["name"],
                prompt=prompt_data["current_version"]["prompt"],
                metadata=prompt_data["current_version"].get("metadata"),
            )

            imported_count += 1

        except Exception as e:
            console.print(
                f"[red]Error importing prompt from {prompt_file.name}: {e}[/red]"
            )
            continue

    return imported_count


def _find_experiment_files(data_dir: Path) -> List[Path]:
    """Find all experiment JSON files in the directory."""
    return list(data_dir.glob("experiment_*.json"))


def _load_experiment_data(experiment_file: Path) -> Dict[str, Any]:
    """Load experiment data from JSON file."""
    with open(experiment_file, "r", encoding="utf-8") as f:
        return json.load(f)


def _find_trace_by_id(
    client: opik.Opik, trace_id: str, project_name: str
) -> Optional[Any]:
    """Find a trace by ID in the specified project."""
    try:
        traces = client.search_traces(
            project_name=project_name,
            trace_id=trace_id,
            max_results=1,
            truncate=False,
        )
        return traces[0] if traces else None
    except Exception:
        return None


def _find_dataset_item_by_content(
    dataset: Any, expected_content: Dict[str, Any]
) -> Optional[str]:
    """Find a dataset item by matching its content."""
    try:
        items = dataset.get_items()
        for item in items:
            # Compare key fields for matching
            if item.get("input") == expected_content.get("input") and item.get(
                "expected_output"
            ) == expected_content.get("expected_output"):
                return item.get("id")
    except Exception:
        pass
    return None


def _create_dataset_item(dataset: Any, item_data: Dict[str, Any]) -> str:
    """Create a dataset item and return its ID."""
    new_item = {
        "input": item_data.get("input"),
        "expected_output": item_data.get("expected_output"),
        "metadata": item_data.get("metadata"),
    }

    dataset.insert([new_item])

    # Find the newly created item
    items = dataset.get_items()
    for item in items:
        if (
            item.get("input") == new_item["input"]
            and item.get("expected_output") == new_item["expected_output"]
        ):
            return item.get("id")

    dataset_name = getattr(dataset, "name", None)
    raise Exception(
        f"Failed to create dataset item. "
        f"Input: {new_item.get('input')!r}, "
        f"Expected Output: {new_item.get('expected_output')!r}"
        + (f", Dataset: {dataset_name!r}" if dataset_name else "")
    )


def _handle_trace_reference(item_data: Dict[str, Any]) -> Optional[str]:
    """Handle trace references from deduplicated exports."""
    trace_reference = item_data.get("trace_reference")
    if trace_reference:
        trace_id = trace_reference.get("trace_id")
        console.print(f"[blue]Using trace reference: {trace_id}[/blue]")
        return trace_id

    # Fall back to direct trace_id
    return item_data.get("trace_id")


def _recreate_experiment(
    client: opik.Opik,
    experiment_data: Dict[str, Any],
    project_name: str,
    dry_run: bool = False,
) -> bool:
    """Recreate a single experiment from exported data."""
    experiment_info = experiment_data["experiment"]
    items_data = experiment_data["items"]

    experiment_name = (
        experiment_info.get("name") or f"recreated-{experiment_info['id']}"
    )
    dataset_name = experiment_info["dataset_name"]

    console.print(f"[blue]Recreating experiment: {experiment_name}[/blue]")

    if dry_run:
        console.print(
            f"[yellow]Would create experiment '{experiment_name}' with {len(items_data)} items[/yellow]"
        )
        return True

    try:
        # Get or create the dataset
        try:
            dataset = client.get_dataset(dataset_name)
        except ApiError as e:
            if e.status_code == 404:
                console.print(
                    f"[yellow]Dataset '{dataset_name}' not found, creating it...[/yellow]"
                )
                dataset = client.create_dataset(
                    name=dataset_name,
                    description=f"Recreated dataset for experiment {experiment_name}",
                )
            else:
                raise

        # Create the experiment
        experiment = client.create_experiment(
            dataset_name=dataset_name,
            name=experiment_name,
            experiment_config=experiment_info.get("metadata"),
            type=experiment_info.get("type", "regular"),
        )

        # Process experiment items
        experiment_items = []
        successful_items = 0
        skipped_items = 0

        for item_data in items_data:
            # Handle trace reference (from deduplicated exports)
            trace_id = _handle_trace_reference(item_data)
            if not trace_id:
                console.print(
                    "[yellow]Warning: No trace ID found, skipping item[/yellow]"
                )
                skipped_items += 1
                continue

            # Find the trace
            trace = _find_trace_by_id(client, trace_id, project_name)
            if not trace:
                console.print(
                    f"[yellow]Warning: Trace {trace_id} not found, skipping item[/yellow]"
                )
                skipped_items += 1
                continue

            # Handle dataset item
            dataset_item_data = item_data.get("dataset_item_data", {})
            if not dataset_item_data:
                console.print(
                    "[yellow]Warning: No dataset item data, skipping item[/yellow]"
                )
                skipped_items += 1
                continue

            try:
                # Find or create dataset item
                dataset_item_id = _find_dataset_item_by_content(
                    dataset, dataset_item_data
                )
                if not dataset_item_id:
                    dataset_item_id = _create_dataset_item(dataset, dataset_item_data)

                # Create experiment item reference
                experiment_items.append(
                    opik.ExperimentItemReferences(
                        dataset_item_id=dataset_item_id,
                        trace_id=trace_id,
                    )
                )
                successful_items += 1

            except Exception as e:
                console.print(
                    f"[yellow]Warning: Failed to handle dataset item: {e}[/yellow]"
                )
                skipped_items += 1
                continue

        # Insert experiment items
        if experiment_items:
            experiment.insert(experiment_items)
            console.print(
                f"[green]Created experiment '{experiment_name}' with {successful_items} items[/green]"
            )
            if skipped_items > 0:
                console.print(
                    f"[yellow]Skipped {skipped_items} items due to missing data[/yellow]"
                )
        else:
            console.print(
                f"[yellow]No valid items found for experiment '{experiment_name}'[/yellow]"
            )

        return True

    except Exception as e:
        console.print(
            f"[red]Error recreating experiment '{experiment_name}': {e}[/red]"
        )
        return False


def _recreate_experiments(
    client: opik.Opik,
    project_dir: Path,
    project_name: str,
    dry_run: bool = False,
    name_pattern: Optional[str] = None,
) -> int:
    """Recreate experiments from JSON files."""
    experiment_files = _find_experiment_files(project_dir)

    if not experiment_files:
        console.print(f"[yellow]No experiment files found in {project_dir}[/yellow]")
        return 0

    console.print(f"[green]Found {len(experiment_files)} experiment files[/green]")

    # Filter experiments by name pattern if specified
    if name_pattern:
        filtered_files = []
        for exp_file in experiment_files:
            try:
                exp_data = _load_experiment_data(exp_file)
                exp_name = exp_data.get("experiment", {}).get("name", "")
                if exp_name and _matches_name_pattern(exp_name, name_pattern):
                    filtered_files.append(exp_file)
            except Exception:
                continue

        if filtered_files:
            console.print(
                f"[blue]Filtered to {len(filtered_files)} experiments matching pattern '{name_pattern}'[/blue]"
            )
            experiment_files = filtered_files
        else:
            console.print(
                f"[yellow]No experiments found matching pattern '{name_pattern}'[/yellow]"
            )
            return 0

    successful = 0
    failed = 0

    for experiment_file in experiment_files:
        try:
            experiment_data = _load_experiment_data(experiment_file)

            if _recreate_experiment(client, experiment_data, project_name, dry_run):
                successful += 1
            else:
                failed += 1

        except Exception as e:
            console.print(f"[red]Error processing {experiment_file.name}: {e}[/red]")
            failed += 1
            continue

    return successful


@click.command(name="import")
@click.argument(
    "workspace_folder",
    type=click.Path(file_okay=False, dir_okay=True, readable=True),
)
@click.argument("workspace_name", type=str)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Show what would be imported without actually importing.",
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
    help="Data types to include in upload. Can be specified multiple times. Defaults to traces only.",
)
@click.option(
    "--exclude",
    type=click.Choice(["traces", "datasets", "prompts"], case_sensitive=False),
    multiple=True,
    help="Data types to exclude from upload. Can be specified multiple times.",
)
@click.option(
    "--name",
    type=str,
    help="Filter items by name using Python regex patterns. Matches against trace names, dataset names, or prompt names.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the import process.",
)
@click.option(
    "--recreate-experiments",
    is_flag=True,
    help="Automatically recreate experiments after importing foundational data. Requires experiment JSON files to be present.",
)
def import_data(
    workspace_folder: str,
    workspace_name: str,
    dry_run: bool,
    all: bool,
    include: tuple,
    exclude: tuple,
    name: Optional[str],
    debug: bool,
    recreate_experiments: bool,
) -> None:
    """
    Upload data from local files to a workspace or workspace/project.

    This command reads data from JSON files in the specified workspace folder
    and imports them to the specified workspace or project.

    Note: Thread metadata is automatically calculated from traces with the same thread_id,
    so threads don't need to be imported separately.

    WORKSPACE_FOLDER: Directory containing JSON files to import.
    WORKSPACE_NAME: Either a workspace name (e.g., "my-workspace") to import to all projects,
                   or workspace/project (e.g., "my-workspace/my-project") to import to a specific project.
    """
    try:
        if debug:
            console.print("[blue]DEBUG: Starting import with parameters:[/blue]")
            console.print(f"[blue]  workspace_folder: {workspace_folder}[/blue]")
            console.print(f"[blue]  workspace_name: {workspace_name}[/blue]")
            console.print(f"[blue]  include: {include}[/blue]")
            console.print(f"[blue]  debug: {debug}[/blue]")

        # Parse workspace/project from the argument
        if "/" in workspace_name:
            workspace, project_name = workspace_name.split("/", 1)
            import_to_specific_project = True
            if debug:
                console.print(
                    f"[blue]DEBUG: Parsed workspace: {workspace}, project: {project_name}[/blue]"
                )
        else:
            # Only workspace specified - upload to all projects
            workspace = workspace_name
            project_name = None
            import_to_specific_project = False
            if debug:
                console.print(f"[blue]DEBUG: Workspace only: {workspace}[/blue]")

        # Initialize Opik client with workspace
        if debug:
            console.print(
                f"[blue]DEBUG: Initializing Opik client with workspace: {workspace}[/blue]"
            )
        client = opik.Opik(workspace=workspace)

        # Use the specified workspace folder directly
        project_dir = Path(workspace_folder)

        # Determine which data types to upload
        if all:
            # If --all is specified, include all data types
            include_set = {"traces", "datasets", "prompts"}
        else:
            include_set = set(item.lower() for item in include)

        exclude_set = set(item.lower() for item in exclude)

        # Apply exclusions
        data_types = include_set - exclude_set

        if not project_dir.exists():
            console.print(f"[red]Error: Directory not found: {project_dir}[/red]")
            console.print("[yellow]Make sure the path is correct.[/yellow]")
            sys.exit(1)

        console.print(f"[green]Uploading data from {project_dir}[/green]")

        if import_to_specific_project:
            console.print(
                f"[blue]Uploading to workspace: {workspace}, project: {project_name}[/blue]"
            )
        else:
            console.print(
                f"[blue]Uploading to workspace: {workspace} (all projects)[/blue]"
            )

        if debug:
            console.print(f"[blue]Data types: {', '.join(sorted(data_types))}[/blue]")

        # Note about workspace vs project-specific data
        project_specific = [dt for dt in data_types if dt in ["traces"]]
        workspace_data = [dt for dt in data_types if dt in ["datasets", "prompts"]]

        if project_specific and workspace_data:
            if import_to_specific_project:
                console.print(
                    f"[yellow]Note: {', '.join(project_specific)} will be imported to project '{project_name}', {', '.join(workspace_data)} belong to workspace '{workspace}'[/yellow]"
                )
            else:
                console.print(
                    f"[yellow]Note: {', '.join(project_specific)} will be imported to all projects, {', '.join(workspace_data)} belong to workspace '{workspace}'[/yellow]"
                )
        elif workspace_data:
            console.print(
                f"[yellow]Note: {', '.join(workspace_data)} belong to workspace '{workspace}'[/yellow]"
            )

        if dry_run:
            console.print("[yellow]Dry run mode - no data will be imported[/yellow]")

        if import_to_specific_project:
            # Upload to specific project
            # Create a new client instance with the specific project name
            assert project_name is not None  # Type narrowing for mypy
            client = opik.Opik(workspace=workspace, project_name=project_name)

            # Upload each data type
            total_imported = 0

            # Upload traces
            if "traces" in data_types:
                if debug:
                    console.print("[blue]Uploading traces...[/blue]")
                traces_imported = _import_traces(client, project_dir, dry_run, name)
                total_imported += traces_imported

            # Upload datasets
            if "datasets" in data_types:
                if debug:
                    console.print("[blue]Uploading datasets...[/blue]")
                datasets_imported = _import_datasets(client, project_dir, dry_run, name)
                total_imported += datasets_imported

            # Upload prompts
            if "prompts" in data_types:
                if debug:
                    console.print("[blue]Uploading prompts...[/blue]")
                prompts_imported = _import_prompts(client, project_dir, dry_run, name)
                total_imported += prompts_imported

            # Recreate experiments if requested
            if recreate_experiments:
                if debug:
                    console.print("[blue]Recreating experiments...[/blue]")
                experiments_recreated = _recreate_experiments(
                    client, project_dir, project_name, dry_run, name
                )
                total_imported += experiments_recreated

            if dry_run:
                console.print(
                    f"[green]Dry run complete: Would import {total_imported} items[/green]"
                )
            else:
                console.print(
                    f"[green]Successfully imported {total_imported} items to project '{project_name}'[/green]"
                )
        else:
            # Upload to all projects in workspace
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

                # Upload workspace-level data once (datasets, experiments, prompts)
                total_imported = 0

                # Upload datasets
                if "datasets" in data_types:
                    if debug:
                        console.print("[blue]Uploading datasets...[/blue]")
                    datasets_imported = _import_datasets(
                        client, project_dir, dry_run, name
                    )
                    total_imported += datasets_imported

                # Upload prompts
                if "prompts" in data_types:
                    if debug:
                        console.print("[blue]Uploading prompts...[/blue]")
                    prompts_imported = _import_prompts(
                        client, project_dir, dry_run, name
                    )
                    total_imported += prompts_imported

                # Recreate experiments if requested
                if recreate_experiments:
                    if debug:
                        console.print("[blue]Recreating experiments...[/blue]")
                    experiments_recreated = _recreate_experiments(
                        client, project_dir, "default", dry_run, name
                    )
                    total_imported += experiments_recreated

                # Note: Traces are project-specific and should be imported to a specific project
                # rather than being uploaded to all projects in a workspace
                if "traces" in data_types:
                    console.print(
                        "[yellow]Note: Traces are project-specific. Use workspace/project format to import traces to a specific project.[/yellow]"
                    )

                if dry_run:
                    console.print(
                        f"[green]Dry run complete: Would import {total_imported} items to workspace '{workspace}'[/green]"
                    )
                else:
                    console.print(
                        f"[green]Successfully imported {total_imported} items to workspace '{workspace}'[/green]"
                    )

            except Exception as e:
                console.print(f"[red]Error getting projects from workspace: {e}[/red]")
                sys.exit(1)

    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        sys.exit(1)
