"""Experiment import functionality.

Note: Experiment import copies traces but not their full span trees (LLM task and
metrics calculation spans). This is sufficient for experiment representation but
not a "full & honest" migration. Spans are imported as part of trace import,
not experiment recreation.
"""

import json
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

import opik
from opik.api_objects.dataset import dataset_item as dataset_item_module
import opik.id_helpers as id_helpers_module  # type: ignore
from opik.rest_api.types.experiment_item import ExperimentItem
from rich.console import Console

from .utils import (
    handle_trace_reference,
    translate_trace_id,
    matches_name_pattern,
    clean_feedback_scores,
)
from .prompt import import_prompts_from_directory

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


def recreate_experiment(
    client: opik.Opik,
    experiment_data: ExperimentData,
    project_name: str,
    trace_id_map: Dict[str, str],
    dry_run: bool = False,
    debug: bool = False,
) -> bool:
    """Recreate a single experiment from exported data.

    Args:
        client: Opik client instance
        experiment_data: Experiment data structure from export
        project_name: Name of the project to create the experiment in
        trace_id_map: Mapping from original trace IDs to new trace IDs (required, can be empty dict)
        dry_run: If True, only simulate the import without making changes
        debug: If True, print debug messages

    Note: This function expects that traces have already been imported into the target workspace.
    When traces are imported, they receive new IDs. The trace_id_map maps original trace IDs
    to the newly created trace IDs. Items referencing traces not in trace_id_map will be skipped.
    An empty trace_id_map is valid and will result in all items being skipped.
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
        dataset = client.get_or_create_dataset(
            name=dataset_name,
            description=f"Recreated dataset for experiment {experiment_name}",
        )

        if debug:
            console.print(
                f"[blue]Using dataset '{dataset_name}' for experiment '{experiment_name}'[/blue]"
            )

        # Create the experiment
        experiment = client.create_experiment(
            dataset_name=dataset_name,
            name=experiment_name,
            experiment_config=experiment_info.get("metadata"),
            type=experiment_info.get("type", "regular"),
        )

        if debug:
            console.print(
                f"[blue]Created experiment '{experiment_name}' with ID: {experiment.id}[/blue]"
            )

        # Process experiment items and accumulate dataset items for batch insertion
        # Track pending items: (dataset_item_obj, new_trace_id)
        pending_items: List[tuple[Any, str]] = []
        successful_items = 0
        skipped_items = 0

        for item_data in items_data:
            # Handle trace reference (from deduplicated exports)
            trace_id = handle_trace_reference(item_data)
            if not trace_id:
                if debug:
                    console.print(
                        f"[yellow]Warning: No trace ID found in item {item_data.get('id', 'unknown')}, skipping item[/yellow]"
                    )
                skipped_items += 1
                continue

            # Translate trace id from source (workspace A) to newly created trace id (workspace B)
            new_trace_id = translate_trace_id(trace_id, trace_id_map)
            if not new_trace_id:
                if debug:
                    console.print(
                        f"[yellow]Warning: No mapping for trace {trace_id}. "
                        f"Trace ID map has {len(trace_id_map)} entries. Skipping item.[/yellow]"
                    )
                    if trace_id_map and debug:
                        # Show first few trace IDs in map for debugging
                        sample_ids = list(trace_id_map.keys())[:3]
                        console.print(
                            f"[blue]Sample trace IDs in map: {sample_ids}[/blue]"
                        )
                skipped_items += 1
                continue

            if debug:
                console.print(f"[blue]Mapped trace {trace_id} -> {new_trace_id}[/blue]")

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
                # Always generate new IDs for dataset items to avoid workspace conflicts
                # The exported IDs might not be valid in the target workspace

                # Build DatasetItem with all fields from dataset_item_data
                # Exclude 'id' as we'll generate a new one
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

                # Always generate a new ID for the dataset item
                # This ensures the ID is valid in the target workspace
                try:
                    chosen_id = id_helpers_module.generate_id()  # type: ignore[attr-defined]
                except Exception:
                    # Fallback: create without ID and let backend generate it
                    chosen_id = None

                # Create DatasetItem object with new ID
                if chosen_id is not None:
                    ds_obj = dataset_item_module.DatasetItem(id=chosen_id, **content)  # type: ignore[attr-defined]
                else:
                    ds_obj = dataset_item_module.DatasetItem(**content)  # type: ignore[attr-defined]

                # Store for batch insertion
                pending_items.append((ds_obj, new_trace_id))

            except Exception as e:
                console.print(
                    f"[yellow]Warning: Failed to handle dataset item: {e}[/yellow]"
                )
                skipped_items += 1
                continue

        # Batch insert all dataset items
        rest_experiment_items = []
        if pending_items:
            try:
                # Extract dataset items for batch insertion
                dataset_items_to_insert = [ds_obj for ds_obj, _ in pending_items]

                # Batch insert dataset items
                # Note: We use the IDs we generated to ensure consistency
                dataset.__internal_api__insert_items_as_dataclasses__(
                    items=dataset_items_to_insert
                )

                # Flush client to ensure dataset items are persisted before using their IDs
                # Use a longer timeout to ensure items are fully persisted
                import time

                flush_success = client.flush(timeout=30)
                if not flush_success:
                    console.print(
                        "[yellow]Warning: Flush may not have completed fully. Dataset items might not be persisted yet.[/yellow]"
                    )
                if debug:
                    console.print(
                        "[blue]Flushed client after inserting dataset items (timeout=30s)[/blue]"
                    )

                # Small delay to ensure backend has processed the items
                time.sleep(1)

                # Create REST API experiment items with actual IDs
                # Use REST API directly instead of streamer for more reliable insertion
                for ds_obj, new_trace_id in pending_items:
                    if ds_obj.id:
                        # Generate ID for experiment item
                        experiment_item_id = id_helpers_module.generate_id()
                        rest_experiment_items.append(
                            ExperimentItem(
                                id=experiment_item_id,
                                experiment_id=experiment.id,
                                dataset_item_id=ds_obj.id,
                                trace_id=new_trace_id,
                            )
                        )
                        successful_items += 1
                        if debug:
                            console.print(
                                f"[blue]Prepared experiment item: dataset_item_id={ds_obj.id}, trace_id={new_trace_id}[/blue]"
                            )
                    else:
                        console.print(
                            "[yellow]Warning: Dataset item inserted but has no ID, skipping experiment item[/yellow]"
                        )
                        skipped_items += 1

            except Exception as e:
                console.print(
                    f"[yellow]Warning: Failed to batch insert dataset items: {e}[/yellow]"
                )
                # Fallback: try individual inserts
                for ds_obj, new_trace_id in pending_items:
                    try:
                        dataset.__internal_api__insert_items_as_dataclasses__(
                            items=[ds_obj]
                        )
                        # Flush after each item to ensure it's persisted
                        client.flush()
                        if ds_obj.id:
                            experiment_item_id = id_helpers_module.generate_id()
                            rest_experiment_items.append(
                                ExperimentItem(
                                    id=experiment_item_id,
                                    experiment_id=experiment.id,
                                    dataset_item_id=ds_obj.id,
                                    trace_id=new_trace_id,
                                )
                            )
                            successful_items += 1
                    except Exception:
                        skipped_items += 1
                        continue

        # Insert experiment items using REST API directly (more reliable than streamer)
        if rest_experiment_items:
            if debug:
                console.print(
                    f"[blue]Inserting {len(rest_experiment_items)} experiment items via REST API...[/blue]"
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
                        f"[yellow]Skipped {skipped_items} items due to missing data[/yellow]"
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
            if debug and trace_id_map:
                console.print(
                    f"[blue]Trace ID map has {len(trace_id_map)} entries but no items were created[/blue]"
                )
            elif debug:
                console.print(
                    "[yellow]No trace ID map available - traces may not have been imported[/yellow]"
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
    """Recreate experiments from JSON files.

    Args:
        trace_id_map: Mapping from original trace IDs to new trace IDs.
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
        if debug:
            console.print(
                f"[blue]No projects directory found at {projects_dir}, skipping trace import[/blue]"
            )
        return trace_id_map, {"traces": 0, "traces_errors": 0}

    project_dirs = [d for d in projects_dir.iterdir() if d.is_dir()]

    if not project_dirs:
        if debug:
            console.print(
                "[blue]No project directories found, skipping trace import[/blue]"
            )
        return trace_id_map, {"traces": 0, "traces_errors": 0}

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
                traces_imported += 1

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
        if debug:
            console.print(
                f"[green]Imported {len(trace_id_map)} trace(s) and built trace ID mapping[/green]"
            )

    return trace_id_map, {"traces": traces_imported, "traces_errors": traces_errors}


