"""Experiment import functionality.

Note: Experiment import copies traces but not their full span trees (LLM task and
metrics calculation spans). This is sufficient for experiment representation but
not a "full & honest" migration. Spans are imported as part of trace import,
not experiment recreation.
"""

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

import opik
from opik.api_objects.dataset import dataset_item as dataset_item_module  # noqa: F401
import opik.id_helpers as id_helpers_module  # type: ignore
from opik.rest_api.types.experiment_item import ExperimentItem

# Note: dataset_item_module is imported for test compatibility.
# Tests patch and import this module, so it must be available even though
# it's not directly used in this module's code.
from rich.console import Console

from .utils import (
    handle_trace_reference,
    translate_trace_id,
    matches_name_pattern,
    clean_feedback_scores,
    debug_print,
)
from .prompt import import_prompts_from_directory
from .dataset import import_datasets_from_directory

console = Console()


@dataclass
class ExperimentData:
    """Structure for imported experiment data.

    Matches the export format from create_experiment_data_structure.
    """

    experiment: Dict[str, Any]
    items: List[Dict[str, Any]]
    downloaded_at: Optional[str] = None

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "ExperimentData":
        """Create ExperimentData from a dictionary (e.g., loaded from JSON)."""
        return cls(
            experiment=data.get("experiment", {}),
            items=data.get("items", []),
            downloaded_at=data.get("downloaded_at"),
        )


def find_experiment_files(data_dir: Path) -> list[Path]:
    """Find all experiment JSON files in the directory."""
    return list(data_dir.glob("experiment_*.json"))


def load_experiment_data(experiment_file: Path) -> ExperimentData:
    """Load experiment data from JSON file."""
    with open(experiment_file, "r", encoding="utf-8") as f:
        data = json.load(f)
        return ExperimentData.from_dict(data)


