"""Experiment import functionality."""

import json
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Optional

import opik
from opik.api_objects.dataset import dataset_item as ds_item
import opik.id_helpers as id_helpers  # type: ignore
from rich.console import Console

from .utils import (
    find_dataset_item_by_content,
    create_dataset_item,
    handle_trace_reference,
    translate_trace_id,
    matches_name_pattern,
    clean_feedback_scores,
)

console = Console()


def find_experiment_files(data_dir: Path) -> list[Path]:
    """Find all experiment JSON files in the directory."""
    return list(data_dir.glob("experiment_*.json"))


def load_experiment_data(experiment_file: Path) -> Dict[str, Any]:
    """Load experiment data from JSON file."""
    with open(experiment_file, "r", encoding="utf-8") as f:
        return json.load(f)


def recreate_experiment(
    client: opik.Opik,
    experiment_data: Dict[str, Any],
    project_name: str,
    trace_id_map: Optional[Dict[str, str]] = None,
    dry_run: bool = False,
    debug: bool = False,
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
            trace_id = handle_trace_reference(item_data)
            if not trace_id:
                console.print(
                    "[yellow]Warning: No trace ID found, skipping item[/yellow]"
                )
                skipped_items += 1
                continue

            # Translate trace id from source (workspace A) to newly created trace id (workspace B)
            new_trace_id = translate_trace_id(trace_id, trace_id_map)
            if not new_trace_id:
                console.print(
                    f"[yellow]Warning: No mapping for trace {trace_id}. Skipping item.[/yellow]"
                )
                skipped_items += 1
                continue

            # Handle dataset item
            dataset_item_data = item_data.get("dataset_item_data")

            # If dataset_item_data is missing, try to use input as fallback (for older exports)
            if not dataset_item_data:
                # Check if input field exists and use it as dataset item data
                input_data = item_data.get("input")
                if input_data and isinstance(input_data, dict):
                    dataset_item_data = input_data
                    if debug:
                        console.print(
                            "[blue]Using 'input' field as dataset_item_data (export may be from older version)[/blue]"
                        )
                else:
                    console.print(
                        "[yellow]Warning: No dataset item data found, skipping item. "
                        "This may be due to an older export format. Please re-export the experiment.[/yellow]"
                    )
                    skipped_items += 1
                    continue

            # Ensure dataset_item_data is a dict
            if not isinstance(dataset_item_data, dict):
                console.print(
                    "[yellow]Warning: dataset_item_data is not a dictionary, skipping item[/yellow]"
                )
                skipped_items += 1
                continue

            try:
                # Find or create dataset item (prefer deterministic id to avoid extra reads)
                dataset_item_id = find_dataset_item_by_content(
                    dataset, dataset_item_data
                )
                if not dataset_item_id:
                    # Prefer using provided id if present, otherwise generate one
                    provided_id = item_data.get(
                        "dataset_item_id"
                    ) or dataset_item_data.get("id")

                    if ds_item is not None:
                        chosen_id = provided_id
                        if chosen_id is None and id_helpers is not None:
                            try:
                                chosen_id = id_helpers.generate_id()  # type: ignore
                            except Exception:
                                chosen_id = None

                        # Build DatasetItem with all fields from dataset_item_data
                        # Exclude 'id' as it's handled separately
                        content = {
                            k: v
                            for k, v in dataset_item_data.items()
                            if k != "id" and v is not None
                        }

                        # Ensure content is not empty (backend requires non-empty data)
                        if not content:
                            console.print(
                                "[yellow]Warning: Dataset item data is empty, skipping item[/yellow]"
                            )
                            skipped_items += 1
                            continue

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
                        dataset_item_id = create_dataset_item(
                            dataset, dataset_item_data
                        )

                # Create experiment item reference
                if dataset_item_id is not None:
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


def recreate_experiments(
    client: opik.Opik,
    project_dir: Path,
    project_name: str,
    dry_run: bool = False,
    name_pattern: Optional[str] = None,
    trace_id_map: Optional[Dict[str, str]] = None,
    debug: bool = False,
) -> int:
    """Recreate experiments from JSON files."""
    experiment_files = find_experiment_files(project_dir)

    if not experiment_files:
        console.print(f"[yellow]No experiment files found in {project_dir}[/yellow]")
        return 0

    console.print(f"[green]Found {len(experiment_files)} experiment files[/green]")

    # Filter experiments by name pattern if specified
    if name_pattern:
        filtered_files = []
        for exp_file in experiment_files:
            try:
                exp_data = load_experiment_data(exp_file)
                exp_name = exp_data.get("experiment", {}).get("name", "")
                if exp_name and matches_name_pattern(exp_name, name_pattern):
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
            experiment_data = load_experiment_data(experiment_file)

            if recreate_experiment(
                client, experiment_data, project_name, trace_id_map, dry_run, debug
            ):
                successful += 1
            else:
                failed += 1

        except Exception as e:
            console.print(f"[red]Error processing {experiment_file.name}: {e}[/red]")
            failed += 1
            continue

    return successful


def _import_traces_from_projects_directory(
    client: opik.Opik,
    workspace_root: Path,
    dry_run: bool,
    debug: bool,
) -> Dict[str, str]:
    """Import traces from projects directory and return trace_id_map.

    Returns a mapping from original trace ID to new trace ID.
    """
    trace_id_map: Dict[str, str] = {}
    projects_dir = workspace_root / "projects"

    if not projects_dir.exists():
        if debug:
            console.print(
                f"[blue]No projects directory found at {projects_dir}, skipping trace import[/blue]"
            )
        return trace_id_map

    project_dirs = [d for d in projects_dir.iterdir() if d.is_dir()]

    if not project_dirs:
        if debug:
            console.print(
                "[blue]No project directories found, skipping trace import[/blue]"
            )
        return trace_id_map

    if debug:
        console.print(
            f"[blue]Importing traces from {len(project_dirs)} project(s) to build trace ID mapping...[/blue]"
        )

    for project_dir in project_dirs:
        project_name = project_dir.name
        trace_files = list(project_dir.glob("trace_*.json"))

        if not trace_files:
            continue

        if debug:
            console.print(
                f"[blue]Importing {len(trace_files)} trace(s) from project '{project_name}'...[/blue]"
            )

        for trace_file in trace_files:
            try:
                with open(trace_file, "r", encoding="utf-8") as f:
                    trace_data = json.load(f)

                trace_info = trace_data.get("trace", {})
                spans_info = trace_data.get("spans", [])
                original_trace_id = trace_info.get("id")

                if not original_trace_id:
                    continue

                if dry_run:
                    if debug:
                        console.print(
                            f"[blue]Would import trace {original_trace_id} from project '{project_name}'[/blue]"
                        )
                    continue

                # Create trace with full data
                # Clean feedback scores to remove read-only fields
                feedback_scores = clean_feedback_scores(
                    trace_info.get("feedback_scores")
                )

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
                    feedback_scores=feedback_scores,
                    error_info=trace_info.get("error_info"),
                    thread_id=trace_info.get("thread_id"),
                    project_name=project_name,
                )

                # Map original trace ID to new trace ID
                trace_id_map[original_trace_id] = trace.id

                # Create spans with full data
                for span_info in spans_info:
                    # Clean feedback scores to remove read-only fields
                    span_feedback_scores = clean_feedback_scores(
                        span_info.get("feedback_scores")
                    )

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
                        feedback_scores=span_feedback_scores,
                        model=span_info.get("model"),
                        provider=span_info.get("provider"),
                        error_info=span_info.get("error_info"),
                        total_cost=span_info.get("total_cost"),
                        trace_id=trace.id,
                        project_name=project_name,
                    )

            except Exception as e:
                console.print(
                    f"[yellow]Warning: Failed to import trace from {trace_file}: {e}[/yellow]"
                )
                continue

    if not dry_run and trace_id_map:
        # Flush client to ensure traces are persisted before recreating experiments
        client.flush()
        if debug:
            console.print(
                f"[green]Imported {len(trace_id_map)} trace(s) and built trace ID mapping[/green]"
            )

    return trace_id_map


