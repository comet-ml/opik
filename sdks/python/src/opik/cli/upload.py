"""Upload command for Opik CLI."""

import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Optional

import click
from rich.console import Console
from rich.progress import Progress, SpinnerColumn, TextColumn

import opik
from opik.rest_api.core.api_error import ApiError

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


def _upload_traces(
    client: Any, project_dir: Path, dry_run: bool, name_pattern: Optional[str] = None
) -> int:
    """Upload traces from JSON files."""
    trace_files = list(project_dir.glob("trace_*.json"))

    if not trace_files:
        console.print(f"[yellow]No trace files found in {project_dir}[/yellow]")
        return 0

    uploaded_count = 0
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
                    console.print(
                        f"[blue]Would upload trace: {trace_data['trace']['id']}[/blue]"
                    )
                    uploaded_count += 1
                    progress.update(
                        task,
                        description=f"Processed {uploaded_count}/{len(trace_files)} traces",
                    )
                    continue

                # Extract trace information
                trace_info = trace_data["trace"]
                spans_info = trace_data.get("spans", [])

                # Create the trace (let system generate new ID)
                trace_obj = client.trace(
                    name=trace_info.get("name"),
                    start_time=(
                        datetime.fromisoformat(
                            trace_info["start_time"].replace("Z", "+00:00")
                        )
                        if trace_info.get("start_time")
                        else None
                    ),
                    end_time=(
                        datetime.fromisoformat(
                            trace_info["end_time"].replace("Z", "+00:00")
                        )
                        if trace_info.get("end_time")
                        else None
                    ),
                    input=trace_info.get("input"),
                    output=trace_info.get("output"),
                    metadata=trace_info.get("metadata"),
                    tags=trace_info.get("tags"),
                    thread_id=trace_info.get("thread_id"),
                    error_info=trace_info.get("error_info"),
                )

                # Create spans for this trace (let system generate new IDs)
                # First, create a mapping of old span IDs to new span IDs
                span_id_mapping: Dict[str, str] = {}

                # Sort spans by hierarchy (root spans first, then children)
                # Build a mapping from span ID to span info for O(1) parent lookup
                span_id_to_info = {span.get("id"): span for span in spans_info}
                span_depths: Dict[str, int] = {}

                def compute_span_depth(span_info: Dict[str, Any]) -> int:
                    span_id = span_info.get("id")
                    if span_id is None:
                        return 0  # Skip spans without IDs
                    if span_id in span_depths:
                        return span_depths[span_id]
                    parent_id = span_info.get("parent_span_id")
                    if parent_id is None:
                        depth = 0
                    elif parent_id in span_id_to_info:
                        depth = compute_span_depth(span_id_to_info[parent_id]) + 1
                    else:
                        depth = 1  # If parent not found, assume depth 1
                    span_depths[span_id] = depth
                    return depth

                sorted_spans = sorted(spans_info, key=compute_span_depth)

                for span_info in sorted_spans:
                    old_span_id = span_info.get("id")
                    old_parent_id = span_info.get("parent_span_id")

                    # Map old parent ID to new parent ID
                    new_parent_id = (
                        span_id_mapping.get(old_parent_id) if old_parent_id else None
                    )

                    # Create the span
                    new_span = client.span(
                        trace_id=trace_obj.id,  # Use the new trace ID
                        parent_span_id=new_parent_id,
                        name=span_info.get("name"),
                        type=span_info.get("type", "general"),
                        start_time=(
                            datetime.fromisoformat(
                                span_info["start_time"].replace("Z", "+00:00")
                            )
                            if span_info.get("start_time")
                            else None
                        ),
                        end_time=(
                            datetime.fromisoformat(
                                span_info["end_time"].replace("Z", "+00:00")
                            )
                            if span_info.get("end_time")
                            else None
                        ),
                        input=span_info.get("input"),
                        output=span_info.get("output"),
                        metadata=span_info.get("metadata"),
                        tags=span_info.get("tags"),
                        usage=span_info.get("usage"),
                        model=span_info.get("model"),
                        provider=span_info.get("provider"),
                        error_info=span_info.get("error_info"),
                    )

                    # Store the mapping of old ID to new ID
                    span_id_mapping[old_span_id] = new_span.id

                uploaded_count += 1
                progress.update(
                    task,
                    description=f"Uploaded {uploaded_count}/{len(trace_files)} traces",
                )

            except Exception as e:
                console.print(
                    f"[red]Error uploading trace from {trace_file.name}: {e}[/red]"
                )
                continue

    return uploaded_count


