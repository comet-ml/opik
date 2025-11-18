"""Experiment import functionality."""

import json
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
            dataset_item_data = item_data.get("dataset_item_data", {})
            if not dataset_item_data:
                console.print(
                    "[yellow]Warning: No dataset item data, skipping item[/yellow]"
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


def import_experiments_from_directory(
    client: opik.Opik,
    source_dir: Path,
    dry_run: bool,
    name_pattern: Optional[str],
    debug: bool,
    recreate_experiments_flag: bool,
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

                # Import experiment. We cannot translate trace ids here unless traces were
                # imported in the same session; pass no mapping in this mode.
                project_for_logs = (experiment_info.get("metadata") or {}).get(
                    "project_name"
                ) or "default"
                success = recreate_experiment(
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