def import_experiments_from_directory(
    client: opik.Opik,
    source_dir: Path,
    dry_run: bool,
    name_pattern: Optional[str],
    debug: bool,
    recreate_experiments_flag: bool,
) -> int:
    """Import experiments from a directory.

    This function will first import traces from the projects directory (if it exists)
    to build a trace_id_map, then use that map when recreating experiments.
    """
    try:
        experiment_files = list(source_dir.glob("experiment_*.json"))

        if not experiment_files:
            console.print("[yellow]No experiment files found in the directory[/yellow]")
            return 0

        # Import traces first to build trace_id_map
        # source_dir is typically workspace/experiments, so parent is workspace root
        workspace_root = source_dir.parent
        trace_id_map = _import_traces_from_projects_directory(
            client, workspace_root, dry_run, debug
        )

        if not trace_id_map and not dry_run:
            console.print(
                "[yellow]Warning: No traces were imported. Experiment items may be skipped if they reference traces.[/yellow]"
            )

        imported_count = 0
        for experiment_file in experiment_files:
            try:
                with open(experiment_file, "r", encoding="utf-8") as f:
                    experiment_data = json.load(f)

                experiment_info = experiment_data.get("experiment", {})
                experiment_name = experiment_info.get("name", "")

                # Filter by name pattern if specified
                if name_pattern and not matches_name_pattern(
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

                # Get project name from experiment metadata or use default
                project_for_logs = (experiment_info.get("metadata") or {}).get(
                    "project_name"
                ) or "default"

                # Use trace_id_map to translate trace IDs
                success = recreate_experiment(
                    client,
                    experiment_data,
                    project_for_logs,
                    trace_id_map if trace_id_map else None,
                    dry_run,
                    debug,
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