def _upload_datasets(
    client: Any, project_dir: Path, dry_run: bool, name_pattern: Optional[str] = None
) -> int:
    """Upload datasets from JSON files."""
    dataset_files = list(project_dir.glob("dataset_*.json"))

    if not dataset_files:
        console.print(f"[yellow]No dataset files found in {project_dir}[/yellow]")
        return 0

    uploaded_count = 0
    for dataset_file in dataset_files:
        try:
            with open(dataset_file, "r", encoding="utf-8") as f:
                dataset_data = json.load(f)

            # Filter by name pattern if specified
            dataset_name = dataset_data.get("name", "")
            if name_pattern and not _matches_name_pattern(dataset_name, name_pattern):
                continue

            if dry_run:
                console.print(
                    f"[blue]Would upload dataset: {dataset_data['name']}[/blue]"
                )
                uploaded_count += 1
                continue

            # Check if dataset already exists
            try:
                client.get_dataset(dataset_data["name"])
                console.print(
                    f"[yellow]Dataset '{dataset_data['name']}' already exists, skipping...[/yellow]"
                )
                uploaded_count += 1
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

            uploaded_count += 1

        except Exception as e:
            console.print(
                f"[red]Error uploading dataset from {dataset_file.name}: {e}[/red]"
            )
            continue

    return uploaded_count


def _upload_experiments(
    client: Any, project_dir: Path, dry_run: bool, name_pattern: Optional[str] = None
) -> int:
    """Upload experiments from JSON files."""
    experiment_files = list(project_dir.glob("experiment_*.json"))

    if not experiment_files:
        console.print(f"[yellow]No experiment files found in {project_dir}[/yellow]")
        return 0

    uploaded_count = 0
    for experiment_file in experiment_files:
        try:
            with open(experiment_file, "r", encoding="utf-8") as f:
                experiment_data = json.load(f)

            # Filter by name pattern if specified
            experiment_name = experiment_data.get("name", "")
            if name_pattern and not _matches_name_pattern(
                experiment_name, name_pattern
            ):
                continue

            if dry_run:
                console.print(
                    f"[blue]Would upload experiment: {experiment_data['name']}[/blue]"
                )
                uploaded_count += 1
                continue

            # Check if experiment already exists
            existing_experiments = client.get_experiments_by_name(
                experiment_data["name"]
            )
            if existing_experiments:
                console.print(
                    f"[yellow]Experiment '{experiment_data['name']}' already exists, skipping...[/yellow]"
                )
                uploaded_count += 1
                continue

            # Create experiment with basic metadata
            # Note: We only create the experiment metadata, not the trace relationships
            # The traces will maintain their experiment_id when uploaded separately
            client.create_experiment(
                dataset_name=experiment_data["dataset_name"],
                name=experiment_data["name"],
                experiment_config=experiment_data["experiment_data"].get("metadata"),
                type=experiment_data["experiment_data"].get("type", "regular"),
            )

            console.print(
                f"[green]Created experiment: {experiment_data['name']}[/green]"
            )
            uploaded_count += 1

        except Exception as e:
            console.print(
                f"[red]Error uploading experiment from {experiment_file.name}: {e}[/red]"
            )
            continue

    return uploaded_count


