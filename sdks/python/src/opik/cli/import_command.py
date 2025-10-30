"""
This module contains the CLI implementation for importing datasets, projects,
experiments, and prompts from exported JSON files. Legacy single-file import helpers
have been removed in favor of directory-based import functions below.
"""

import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

import click
from rich.console import Console

import opik
from opik.api_objects.prompt.prompt import Prompt
from opik.api_objects.prompt.types import PromptType

console = Console()


def _matches_name_pattern(name: str, pattern: Optional[str]) -> bool:
    """Check if a name matches the given pattern using case-insensitive substring matching."""
    if pattern is None:
        return True
    # Simple string matching - check if pattern is contained in name (case-insensitive)
    return pattern.lower() in name.lower()


def _find_experiment_files(data_dir: Path) -> List[Path]:
    """Find all experiment JSON files in the directory."""
    return list(data_dir.glob("experiment_*.json"))


def _load_experiment_data(experiment_file: Path) -> Dict[str, Any]:
    """Load experiment data from JSON file."""
    with open(experiment_file, "r", encoding="utf-8") as f:
        return json.load(f)


def _translate_trace_id(
    original_trace_id: str, trace_id_map: Optional[Dict[str, str]]
) -> Optional[str]:
    """Translate an original trace id from export to the newly created id.

    Returns None if mapping is unavailable for this trace id.
    """
    if trace_id_map is None:
        return None
    return trace_id_map.get(original_trace_id)