def _build_dataset_item_id_map(
    client: opik.Opik,
    experiment_files: List[Path],
    datasets_dir: Path,
    dry_run: bool,
    debug: bool,
) -> tuple[Dict[str, str], Dict[str, int]]:
    """Build a mapping from original dataset_item_id to new dataset_item_id.

    This function:
    1. Collects all dataset_item_id and dataset_item_data from experiment files
    2. Imports datasets from the datasets directory
    3. Matches imported dataset items by content to build the mapping

    Args:
        client: Opik client instance
        experiment_files: List of experiment JSON files
        datasets_dir: Directory containing dataset exports
        dry_run: If True, only simulate without making changes
        debug: If True, print debug messages

    Returns:
        Tuple of (dataset_item_id_map, dataset_stats) where:
        - dataset_item_id_map: Dictionary mapping original dataset_item_id to new dataset_item_id
        - dataset_stats: Dictionary with 'datasets', 'datasets_skipped', 'datasets_errors' keys
    """
    dataset_item_id_map: Dict[str, str] = {}
    dataset_stats: Dict[str, int] = {
        "datasets": 0,
        "datasets_skipped": 0,
        "datasets_errors": 0,
    }

    if dry_run:
        return dataset_item_id_map, dataset_stats

    # Step 1: Collect all dataset_item_id and dataset_item_data from experiment files
    # Map: content_hash -> list of (original_dataset_item_id, dataset_item_data)
    # Multiple original IDs can have the same content (they should all map to the same new item)
    content_to_original_ids: Dict[str, List[tuple[str, Dict[str, Any]]]] = {}

    for experiment_file in experiment_files:
        try:
            experiment_data = load_experiment_data(experiment_file)
            items_data = experiment_data.items

            for item_data in items_data:
                original_dataset_item_id = item_data.get("dataset_item_id")
                dataset_item_data = item_data.get("dataset_item_data")

                # Fallback to input for older exports
                if not dataset_item_data:
                    dataset_item_data = item_data.get("input")

                if not original_dataset_item_id or not dataset_item_data:
                    continue

                # Remove 'id' field from dataset_item_data for consistent hashing
                # (imported items don't have 'id' field, so we need to match without it)
                if isinstance(dataset_item_data, dict):
                    dataset_item_data_for_hash = {
                        k: v for k, v in dataset_item_data.items() if k != "id"
                    }
                else:
                    dataset_item_data_for_hash = dataset_item_data

                # Create a hash of the content for matching (without 'id' field)
                # Sort keys to ensure consistent hashing
                content_str = json.dumps(dataset_item_data_for_hash, sort_keys=True)
                content_hash = hashlib.sha256(content_str.encode()).hexdigest()

                # Store the mapping (content_hash -> list of (original_id, data))
                # Store the original data without 'id' for matching
                if content_hash not in content_to_original_ids:
                    content_to_original_ids[content_hash] = []
                content_to_original_ids[content_hash].append(
                    (original_dataset_item_id, dataset_item_data_for_hash)
                )

        except Exception as e:
            debug_print(
                f"Warning: Failed to process experiment file {experiment_file} for dataset mapping: {e}",
                debug,
            )
            continue

    if not content_to_original_ids:
        debug_print("No dataset items found in experiment files", debug)
        return dataset_item_id_map, dataset_stats

    # Count total unique original IDs
    total_original_ids = sum(len(ids) for ids in content_to_original_ids.values())
    console.print(
        f"[blue]Found {total_original_ids} dataset item reference(s) ({len(content_to_original_ids)} unique content(s)) in experiment files[/blue]"
    )
    debug_print(
        f"Found {total_original_ids} dataset item reference(s) ({len(content_to_original_ids)} unique content(s)) in experiment files",
        debug,
    )

    # Step 2: Import datasets
    datasets_dir = (
        datasets_dir if datasets_dir.exists() else datasets_dir.parent / "datasets"
    )
    if not datasets_dir.exists():
        console.print(
            f"[yellow]Warning: No datasets directory found at {datasets_dir}, skipping dataset import[/yellow]"
        )
        debug_print(
            f"No datasets directory found at {datasets_dir}, skipping dataset import",
            debug,
        )
        return dataset_item_id_map, dataset_stats

    console.print(
        f"[blue]Importing datasets from {datasets_dir} to build dataset item ID mapping...[/blue]"
    )

    # Import datasets (this will create dataset items with new IDs)
    dataset_import_stats = import_datasets_from_directory(
        client, datasets_dir, dry_run, None, debug
    )

    # Update dataset_stats with import results
    dataset_stats["datasets"] = dataset_import_stats.get("datasets", 0)
    dataset_stats["datasets_skipped"] = dataset_import_stats.get("datasets_skipped", 0)
    dataset_stats["datasets_errors"] = dataset_import_stats.get("datasets_errors", 0)

    if dataset_import_stats.get("datasets", 0) == 0:
        console.print(
            f"[yellow]Warning: No datasets were imported from {datasets_dir}[/yellow]"
        )
        dataset_files = list(datasets_dir.glob("dataset_*.json"))
        if dataset_files:
            console.print(
                f"[yellow]Found {len(dataset_files)} dataset file(s) but none were imported[/yellow]"
            )
        else:
            console.print(f"[yellow]No dataset files found in {datasets_dir}[/yellow]")

    # Flush to ensure datasets are persisted
    if not dry_run:
        client.flush()
        console.print(
            f"[green]Imported {dataset_import_stats.get('datasets', 0)} dataset(s)[/green]"
        )

    # Step 3: Get all imported dataset items and match by content
    dataset_files = list(datasets_dir.glob("dataset_*.json"))

    if not dataset_files:
        console.print(
            f"[yellow]Warning: No dataset files found in {datasets_dir}[/yellow]"
        )
        return dataset_item_id_map, dataset_stats

    console.print(
        f"[blue]Processing {len(dataset_files)} dataset file(s) to build item ID mapping...[/blue]"
    )

    for dataset_file in dataset_files:
        try:
            with open(dataset_file, "r", encoding="utf-8") as f:
                dataset_data = json.load(f)

            dataset_name = dataset_data.get("name") or (
                dataset_data.get("dataset", {}).get("name")
                if dataset_data.get("dataset")
                else None
            )

            if not dataset_name:
                continue

            # Get the imported dataset
            try:
                dataset = client.get_dataset(dataset_name)
            except Exception:
                debug_print(
                    f"Warning: Could not get dataset '{dataset_name}' after import",
                    debug,
                )
                continue

            # Get all items from the imported dataset (with their new IDs)
            try:
                imported_items = dataset.get_items()
                console.print(
                    f"[blue]Dataset '{dataset_name}' has {len(imported_items)} item(s)[/blue]"
                )
            except Exception as e:
                console.print(
                    f"[yellow]Warning: Could not get items from dataset '{dataset_name}': {e}[/yellow]"
                )
                continue

            # Match imported items to original items by content
            matched_count = 0
            for imported_item in imported_items:
                imported_item_id = imported_item.get("id")
                if not imported_item_id:
                    continue

                # Remove 'id' from content for comparison
                imported_content = {k: v for k, v in imported_item.items() if k != "id"}

                # Create hash of imported content
                imported_content_str = json.dumps(imported_content, sort_keys=True)
                imported_content_hash = hashlib.sha256(
                    imported_content_str.encode()
                ).hexdigest()

                # Match to original - map all original IDs with this content to the same new item
                if imported_content_hash in content_to_original_ids:
                    original_ids_list = content_to_original_ids[imported_content_hash]
                    for original_id, _ in original_ids_list:
                        dataset_item_id_map[original_id] = imported_item_id
                        matched_count += 1
                        debug_print(
                            f"Mapped dataset item {original_id} -> {imported_item_id}",
                            debug,
                        )
                    # Remove from dict to avoid rematching (though duplicates are fine)
                    # We keep it in case the same content appears in multiple datasets

            if matched_count > 0:
                console.print(
                    f"[green]Matched {matched_count} dataset item(s) from dataset '{dataset_name}'[/green]"
                )
            elif imported_items:
                # Show why items weren't matched
                unmatched_hashes = set(content_to_original_ids.keys())
                imported_hashes = set()
                for item in imported_items:
                    item_content = {k: v for k, v in item.items() if k != "id"}
                    content_str = json.dumps(item_content, sort_keys=True)
                    imported_hashes.add(
                        hashlib.sha256(content_str.encode()).hexdigest()
                    )

                if unmatched_hashes and imported_hashes:
                    console.print(
                        f"[yellow]Warning: Could not match any items from dataset '{dataset_name}'. Content hashes don't match.[/yellow]"
                    )
                    if debug:
                        # Show sample content from both sides
                        sample_original = list(content_to_original_ids.values())[0][0][
                            1
                        ]
                        sample_imported = imported_items[0] if imported_items else {}
                        console.print(
                            f"[yellow]Sample original content: {json.dumps(sample_original, sort_keys=True)[:200]}...[/yellow]"
                        )
                        imported_sample = {
                            k: v for k, v in sample_imported.items() if k != "id"
                        }
                        console.print(
                            f"[yellow]Sample imported content: {json.dumps(imported_sample, sort_keys=True)[:200]}...[/yellow]"
                        )

        except Exception as e:
            console.print(
                f"[yellow]Warning: Failed to process dataset file {dataset_file.name} for mapping: {e}[/yellow]"
            )
            if debug:
                import traceback

                console.print(f"[yellow]Traceback: {traceback.format_exc()}[/yellow]")
            continue

    if dataset_item_id_map:
        console.print(
            f"[green]Built dataset item ID mapping with {len(dataset_item_id_map)} item(s)[/green]"
        )
        debug_print(
            f"Dataset item ID mapping has {len(dataset_item_id_map)} entries",
            debug,
        )
    else:
        console.print(
            "[yellow]Warning: Dataset item ID mapping is empty. This may cause experiment items to be skipped.[/yellow]"
        )
        if content_to_original_ids:
            console.print(
                f"[yellow]Found {total_original_ids} dataset item reference(s) in experiment files but couldn't match them to imported items.[/yellow]"
            )
            console.print(
                "[yellow]This usually means the dataset items weren't imported correctly or the content structure doesn't match.[/yellow]"
            )

    return dataset_item_id_map, dataset_stats