def _upload_prompts(
    client: Any, project_dir: Path, dry_run: bool, name_pattern: Optional[str] = None
) -> int:
    """Upload prompts from JSON files."""
    prompt_files = list(project_dir.glob("prompt_*.json"))

    if not prompt_files:
        console.print(f"[yellow]No prompt files found in {project_dir}[/yellow]")
        return 0

    uploaded_count = 0
    for prompt_file in prompt_files:
        try:
            with open(prompt_file, "r", encoding="utf-8") as f:
                prompt_data = json.load(f)

            # Filter by name pattern if specified
            prompt_name = prompt_data.get("name", "")
            if name_pattern and not _matches_name_pattern(prompt_name, name_pattern):
                continue

            if dry_run:
                console.print(
                    f"[blue]Would upload prompt: {prompt_data['name']}[/blue]"
                )
                uploaded_count += 1
                continue

            # Create prompt
            client.create_prompt(
                name=prompt_data["name"],
                prompt=prompt_data["current_version"]["prompt"],
                metadata=prompt_data["current_version"].get("metadata"),
            )

            uploaded_count += 1

        except Exception as e:
            console.print(
                f"[red]Error uploading prompt from {prompt_file.name}: {e}[/red]"
            )
            continue

    return uploaded_count


@click.command()
@click.argument("workspace_folder", type=click.Path(file_okay=False, dir_okay=True, readable=True))
@click.argument("workspace_name", type=str)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Show what would be uploaded without actually uploading.",
)
@click.option(
    "--all",
    is_flag=True,
    help="Include all data types (traces, datasets, experiments, prompts).",
)
@click.option(
    "--include",
    type=click.Choice(
        ["traces", "datasets", "experiments", "prompts"], case_sensitive=False
    ),
    multiple=True,
    default=["traces"],
    help="Data types to include in upload. Can be specified multiple times. Defaults to traces only.",
)
@click.option(
    "--exclude",
    type=click.Choice(
        ["traces", "datasets", "experiments", "prompts"], case_sensitive=False
    ),
    multiple=True,
    help="Data types to exclude from upload. Can be specified multiple times.",
)
@click.option(
    "--name",
    type=str,
    help="Filter items by name using Python regex patterns. Matches against trace names, dataset names, experiment names, or prompt names.",
)
def upload(
    workspace_folder: str,
    workspace_name: str,
    dry_run: bool,
    all: bool,
    include: tuple,
    exclude: tuple,
    name: Optional[str],
) -> None:
    """
    Upload data from local files to a workspace or workspace/project.

    This command reads data from JSON files in the specified workspace folder
    and uploads them to the specified workspace or project.

    Note: Thread metadata is automatically calculated from traces with the same thread_id,
    so threads don't need to be uploaded separately.

    WORKSPACE_FOLDER: Directory containing JSON files to upload.
    WORKSPACE_NAME: Either a workspace name (e.g., "my-workspace") to upload to all projects,
                   or workspace/project (e.g., "my-workspace/my-project") to upload to a specific project.
    """
    try:
        # Parse workspace/project from the argument
        if "/" in workspace_name:
            workspace, project_name = workspace_name.split("/", 1)
            upload_to_specific_project = True
        else:
            # Only workspace specified - upload to all projects
            workspace = workspace_name
            project_name = None
            upload_to_specific_project = False

        # Initialize Opik client with workspace
        client = opik.Opik(workspace=workspace)

        # Use the specified workspace folder directly
        project_dir = Path(workspace_folder)

        # Determine which data types to upload
        if all:
            # If --all is specified, include all data types
            include_set = {"traces", "datasets", "experiments", "prompts"}
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

        if upload_to_specific_project:
            console.print(
                f"[blue]Uploading to workspace: {workspace}, project: {project_name}[/blue]"
            )
        else:
            console.print(
                f"[blue]Uploading to workspace: {workspace} (all projects)[/blue]"
            )

        console.print(f"[blue]Data types: {', '.join(sorted(data_types))}[/blue]")

        # Note about workspace vs project-specific data
        project_specific = [dt for dt in data_types if dt in ["traces"]]
        workspace_data = [
            dt for dt in data_types if dt in ["datasets", "experiments", "prompts"]
        ]

        if project_specific and workspace_data:
            if upload_to_specific_project:
                console.print(
                    f"[yellow]Note: {', '.join(project_specific)} will be uploaded to project '{project_name}', {', '.join(workspace_data)} belong to workspace '{workspace}'[/yellow]"
                )
            else:
                console.print(
                    f"[yellow]Note: {', '.join(project_specific)} will be uploaded to all projects, {', '.join(workspace_data)} belong to workspace '{workspace}'[/yellow]"
                )
        elif workspace_data:
            console.print(
                f"[yellow]Note: {', '.join(workspace_data)} belong to workspace '{workspace}'[/yellow]"
            )

        if dry_run:
            console.print("[yellow]Dry run mode - no data will be uploaded[/yellow]")

        if upload_to_specific_project:
            # Upload to specific project
            # Set the project name for the client
            assert project_name is not None  # Type narrowing for mypy
            client._project_name = project_name

            # Upload each data type
            total_uploaded = 0

            # Upload traces
            if "traces" in data_types:
                console.print("[blue]Uploading traces...[/blue]")
                traces_uploaded = _upload_traces(client, project_dir, dry_run, name)
                total_uploaded += traces_uploaded

            # Upload datasets
            if "datasets" in data_types:
                console.print("[blue]Uploading datasets...[/blue]")
                datasets_uploaded = _upload_datasets(client, project_dir, dry_run, name)
                total_uploaded += datasets_uploaded

            # Upload experiments
            if "experiments" in data_types:
                console.print("[blue]Uploading experiments...[/blue]")
                experiments_uploaded = _upload_experiments(
                    client, project_dir, dry_run, name
                )
                total_uploaded += experiments_uploaded

            # Upload prompts
            if "prompts" in data_types:
                console.print("[blue]Uploading prompts...[/blue]")
                prompts_uploaded = _upload_prompts(client, project_dir, dry_run, name)
                total_uploaded += prompts_uploaded

            if dry_run:
                console.print(
                    f"[green]Dry run complete: Would upload {total_uploaded} items[/green]"
                )
            else:
                console.print(
                    f"[green]Successfully uploaded {total_uploaded} items to project '{project_name}'[/green]"
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
                total_uploaded = 0

                # Upload datasets
                if "datasets" in data_types:
                    console.print("[blue]Uploading datasets...[/blue]")
                    datasets_uploaded = _upload_datasets(
                        client, project_dir, dry_run, name
                    )
                    total_uploaded += datasets_uploaded

                # Upload experiments
                if "experiments" in data_types:
                    console.print("[blue]Uploading experiments...[/blue]")
                    experiments_uploaded = _upload_experiments(
                        client, project_dir, dry_run, name
                    )
                    total_uploaded += experiments_uploaded

                # Upload prompts
                if "prompts" in data_types:
                    console.print("[blue]Uploading prompts...[/blue]")
                    prompts_uploaded = _upload_prompts(
                        client, project_dir, dry_run, name
                    )
                    total_uploaded += prompts_uploaded

                # Upload traces to each project
                if "traces" in data_types:
                    for project in projects:
                        project_name = project.name
                        console.print(
                            f"[blue]Uploading traces to project: {project_name}...[/blue]"
                        )

                        # Set the project name for the client
                        assert project_name is not None  # Type narrowing for mypy
                        client._project_name = project_name

                        traces_uploaded = _upload_traces(
                            client, project_dir, dry_run, name
                        )
                        total_uploaded += traces_uploaded

                        if traces_uploaded > 0:
                            console.print(
                                f"[green]Uploaded {traces_uploaded} traces to {project_name}[/green]"
                            )

                if dry_run:
                    console.print(
                        f"[green]Dry run complete: Would upload {total_uploaded} items to workspace '{workspace}'[/green]"
                    )
                else:
                    console.print(
                        f"[green]Successfully uploaded {total_uploaded} items to workspace '{workspace}'[/green]"
                    )

            except Exception as e:
                console.print(f"[red]Error getting projects from workspace: {e}[/red]")
                sys.exit(1)

    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        sys.exit(1)