def _find_dataset_item_by_content(
    dataset: opik.Dataset, expected_content: Dict[str, Any]
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


def _create_dataset_item(dataset: opik.Dataset, item_data: Dict[str, Any]) -> str:
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
    trace_id_map: Optional[Dict[str, str]] = None,
    dry_run: bool = False,
) -> bool:
    """Recreate a single experiment from exported data.

    Note: This function expects that traces have already been imported into the target workspace.
    When traces are imported, they receive new IDs. The trace IDs from the exported experiment
    data may not match the imported traces unless they were imported in the same session and
    the trace IDs were preserved during import. Items referencing non-existent traces will be skipped.
    """
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
        dataset = client.get_or_create_dataset(
            name=dataset_name,
            description=f"Recreated dataset for experiment {experiment_name}",
        )

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

            # Translate trace id from source (workspace A) to newly created trace id (workspace B)
            new_trace_id = _translate_trace_id(trace_id, trace_id_map)
            if not new_trace_id:
                console.print(
                    f"[yellow]Warning: No mapping for trace {trace_id}. Skipping item.[/yellow]"
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
                # Find or create dataset item (prefer deterministic id to avoid extra reads)
                dataset_item_id = _find_dataset_item_by_content(
                    dataset, dataset_item_data
                )
                if not dataset_item_id:
                    # Prefer using provided id if present, otherwise generate one
                    provided_id = item_data.get(
                        "dataset_item_id"
                    ) or dataset_item_data.get("id")
                    try:
                        from opik.api_objects.dataset import dataset_item as ds_item
                        import opik.id_helpers as id_helpers  # type: ignore
                    except Exception:
                        ds_item = None
                        id_helpers = None

                    if ds_item is not None:
                        chosen_id = provided_id
                        if chosen_id is None and id_helpers is not None:
                            try:
                                chosen_id = id_helpers.generate_id()  # type: ignore
                            except Exception:
                                chosen_id = None

                        # Build DatasetItem with flexible extra fields
                        content = {
                            "input": dataset_item_data.get("input"),
                            "expected_output": dataset_item_data.get("expected_output"),
                            "metadata": dataset_item_data.get("metadata"),
                        }
                        content = {k: v for k, v in content.items() if v is not None}

                        if chosen_id is not None:
                            ds_obj = ds_item.DatasetItem(id=chosen_id, **content)  # type: ignore
                        else:
                            ds_obj = ds_item.DatasetItem(**content)  # type: ignore

                        # Use internal API to avoid redundant downloads
                        dataset.__internal_api__insert_items_as_dataclasses__(
                            items=[ds_obj]
                        )
                        dataset_item_id = ds_obj.id
                    else:
                        # Fallback to public insert + search
                        dataset_item_id = _create_dataset_item(
                            dataset, dataset_item_data
                        )

                # Create experiment item reference
                experiment_items.append(
                    opik.ExperimentItemReferences(
                        dataset_item_id=dataset_item_id,
                        trace_id=new_trace_id,
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
            # Flush client to ensure experiment items are persisted before continuing
            client.flush()
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
    trace_id_map: Optional[Dict[str, str]] = None,
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

            if _recreate_experiment(
                client, experiment_data, project_name, trace_id_map, dry_run
            ):
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
                # Maintain a per-project mapping from original -> new trace ids
                trace_id_map: Dict[str, str] = {}

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
                        original_trace_id = trace_info.get("id")

                        # Create trace with full data
                        trace = client.trace(
                            name=trace_info.get("name", "imported_trace"),
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
                            input=trace_info.get("input", {}),
                            output=trace_info.get("output", {}),
                            metadata=trace_info.get("metadata"),
                            tags=trace_info.get("tags"),
                            feedback_scores=trace_info.get("feedback_scores"),
                            error_info=trace_info.get("error_info"),
                            thread_id=trace_info.get("thread_id"),
                            project_name=project_name,
                        )

                        if original_trace_id:
                            trace_id_map[original_trace_id] = trace.id

                        # Create spans with full data
                        for span_info in spans_info:
                            client.span(
                                name=span_info.get("name", "imported_span"),
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
                                input=span_info.get("input", {}),
                                output=span_info.get("output", {}),
                                metadata=span_info.get("metadata"),
                                tags=span_info.get("tags"),
                                usage=span_info.get("usage"),
                                feedback_scores=span_info.get("feedback_scores"),
                                model=span_info.get("model"),
                                provider=span_info.get("provider"),
                                error_info=span_info.get("error_info"),
                                total_cost=span_info.get("total_cost"),
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
                    # Flush client before recreating experiments
                    client.flush()

                    experiment_files = list(project_dir.glob("experiment_*.json"))
                    if experiment_files:
                        if debug:
                            console.print(
                                f"[blue]Found {len(experiment_files)} experiment files in project {project_name}[/blue]"
                            )

                        # Recreate experiments
                        experiments_recreated = _recreate_experiments(
                            client,
                            project_dir,
                            project_name,
                            dry_run,
                            name_pattern,
                            trace_id_map,
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

                # Import experiment. We cannot translate trace ids here unless traces were
                # imported in the same session; pass no mapping in this mode.
                project_for_logs = (experiment_info.get("metadata") or {}).get(
                    "project_name"
                ) or "default"
                success = _recreate_experiment(
                    client,
                    experiment_data,
                    project_for_logs,
                    None,
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
    """Import data to Opik workspace.

    This command allows you to import previously exported data back into an Opik workspace.
    Supported data types include projects, datasets, experiments, and prompts.

    \b
    General Usage:
        opik import WORKSPACE TYPE FOLDER/ [OPTIONS]

    \b
    Data Types:
        project     Import projects from workspace_folder/projects/
        dataset     Import datasets from workspace_folder/datasets/
        experiment  Import experiments from workspace_folder/experiments/
        prompt      Import prompts from workspace_folder/prompts/

    \b
    Common Options:
        --dry-run   Preview what would be imported without actually importing
        --name      Filter items by name using pattern matching
        --debug     Show detailed information about the import process

    \b
    Examples:
        # Preview all projects that would be imported
        opik import my-workspace project ./exported-data/ --dry-run

        # Import specific projects
        opik import my-workspace project ./exported-data/ --name "my-project"

        # Import all datasets
        opik import my-workspace dataset ./exported-data/
    """
    ctx.ensure_object(dict)
    ctx.obj["workspace"] = workspace


@import_group.command(name="dataset")
@click.argument(
    "workspace_folder", type=click.Path(file_okay=False, dir_okay=True, readable=True)
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Show what would be imported without actually importing. Use this to preview datasets before importing.",
)
@click.option(
    "--name",
    type=str,
    help="Filter datasets by name using case-insensitive substring matching. Use this to import only specific datasets.",
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
    """Import datasets from workspace/datasets directory.

    This command imports all datasets found in the workspace_folder/datasets/ directory.
    By default, ALL datasets in the directory will be imported. Use --name to filter
    specific datasets and --dry-run to preview what will be imported.

    \b
    Examples:
    \b
        # Preview all datasets that would be imported
        opik import my-workspace dataset ./exported-data/ --dry-run
    \b
        # Import all datasets
        opik import my-workspace dataset ./exported-data/
    \b
        # Import only datasets containing "training" in the name
        opik import my-workspace dataset ./exported-data/ --name "training"
    """
    workspace = ctx.obj["workspace"]
    _import_by_type("dataset", workspace_folder, workspace, dry_run, name, debug)


@import_group.command(name="project")
@click.argument(
    "workspace_folder", type=click.Path(file_okay=False, dir_okay=True, readable=True)
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Show what would be imported without actually importing. Use this to preview projects before importing.",
)
@click.option(
    "--name",
    type=str,
    help="Filter projects by name using case-insensitive substring matching. Use this to import only specific projects.",
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
    """Import projects from workspace/projects directory.

    This command imports all projects found in the workspace_folder/projects/ directory.
    By default, ALL projects in the directory will be imported. Use --name to filter
    specific projects and --dry-run to preview what will be imported.

    \b
    Examples:
    \b
        # Preview all projects that would be imported
        opik import my-workspace project ./exported-data/ --dry-run
    \b
        # Import all projects
        opik import my-workspace project ./exported-data/
    \b
        # Import only projects containing "my-project" in the name
        opik import my-workspace project ./exported-data/ --name "my-project"
    \b
        # Import projects with debug output
        opik import my-workspace project ./exported-data/ --debug
    \b
        # Preview specific projects before importing
        opik import my-workspace project ./exported-data/ --name "test" --dry-run
    """
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