def import_experiments_from_directory(
    client: opik.Opik,
    source_dir: Path,
    dry_run: bool,
    name_pattern: Optional[str],
    debug: bool,
    recreate_experiments_flag: bool,
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
            if debug:
                console.print(
                    "[blue]Importing prompts from prompts directory...[/blue]"
                )
            prompts_stats = import_prompts_from_directory(
                client, prompts_dir, dry_run, name_pattern, debug
            )
            if prompts_stats.get("prompts", 0) > 0 and not dry_run:
                # Flush client to ensure prompts are persisted
                client.flush()
                if debug:
                    console.print(
                        f"[green]Imported {prompts_stats.get('prompts', 0)} prompt(s)[/green]"
                    )
        elif debug:
            console.print(
                "[blue]No prompts directory found, skipping prompt import[/blue]"
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
            if debug:
                projects_dir = workspace_root / "projects"
                if projects_dir.exists():
                    project_dirs = [d for d in projects_dir.iterdir() if d.is_dir()]
                    console.print(
                        f"[blue]Found {len(project_dirs)} project directory(ies): {[d.name for d in project_dirs]}[/blue]"
                    )
                    for project_dir in project_dirs:
                        trace_files = list(project_dir.glob("trace_*.json"))
                        console.print(
                            f"[blue]Project '{project_dir.name}' has {len(trace_files)} trace file(s)[/blue]"
                        )
                else:
                    console.print(
                        f"[blue]Projects directory not found at {projects_dir}[/blue]"
                    )
        elif trace_id_map and debug:
            console.print(
                f"[green]Built trace ID mapping with {len(trace_id_map)} trace(s)[/green]"
            )
            # Show sample trace IDs for debugging
            sample_original_ids = list(trace_id_map.keys())[:3]
            console.print(
                f"[blue]Sample original trace IDs in map: {sample_original_ids}[/blue]"
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
                    if debug:
                        console.print(
                            f"[blue]Skipping experiment {experiment_name} (doesn't match pattern)[/blue]"
                        )
                    skipped_count += 1
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
                            if debug:
                                console.print(
                                    f"[blue]Inferred project name '{project_for_logs}' from trace files[/blue]"
                                )
                            break

                # Default to "default" if still not found
                if not project_for_logs:
                    project_for_logs = "default"
                    if debug:
                        console.print(
                            "[blue]Using default project name (no project found in metadata or trace files)[/blue]"
                        )

                # Use trace_id_map to translate trace IDs (empty dict if None)
                success = recreate_experiment(
                    client,
                    experiment_data,
                    project_for_logs,
                    trace_id_map or {},
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
                error_count += 1
                continue

        return {
            "experiments": imported_count,
            "experiments_skipped": skipped_count,
            "experiments_errors": error_count,
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
            "prompts": 0,
            "prompts_skipped": 0,
            "prompts_errors": 0,
            "traces": 0,
            "traces_errors": 0,
        }