def recreate_experiment(
    client: opik.Opik,
    experiment_data: ExperimentData,
    project_name: str,
    trace_id_map: Dict[str, str],
    dataset_item_id_map: Optional[Dict[str, str]] = None,
    dry_run: bool = False,
    debug: bool = False,
) -> bool:
    """Recreate a single experiment from exported data.

    Args:
        client: Opik client instance
        experiment_data: Experiment data structure from export
        project_name: Name of the project to create the experiment in
        trace_id_map: Mapping from original trace IDs to new trace IDs (required, can be empty dict)
        dataset_item_id_map: Mapping from original dataset_item_id to new dataset_item_id (optional)
        dry_run: If True, only simulate the import without making changes
        debug: If True, print debug messages

    Note: This function expects that traces and datasets have already been imported into the target workspace.
    When traces are imported, they receive new IDs. The trace_id_map maps original trace IDs
    to the newly created trace IDs. Items referencing traces not in trace_id_map will be skipped.
    When datasets are imported, their items receive new IDs. The dataset_item_id_map maps original
    dataset_item_id to the newly created dataset_item_id. Items referencing dataset items not in
    dataset_item_id_map will be skipped. An empty trace_id_map or dataset_item_id_map is valid and
    will result in items being skipped.
    """
    experiment_info = experiment_data.experiment
    items_data = experiment_data.items

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
        # Ensure dataset is in the same workspace as the client
        _ = client.get_or_create_dataset(
            name=dataset_name,
            description=f"Recreated dataset for experiment {experiment_name}",
        )

        debug_print(
            f"Using dataset '{dataset_name}' for experiment '{experiment_name}'",
            debug,
        )

        # Ensure project_name is in metadata for future imports
        experiment_metadata = experiment_info.get("metadata") or {}
        if project_name and "project_name" not in experiment_metadata:
            experiment_metadata = experiment_metadata.copy()
            experiment_metadata["project_name"] = project_name
            debug_print(
                f"Adding project_name '{project_name}' to experiment metadata",
                debug,
            )

        # Create the experiment
        experiment = client.create_experiment(
            dataset_name=dataset_name,
            name=experiment_name,
            experiment_config=experiment_metadata,
            type=experiment_info.get("type", "regular"),
        )

        debug_print(
            f"Created experiment '{experiment_name}' with ID: {experiment.id}",
            debug,
        )

        # Process experiment items using dataset_item_id_map
        rest_experiment_items = []
        successful_items = 0
        skipped_items = 0
        skipped_no_trace_id = 0
        skipped_no_trace_mapping = 0
        skipped_no_dataset_item_id = 0
        skipped_no_dataset_item_mapping = 0

        for item_data in items_data:
            # Handle trace reference (from deduplicated exports)
            trace_id = handle_trace_reference(item_data)
            if not trace_id:
                debug_print(
                    f"Warning: No trace ID found in item {item_data.get('id', 'unknown')}, skipping item",
                    debug,
                )
                skipped_items += 1
                skipped_no_trace_id += 1
                continue

            # Translate trace id from source (workspace A) to newly created trace id (workspace B)
            new_trace_id = translate_trace_id(trace_id, trace_id_map)
            if not new_trace_id:
                debug_print(
                    f"Warning: No mapping for trace {trace_id}. "
                    f"Trace ID map has {len(trace_id_map)} entries. Skipping item.",
                    debug,
                )
                if trace_id_map:
                    # Show first few trace IDs in map for debugging
                    sample_ids = list(trace_id_map.keys())[:3]
                    debug_print(f"Sample trace IDs in map: {sample_ids}", debug)
                skipped_items += 1
                skipped_no_trace_mapping += 1
                continue

            debug_print(f"Mapped trace {trace_id} -> {new_trace_id}", debug)

            # Translate dataset_item_id using dataset_item_id_map
            original_dataset_item_id = item_data.get("dataset_item_id")
            if not original_dataset_item_id:
                debug_print(
                    f"Warning: No dataset_item_id found in item {item_data.get('id', 'unknown')}, skipping item",
                    debug,
                )
                skipped_items += 1
                skipped_no_dataset_item_id += 1
                continue

            # Use dataset_item_id_map to get the new dataset_item_id
            new_dataset_item_id = None
            if dataset_item_id_map:
                new_dataset_item_id = dataset_item_id_map.get(original_dataset_item_id)

            if not new_dataset_item_id:
                debug_print(
                    f"Warning: No mapping for dataset_item_id {original_dataset_item_id}. "
                    f"Dataset item ID map has {len(dataset_item_id_map) if dataset_item_id_map else 0} entries. Skipping item.",
                    debug,
                )
                if dataset_item_id_map:
                    # Show first few dataset item IDs in map for debugging
                    sample_ids = list(dataset_item_id_map.keys())[:3]
                    debug_print(f"Sample dataset item IDs in map: {sample_ids}", debug)
                skipped_items += 1
                skipped_no_dataset_item_mapping += 1
                continue

            debug_print(
                f"Mapped dataset_item_id {original_dataset_item_id} -> {new_dataset_item_id}",
                debug,
            )

            # Create experiment item with mapped IDs
            try:
                experiment_item_id = id_helpers_module.generate_id()
                rest_experiment_items.append(
                    ExperimentItem(
                        id=experiment_item_id,
                        experiment_id=experiment.id,
                        dataset_item_id=new_dataset_item_id,
                        trace_id=new_trace_id,
                    )
                )
                successful_items += 1
                debug_print(
                    f"Prepared experiment item: dataset_item_id={new_dataset_item_id}, trace_id={new_trace_id}",
                    debug,
                )
            except Exception as e:
                console.print(
                    f"[yellow]Warning: Failed to create experiment item: {e}[/yellow]"
                )
                skipped_items += 1
                continue

        # Insert experiment items using REST API directly (more reliable than streamer)
        if rest_experiment_items:
            debug_print(
                f"Inserting {len(rest_experiment_items)} experiment items via REST API...",
                debug,
            )
            try:
                # Use REST API directly instead of streamer
                client._rest_client.experiments.create_experiment_items(
                    experiment_items=rest_experiment_items
                )
                console.print(
                    f"[green]Created experiment '{experiment_name}' with {successful_items} items[/green]"
                )
                if skipped_items > 0:
                    console.print(
                        f"[yellow]Skipped {skipped_items} items due to missing data:[/yellow]"
                    )
                    if skipped_no_trace_id > 0:
                        console.print(
                            f"  - {skipped_no_trace_id} items missing trace_id"
                        )
                    if skipped_no_trace_mapping > 0:
                        console.print(
                            f"  - {skipped_no_trace_mapping} items with trace_id not found in trace_id_map (map has {len(trace_id_map)} entries)"
                        )
                    if skipped_no_dataset_item_id > 0:
                        console.print(
                            f"  - {skipped_no_dataset_item_id} items missing dataset_item_id"
                        )
                    if skipped_no_dataset_item_mapping > 0:
                        console.print(
                            f"  - {skipped_no_dataset_item_mapping} items with dataset_item_id not found in dataset_item_id_map (map has {len(dataset_item_id_map) if dataset_item_id_map else 0} entries)"
                        )
            except Exception as e:
                console.print(f"[red]Error inserting experiment items: {e}[/red]")
                if debug:
                    import traceback

                    console.print(f"[red]Traceback: {traceback.format_exc()}[/red]")
                raise
        else:
            console.print(
                f"[yellow]No valid items found for experiment '{experiment_name}'[/yellow]"
            )
            console.print(
                f"[yellow]Total items in experiment: {len(items_data)}[/yellow]"
            )
            if trace_id_map:
                console.print(
                    f"[yellow]Trace ID map has {len(trace_id_map)} entries[/yellow]"
                )
                # Show sample trace IDs from experiment items vs map
                experiment_trace_ids = [
                    handle_trace_reference(item) for item in items_data
                ]
                experiment_trace_ids = [tid for tid in experiment_trace_ids if tid]
                if experiment_trace_ids:
                    matched = sum(
                        1 for tid in experiment_trace_ids if tid in trace_id_map
                    )
                    console.print(
                        f"[yellow]Experiment references {len(experiment_trace_ids)} trace(s), {matched} found in trace_id_map[/yellow]"
                    )
                    if matched < len(experiment_trace_ids):
                        missing = [
                            tid
                            for tid in experiment_trace_ids
                            if tid not in trace_id_map
                        ]
                        console.print(
                            f"[yellow]Missing trace IDs (first 5): {missing[:5]}[/yellow]"
                        )
            else:
                console.print(
                    "[yellow]No trace ID map available - traces may not have been imported[/yellow]"
                )
            if dataset_item_id_map:
                console.print(
                    f"[yellow]Dataset item ID map has {len(dataset_item_id_map)} entries[/yellow]"
                )
            else:
                console.print(
                    "[yellow]No dataset item ID map available - datasets may not have been imported[/yellow]"
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
    dataset_item_id_map: Optional[Dict[str, str]] = None,
    debug: bool = False,
) -> int:
    """Recreate experiments from JSON files.

    Args:
        trace_id_map: Mapping from original trace IDs to new trace IDs.
                     If None, will be treated as empty dict (all items will be skipped).
        dataset_item_id_map: Mapping from original dataset_item_id to new dataset_item_id.
                            If None, will be treated as empty dict (all items will be skipped).
    """
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
                exp_name = exp_data.experiment.get("name", "")
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
                client,
                experiment_data,
                project_name,
                trace_id_map or {},
                dataset_item_id_map or {},
                dry_run,
                debug,
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
) -> tuple[Dict[str, str], Dict[str, int]]:
    """Import traces from projects directory and return trace_id_map and statistics.

    Returns:
        Tuple of (trace_id_map, stats_dict) where:
        - trace_id_map: mapping from original trace ID to new trace ID
        - stats_dict: dictionary with 'traces' and 'traces_errors' keys
    """
    trace_id_map: Dict[str, str] = {}
    traces_imported = 0
    traces_errors = 0
    projects_dir = workspace_root / "projects"

    if not projects_dir.exists():
        debug_print(
            f"No projects directory found at {projects_dir}, skipping trace import",
            debug,
        )
        return trace_id_map, {"traces": 0, "traces_errors": 0}

    project_dirs = [d for d in projects_dir.iterdir() if d.is_dir()]

    if not project_dirs:
        debug_print("No project directories found, skipping trace import", debug)
        return trace_id_map, {"traces": 0, "traces_errors": 0}

    debug_print(
        f"Importing traces from {len(project_dirs)} project(s) to build trace ID mapping...",
        debug,
    )

    for project_dir in project_dirs:
        project_name = project_dir.name
        trace_files = list(project_dir.glob("trace_*.json"))

        if not trace_files:
            debug_print(f"No trace files found in project '{project_name}'", debug)
            continue

        debug_print(
            f"Importing {len(trace_files)} trace(s) from project '{project_name}'...",
            debug,
        )

        for trace_file in trace_files:
            try:
                with open(trace_file, "r", encoding="utf-8") as f:
                    trace_data = json.load(f)

                trace_info = trace_data.get("trace", {})
                spans_info = trace_data.get("spans", [])
                original_trace_id = trace_info.get("id")

                if not original_trace_id:
                    debug_print(
                        f"Warning: Trace file {trace_file.name} has no trace ID, skipping",
                        debug,
                    )
                    traces_errors += 1
                    continue

                if dry_run:
                    debug_print(
                        f"Would import trace {original_trace_id} from project '{project_name}'",
                        debug,
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
                traces_imported += 1
                debug_print(
                    f"Mapped trace {original_trace_id} -> {trace.id} (project: {project_name})",
                    debug,
                )

                # Create spans with full data, preserving parent-child relationships
                # Build span_id_map to translate parent_span_id references
                span_id_map: Dict[str, str] = {}  # Maps original span ID to new span ID

                # First pass: create all spans and build span_id_map
                # We need to create spans in order so parent spans exist before children
                # Sort spans to process root spans (no parent) first, then children
                root_spans = [s for s in spans_info if not s.get("parent_span_id")]
                child_spans = [s for s in spans_info if s.get("parent_span_id")]
                sorted_spans = root_spans + child_spans

                for span_info in sorted_spans:
                    # Clean feedback scores to remove read-only fields
                    span_feedback_scores = clean_feedback_scores(
                        span_info.get("feedback_scores")
                    )

                    original_span_id = span_info.get("id")
                    original_parent_span_id = span_info.get("parent_span_id")

                    # Translate parent_span_id if it exists
                    new_parent_span_id = None
                    if (
                        original_parent_span_id
                        and original_parent_span_id in span_id_map
                    ):
                        new_parent_span_id = span_id_map[original_parent_span_id]

                    # Create span with parent_span_id if available
                    span = client.span(
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
                        parent_span_id=new_parent_span_id,
                        project_name=project_name,
                    )

                    # Map original span ID to new span ID for parent relationship mapping
                    if original_span_id and span.id:
                        span_id_map[original_span_id] = span.id

            except Exception as e:
                console.print(
                    f"[yellow]Warning: Failed to import trace from {trace_file}: {e}[/yellow]"
                )
                traces_errors += 1
                continue

    if not dry_run and trace_id_map:
        # Flush client to ensure traces are persisted before recreating experiments
        client.flush()
        console.print(
            f"[green]Imported {len(trace_id_map)} trace(s) and built trace ID mapping[/green]"
        )
        debug_print(
            f"Trace ID mapping has {len(trace_id_map)} entries",
            debug,
        )
    elif not dry_run:
        console.print(
            "[yellow]Warning: No traces were imported. Trace ID map is empty.[/yellow]"
        )
        if traces_imported == 0 and traces_errors == 0:
            console.print(
                f"[yellow]No trace files were found in {projects_dir}[/yellow]"
            )

    return trace_id_map, {"traces": traces_imported, "traces_errors": traces_errors}


def import_experiments_from_directory(
    client: opik.Opik,
    source_dir: Path,
    dry_run: bool,
    name_pattern: Optional[str],
    debug: bool,
) -> Dict[str, int]:
    """Import experiments from a directory.

    This function will first import prompts and traces from their respective directories
    (if they exist) to build a trace_id_map, then use that map when recreating experiments.

    Returns:
        Dictionary with keys: 'experiments', 'experiments_skipped', 'experiments_errors',
        'prompts', 'prompts_skipped', 'prompts_errors', 'traces', 'traces_errors'
    """
    try:
        experiment_files = list(source_dir.glob("experiment_*.json"))

        if not experiment_files:
            console.print("[yellow]No experiment files found in the directory[/yellow]")
            return {
                "experiments": 0,
                "experiments_skipped": 0,
                "experiments_errors": 0,
                "datasets": 0,
                "datasets_skipped": 0,
                "datasets_errors": 0,
                "prompts": 0,
                "prompts_skipped": 0,
                "prompts_errors": 0,
                "traces": 0,
                "traces_errors": 0,
            }

        # source_dir is typically workspace/experiments, so parent is workspace root
        workspace_root = source_dir.parent

        # Import prompts first (they may be referenced by experiments)
        prompts_stats = {"prompts": 0, "prompts_skipped": 0, "prompts_errors": 0}
        prompts_dir = workspace_root / "prompts"
        if prompts_dir.exists():
            debug_print("Importing prompts from prompts directory...", debug)
            prompts_stats = import_prompts_from_directory(
                client, prompts_dir, dry_run, name_pattern, debug
            )
            if prompts_stats.get("prompts", 0) > 0 and not dry_run:
                # Flush client to ensure prompts are persisted
                client.flush()
                debug_print(
                    f"Imported {prompts_stats.get('prompts', 0)} prompt(s)",
                    debug,
                )
        else:
            debug_print("No prompts directory found, skipping prompt import", debug)

        # Import datasets first to build dataset_item_id_map
        datasets_dir = workspace_root / "datasets"
        dataset_item_id_map: Dict[str, str] = {}
        datasets_stats: Dict[str, int] = {
            "datasets": 0,
            "datasets_skipped": 0,
            "datasets_errors": 0,
        }
        if datasets_dir.exists():
            debug_print(
                "Importing datasets and building dataset item ID mapping...",
                debug,
            )
            dataset_item_id_map, datasets_stats = _build_dataset_item_id_map(
                client, experiment_files, datasets_dir, dry_run, debug
            )
        else:
            debug_print(
                f"No datasets directory found at {datasets_dir}, skipping dataset import",
                debug,
            )

        # Import traces first to build trace_id_map
        trace_id_map, traces_stats = _import_traces_from_projects_directory(
            client, workspace_root, dry_run, debug
        )

        if not trace_id_map and not dry_run:
            console.print(
                "[yellow]Warning: No traces were imported. Experiment items may be skipped if they reference traces.[/yellow]"
            )
            # Try to diagnose why traces weren't imported
            projects_dir = workspace_root / "projects"
            if projects_dir.exists():
                project_dirs = [d for d in projects_dir.iterdir() if d.is_dir()]
                debug_print(
                    f"Found {len(project_dirs)} project directory(ies): {[d.name for d in project_dirs]}",
                    debug,
                )
                for project_dir in project_dirs:
                    trace_files = list(project_dir.glob("trace_*.json"))
                    debug_print(
                        f"Project '{project_dir.name}' has {len(trace_files)} trace file(s)",
                        debug,
                    )
            else:
                debug_print(
                    f"Projects directory not found at {projects_dir}",
                    debug,
                )
        elif trace_id_map:
            debug_print(
                f"Built trace ID mapping with {len(trace_id_map)} trace(s)",
                debug,
            )
            # Show sample trace IDs for debugging
            sample_original_ids = list(trace_id_map.keys())[:3]
            debug_print(
                f"Sample original trace IDs in map: {sample_original_ids}", debug
            )

        # Build a map of trace_id -> project_name from trace files for project inference
        trace_to_project_map: Dict[str, str] = {}
        projects_dir = workspace_root / "projects"
        if projects_dir.exists():
            for project_dir in projects_dir.iterdir():
                if not project_dir.is_dir():
                    continue
                project_name = project_dir.name
                for trace_file in project_dir.glob("trace_*.json"):
                    try:
                        with open(trace_file, "r", encoding="utf-8") as f:
                            trace_data = json.load(f)
                        original_trace_id = trace_data.get("trace", {}).get("id")
                        if original_trace_id:
                            trace_to_project_map[original_trace_id] = project_name
                    except Exception:
                        continue

        imported_count = 0
        skipped_count = 0
        error_count = 0
        for experiment_file in experiment_files:
            try:
                experiment_data = load_experiment_data(experiment_file)

                experiment_info = experiment_data.experiment
                experiment_name = experiment_info.get("name", "")

                # Debug: Check trace IDs in experiment items vs trace_id_map
                if debug and trace_id_map:
                    items_data = experiment_data.items
                    experiment_trace_ids = []
                    for item_data in items_data:
                        trace_id = handle_trace_reference(item_data)
                        if trace_id:
                            experiment_trace_ids.append(trace_id)

                    if experiment_trace_ids:
                        console.print(
                            f"[blue]Experiment '{experiment_name}' references {len(experiment_trace_ids)} trace(s)[/blue]"
                        )
                        matched = sum(
                            1 for tid in experiment_trace_ids if tid in trace_id_map
                        )
                        console.print(
                            f"[blue]{matched}/{len(experiment_trace_ids)} trace IDs found in trace_id_map[/blue]"
                        )
                        if matched < len(experiment_trace_ids):
                            missing = [
                                tid
                                for tid in experiment_trace_ids
                                if tid not in trace_id_map
                            ]
                            console.print(
                                f"[yellow]Missing trace IDs: {missing[:5]}[/yellow]"
                            )

                # Filter by name pattern if specified
                if name_pattern and not matches_name_pattern(
                    experiment_name, name_pattern
                ):
                    debug_print(
                        f"Skipping experiment {experiment_name} (doesn't match pattern)",
                        debug,
                    )
                    skipped_count += 1
                    continue

                if dry_run:
                    console.print(
                        f"[blue]Would import experiment: {experiment_name}[/blue]"
                    )
                    imported_count += 1
                    continue

                debug_print(f"Importing experiment: {experiment_name}", debug)

                # Determine project name: try metadata first, then infer from trace files
                project_for_logs = (experiment_info.get("metadata") or {}).get(
                    "project_name"
                )

                # If no project in metadata, try to infer from trace files
                if not project_for_logs and trace_to_project_map:
                    items_data = experiment_data.items
                    # Find the first trace_id in items and use its project
                    for item_data in items_data:
                        trace_id = handle_trace_reference(item_data)
                        if trace_id and trace_id in trace_to_project_map:
                            project_for_logs = trace_to_project_map[trace_id]
                            debug_print(
                                f"Inferred project name '{project_for_logs}' from trace files",
                                debug,
                            )
                            break

                # Default to "default" if still not found
                if not project_for_logs:
                    project_for_logs = "default"
                    debug_print(
                        "Using default project name (no project found in metadata or trace files)",
                        debug,
                    )

                # Use trace_id_map and dataset_item_id_map to translate IDs (empty dicts if None)
                # Note: dataset_item_id_map is already a dict (not None) from _build_dataset_item_id_map
                success = recreate_experiment(
                    client,
                    experiment_data,
                    project_for_logs,
                    trace_id_map or {},
                    dataset_item_id_map,
                    dry_run,
                    debug,
                )

                if success:
                    imported_count += 1
                    debug_print(f"Imported experiment: {experiment_name}", debug)

            except Exception as e:
                console.print(
                    f"[red]Error importing experiment from {experiment_file}: {e}[/red]"
                )
                error_count += 1
                continue

        return {
            "experiments": imported_count,
            "experiments_skipped": skipped_count,
            "experiments_errors": error_count,
            "datasets": datasets_stats.get("datasets", 0),
            "datasets_skipped": datasets_stats.get("datasets_skipped", 0),
            "datasets_errors": datasets_stats.get("datasets_errors", 0),
            "prompts": prompts_stats.get("prompts", 0),
            "prompts_skipped": prompts_stats.get("prompts_skipped", 0),
            "prompts_errors": prompts_stats.get("prompts_errors", 0),
            "traces": traces_stats.get("traces", 0),
            "traces_errors": traces_stats.get("traces_errors", 0),
        }

    except Exception as e:
        console.print(f"[red]Error importing experiments: {e}[/red]")
        return {
            "experiments": 0,
            "experiments_skipped": 0,
            "experiments_errors": 1,
            "datasets": 0,
            "datasets_skipped": 0,
            "datasets_errors": 0,
            "prompts": 0,
            "prompts_skipped": 0,
            "prompts_errors": 0,
            "traces": 0,
            "traces_errors": 0,
        }
