"""Upload command for Opik CLI."""

import json
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

# Constants for import command
DEFAULT_PROJECT_NAME = "default"


def _matches_name_pattern(name: str, pattern: Optional[str]) -> bool:
    """Check if a name matches the given pattern using case-insensitive substring matching."""
    if pattern is None:
        return True
    # Simple string matching - check if pattern is contained in name (case-insensitive)
    return pattern.lower() in name.lower()


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
        # Use the proper API method to get trace by ID
        trace = client.get_trace_content(trace_id)
        return trace
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
    dataset_info = f", Dataset: {dataset_name!r}" if dataset_name else ""
    raise Exception(
        f"Failed to create dataset item. "
        f"Input: {new_item.get('input')!r}, "
        f"Expected Output: {new_item.get('expected_output')!r}{dataset_info}"
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


def _import_by_type(
    import_type: str,
    workspace_folder: str,
    workspace: str,
    dry_run: bool,
    name_pattern: Optional[str],
    debug: bool,
    recreate_experiments: bool = False,
) -> None:
    """
    Import data by type (dataset, project, experiment) with pattern matching.

    Args:
        import_type: Type of data to import ("dataset", "project", "experiment")
        workspace_folder: Base workspace folder containing the data
        workspace: Target workspace name
        dry_run: Whether to show what would be imported without importing
        name_pattern: Optional string pattern to filter items by name (case-insensitive substring matching)
        debug: Enable debug output
        recreate_experiments: Whether to recreate experiments after importing
    """
    try:
        if debug:
            console.print(
                f"[blue]DEBUG: Starting {import_type} import from {workspace_folder}[/blue]"
            )

        # Initialize Opik client
        client = opik.Opik(workspace=workspace)

        # Determine source directory based on import type
        base_path = Path(workspace_folder)

        if import_type == "dataset":
            source_dir = base_path / "datasets"
        elif import_type == "project":
            source_dir = base_path / "projects"
        elif import_type == "experiment":
            source_dir = base_path / "experiments"
        elif import_type == "prompt":
            source_dir = base_path / "prompts"
        else:
            console.print(f"[red]Unknown import type: {import_type}[/red]")
            return

        if not source_dir.exists():
            console.print(f"[red]Source directory {source_dir} does not exist[/red]")
            sys.exit(1)

        if debug:
            console.print(f"[blue]Source directory: {source_dir}[/blue]")

        imported_count = 0

        if import_type == "dataset":
            imported_count = _import_datasets_from_directory(
                client, source_dir, dry_run, name_pattern, debug
            )
        elif import_type == "project":
            imported_count = _import_projects_from_directory(
                client, source_dir, dry_run, name_pattern, debug, recreate_experiments
            )
        elif import_type == "experiment":
            imported_count = _import_experiments_from_directory(
                client, source_dir, dry_run, name_pattern, debug, recreate_experiments
            )
        elif import_type == "prompt":
            imported_count = _import_prompts_from_directory(
                client, source_dir, dry_run, name_pattern, debug
            )

        if dry_run:
            console.print(
                f"[blue]Dry run complete: Would import {imported_count} {import_type}s[/blue]"
            )
        else:
            console.print(
                f"[green]Successfully imported {imported_count} {import_type}s[/green]"
            )

    except Exception as e:
        console.print(f"[red]Error importing {import_type}s: {e}[/red]")
        sys.exit(1)


def _import_datasets_from_directory(
    client: opik.Opik,
    source_dir: Path,
    dry_run: bool,
    name_pattern: Optional[str],
    debug: bool,
) -> int:
    """Import datasets from a directory."""
    try:
        dataset_files = list(source_dir.glob("dataset_*.json"))

        if not dataset_files:
            console.print("[yellow]No dataset files found in the directory[/yellow]")
            return 0

        imported_count = 0
        for dataset_file in dataset_files:
            try:
                with open(dataset_file, "r", encoding="utf-8") as f:
                    dataset_data = json.load(f)

                dataset_name = dataset_data.get("name", "")

                # Filter by name pattern if specified
                if name_pattern and not _matches_name_pattern(
                    dataset_name, name_pattern
                ):
                    if debug:
                        console.print(
                            f"[blue]Skipping dataset {dataset_name} (doesn't match pattern)[/blue]"
                        )
                    continue

                if dry_run:
                    console.print(f"[blue]Would import dataset: {dataset_name}[/blue]")
                    imported_count += 1
                    continue

                if debug:
                    console.print(f"[blue]Importing dataset: {dataset_name}[/blue]")

                # Create dataset
                dataset = client.create_dataset(name=dataset_name)

                # Import dataset items
                items = dataset_data.get("items", [])
                if items:
                    dataset.insert(items)

                imported_count += 1
                if debug:
                    console.print(
                        f"[green]Imported dataset: {dataset_name} with {len(items)} items[/green]"
                    )

            except Exception as e:
                console.print(
                    f"[red]Error importing dataset from {dataset_file}: {e}[/red]"
                )
                continue

        return imported_count

    except Exception as e:
        console.print(f"[red]Error importing datasets: {e}[/red]")
        return 0


def _import_projects_from_directory(
    client: opik.Opik,
    source_dir: Path,
    dry_run: bool,
    name_pattern: Optional[str],
    debug: bool,
    recreate_experiments: bool = False,
) -> int:
    """Import projects from a directory."""
    try:
        project_dirs = [d for d in source_dir.iterdir() if d.is_dir()]

        if not project_dirs:
            console.print("[yellow]No project directories found[/yellow]")
            return 0

        imported_count = 0
        for project_dir in project_dirs:
            try:
                project_name = project_dir.name

                # Filter by name pattern if specified
                if name_pattern and not _matches_name_pattern(
                    project_name, name_pattern
                ):
                    if debug:
                        console.print(
                            f"[blue]Skipping project {project_name} (doesn't match pattern)[/blue]"
                        )
                    continue

                if dry_run:
                    console.print(f"[blue]Would import project: {project_name}[/blue]")
                    imported_count += 1
                    continue

                if debug:
                    console.print(f"[blue]Importing project: {project_name}[/blue]")

                # Import traces from the project directory
                trace_files = list(project_dir.glob("trace_*.json"))
                traces_imported = 0

                for trace_file in trace_files:
                    try:
                        with open(trace_file, "r", encoding="utf-8") as f:
                            trace_data = json.load(f)

                        # Import trace and spans
                        trace_info = trace_data.get("trace", {})
                        spans_info = trace_data.get("spans", [])

                        # Create trace
                        trace = client.trace(
                            name=trace_info.get("name", "imported_trace"),
                            input=trace_info.get("input", {}),
                            output=trace_info.get("output", {}),
                            project_name=project_name,
                        )

                        # Create spans
                        for span_info in spans_info:
                            client.span(
                                name=span_info.get("name", "imported_span"),
                                input=span_info.get("input", {}),
                                output=span_info.get("output", {}),
                                trace_id=trace.id,
                                project_name=project_name,
                            )

                        traces_imported += 1

                    except Exception as e:
                        console.print(
                            f"[red]Error importing trace from {trace_file}: {e}[/red]"
                        )
                        continue

                # Handle experiment recreation if requested
                if recreate_experiments:
                    experiment_files = list(project_dir.glob("experiment_*.json"))
                    if experiment_files:
                        if debug:
                            console.print(
                                f"[blue]Found {len(experiment_files)} experiment files in project {project_name}[/blue]"
                            )

                        # Recreate experiments
                        experiments_recreated = _recreate_experiments(
                            client, project_dir, project_name, dry_run, name_pattern
                        )

                        if debug and experiments_recreated > 0:
                            console.print(
                                f"[green]Recreated {experiments_recreated} experiments for project {project_name}[/green]"
                            )

                if traces_imported > 0:
                    imported_count += 1
                    if debug:
                        console.print(
                            f"[green]Imported project: {project_name} with {traces_imported} traces[/green]"
                        )

            except Exception as e:
                console.print(
                    f"[red]Error importing project {project_dir.name}: {e}[/red]"
                )
                continue

        return imported_count

    except Exception as e:
        console.print(f"[red]Error importing projects: {e}[/red]")
        return 0


def _import_experiments_from_directory(
    client: opik.Opik,
    source_dir: Path,
    dry_run: bool,
    name_pattern: Optional[str],
    debug: bool,
    recreate_experiments: bool,
) -> int:
    """Import experiments from a directory."""
    try:
        experiment_files = list(source_dir.glob("experiment_*.json"))

        if not experiment_files:
            console.print("[yellow]No experiment files found in the directory[/yellow]")
            return 0

        imported_count = 0
        for experiment_file in experiment_files:
            try:
                with open(experiment_file, "r", encoding="utf-8") as f:
                    experiment_data = json.load(f)

                experiment_info = experiment_data.get("experiment", {})
                experiment_name = experiment_info.get("name", "")

                # Filter by name pattern if specified
                if name_pattern and not _matches_name_pattern(
                    experiment_name, name_pattern
                ):
                    if debug:
                        console.print(
                            f"[blue]Skipping experiment {experiment_name} (doesn't match pattern)[/blue]"
                        )
                    continue

                if dry_run:
                    console.print(
                        f"[blue]Would import experiment: {experiment_name}[/blue]"
                    )
                    imported_count += 1
                    continue

                if debug:
                    console.print(
                        f"[blue]Importing experiment: {experiment_name}[/blue]"
                    )

                # Import experiment using the existing _recreate_experiment function
                success = _recreate_experiment(
                    client,
                    experiment_data,
                    experiment_info.get("dataset_name", "default"),
                    dry_run,
                )

                if success:
                    imported_count += 1
                    if debug:
                        console.print(
                            f"[green]Imported experiment: {experiment_name}[/green]"
                        )

            except Exception as e:
                console.print(
                    f"[red]Error importing experiment from {experiment_file}: {e}[/red]"
                )
                continue

        return imported_count

    except Exception as e:
        console.print(f"[red]Error importing experiments: {e}[/red]")
        return 0


def _import_prompts_from_directory(
    client: opik.Opik,
    source_dir: Path,
    dry_run: bool,
    name_pattern: Optional[str],
    debug: bool,
) -> int:
    """Import prompts from a directory."""
    try:
        prompt_files = list(source_dir.glob("prompt_*.json"))

        if not prompt_files:
            console.print("[yellow]No prompt files found in the directory[/yellow]")
            return 0

        imported_count = 0
        for prompt_file in prompt_files:
            try:
                with open(prompt_file, "r", encoding="utf-8") as f:
                    prompt_data = json.load(f)

                prompt_name = prompt_data.get("name", "")
                if not prompt_name:
                    console.print(
                        f"[yellow]Skipping {prompt_file.name} (no name found)[/yellow]"
                    )
                    continue

                # Filter by name pattern if specified
                if name_pattern and not _matches_name_pattern(
                    prompt_name, name_pattern
                ):
                    if debug:
                        console.print(
                            f"[blue]Skipping prompt {prompt_name} (doesn't match pattern)[/blue]"
                        )
                    continue

                if dry_run:
                    console.print(f"[blue]Would import prompt: {prompt_name}[/blue]")
                    imported_count += 1
                    continue

                if debug:
                    console.print(f"[blue]Importing prompt: {prompt_name}[/blue]")

                # Get current version data
                current_version = prompt_data.get("current_version", {})
                prompt_text = current_version.get("prompt", "")
                metadata = current_version.get("metadata")
                prompt_type = current_version.get("type")

                if not prompt_text:
                    console.print(
                        f"[yellow]Skipping {prompt_name} (no prompt text found)[/yellow]"
                    )
                    continue

                # Create the prompt
                try:
                    # Import the Prompt class and PromptType
                    from opik.api_objects.prompt.prompt import Prompt
                    from opik.api_objects.prompt.types import PromptType

                    # Convert string type to PromptType enum if needed
                    if prompt_type and isinstance(prompt_type, str):
                        try:
                            prompt_type_enum = PromptType(prompt_type)
                        except ValueError:
                            console.print(
                                f"[yellow]Unknown prompt type '{prompt_type}', using MUSTACHE[/yellow]"
                            )
                            prompt_type_enum = PromptType.MUSTACHE
                    else:
                        prompt_type_enum = PromptType.MUSTACHE

                    # Create the prompt
                    Prompt(
                        name=prompt_name,
                        prompt=prompt_text,
                        metadata=metadata,
                        type=prompt_type_enum,
                    )

                    imported_count += 1
                    if debug:
                        console.print(f"[green]Imported prompt: {prompt_name}[/green]")

                except Exception as e:
                    console.print(
                        f"[red]Error creating prompt {prompt_name}: {e}[/red]"
                    )
                    continue

            except Exception as e:
                console.print(
                    f"[red]Error importing prompt from {prompt_file}: {e}[/red]"
                )
                continue

        return imported_count

    except Exception as e:
        console.print(f"[red]Error importing prompts: {e}[/red]")
        return 0


@click.group(name="import")
@click.argument("workspace", type=str)
@click.pass_context
def import_group(ctx: click.Context, workspace: str) -> None:
    """Import data to Opik workspace."""
    ctx.ensure_object(dict)
    ctx.obj["workspace"] = workspace


@import_group.command(name="dataset")
@click.argument(
    "workspace_folder", type=click.Path(file_okay=False, dir_okay=True, readable=True)
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Show what would be imported without actually importing.",
)
@click.option(
    "--name",
    type=str,
    help="Filter datasets by name using string pattern matching (case-insensitive).",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the import process.",
)
@click.pass_context
def import_dataset(
    ctx: click.Context,
    workspace_folder: str,
    dry_run: bool,
    name: Optional[str],
    debug: bool,
) -> None:
    """Import datasets from workspace/datasets directory."""
    workspace = ctx.obj["workspace"]
    _import_by_type("dataset", workspace_folder, workspace, dry_run, name, debug)


@import_group.command(name="project")
@click.argument(
    "workspace_folder", type=click.Path(file_okay=False, dir_okay=True, readable=True)
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Show what would be imported without actually importing.",
)
@click.option(
    "--name",
    type=str,
    help="Filter projects by name using string pattern matching (case-insensitive).",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the import process.",
)
@click.pass_context
def import_project(
    ctx: click.Context,
    workspace_folder: str,
    dry_run: bool,
    name: Optional[str],
    debug: bool,
) -> None:
    """Import projects from workspace/projects directory."""
    workspace = ctx.obj["workspace"]
    _import_by_type(
        "project",
        workspace_folder,
        workspace,
        dry_run,
        name,
        debug,
        True,  # Always recreate experiments when importing projects
    )


@import_group.command(name="experiment")
@click.argument(
    "workspace_folder", type=click.Path(file_okay=False, dir_okay=True, readable=True)
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Show what would be imported without actually importing.",
)
@click.option(
    "--name",
    type=str,
    help="Filter experiments by name using string pattern matching (case-insensitive).",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the import process.",
)
@click.pass_context
def import_experiment(
    ctx: click.Context,
    workspace_folder: str,
    dry_run: bool,
    name: Optional[str],
    debug: bool,
) -> None:
    """Import experiments from workspace/experiments directory."""
    workspace = ctx.obj["workspace"]
    # Always recreate experiments when importing
    _import_by_type(
        "experiment", workspace_folder, workspace, dry_run, name, debug, True
    )


@import_group.command(name="prompt")
@click.argument(
    "workspace_folder", type=click.Path(file_okay=False, dir_okay=True, readable=True)
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Show what would be imported without actually importing.",
)
@click.option(
    "--name",
    type=str,
    help="Filter prompts by name using string pattern matching (case-insensitive).",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the import process.",
)
@click.pass_context
def import_prompt(
    ctx: click.Context,
    workspace_folder: str,
    dry_run: bool,
    name: Optional[str],
    debug: bool,
) -> None:
    """Import prompts from workspace/prompts directory."""
    workspace = ctx.obj["workspace"]
    _import_by_type("prompt", workspace_folder, workspace, dry_run, name, debug)
